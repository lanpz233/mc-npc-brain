package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.ItemZhTable;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.WorldInteractionService;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * npc.surrender —— 打劫交割：被吓破胆的村民按谈好的价码交出财物（Fong 2026-10-23 定稿）。
 * <p>
 * 分工（服主亲自定）：给不给由“恐惧 vs 胆量”的破防骰子定（ai/RobberyWill，插件掷、
 * CentralPlanner 把结果注进局势简报）；交多少由模型在对话里跟玩家讨价还价
 * （绿宝石不封顶、先哭穷开低价、被逼再松口）。本工具只负责交割与记账，门有三道：
 * <ol>
 *   <li>破防窗口必须开着（没破防时调了也不认——他嘴上答应不算数）；</li>
 *   <li>今天没被抢过（同一天榨不出第二回，防连刷）；</li>
 *   <li>东西得真有（数量按库存砍，交不出虚数）。</li>
 * </ol>
 * 成交后当场记账：关窗、记被抢日、村庄信任大跌（VillageTrust.onRobbed）、
 * 对抢劫者好感重扣、留事实（本人记仇 + 全村传开）。
 */
public final class NpcSurrenderTool implements NpcTool {

    /** 被抢之后对抢劫者的好感重扣（记仇） */
    private static final double ROBBED_FAVOR_HIT = -30.0;

    private final McaiPlugin plugin;

    public NpcSurrenderTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.surrender";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("surrender");
    }

    @Override
    public String description() {
        return "（仅限你被威胁到撑不住时）把谈好价码的财物交给正在打劫你的玩家保命："
                + "交什么、交几个按你们谈成的来；没被吓破胆时调用会被系统拦下";
    }

    @Override
    public Category category() {
        return Category.ACTION;
    }

    @Override
    public Risk risk() {
        return Risk.MEDIUM;
    }

    @Override
    public boolean requiresPlayer() {
        return true;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("item", ToolSchema.Param.string("交出的物品中文名，例如 绿宝石/面包/铁剑").maxLength(32), true)
                .param("count", ToolSchema.Param.number("交几个（你们谈成的数量）").min(1).max(64))
                .param("message", ToolSchema.Param.string("交东西时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        Player player = context.playerUuid() != null ? Bukkit.getPlayer(context.playerUuid()) : null;
        if (player == null || !player.isOnline()) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST, "附近没有玩家可交");
        }
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);

        // 门一：破防窗口。没被吓破胆，他不会交——模型嘴上答应也不算数
        if (!vs.surrenderOpenFor(player.getUniqueId())) {
            plugin.getLogger().info("§c[MC-AI] surrender 拒绝(没破防): " + npcName + " ← " + player.getName());
            return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED,
                    npcName + "还没被吓破胆，不会交出东西");
        }
        // 门二：今天已经被抢过 → 榨干了，交不出第二回
        long day = vs.entity().getWorld().getFullTime() / 24000L;
        if (vs.lastRobbedDay() == day) {
            vs.closeSurrenderWindow();
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST,
                    npcName + "今天已经被抢过一次，身上真没什么了");
        }
        Material mat = WorldInteractionService.resolveItem(ToolArgs.string(arguments, "item", null));
        if (mat == null || mat.isAir()) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_ARGUMENT, "不知道这是什么东西");
        }
        // 门三：东西得真有，数量按库存砍——谈成的数超过家底，就把家底交出去
        int stock = vs.stockOf(mat);
        if (stock <= 0) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST,
                    npcName + "身上没有" + ItemZhTable.zhOf(mat));
        }
        int give = Math.min(ToolArgs.integer(arguments, "count", 1), stock);
        vs.removeFromInventory(mat, give);
        var leftover = player.getInventory().addItem(new ItemStack(mat, give));
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }

        // ===== 成交记账 =====
        vs.closeSurrenderWindow();
        vs.markRobbed(day);
        var loc = vs.entity().getLocation();
        int villageId = plugin.getVillageTrust().villageIdFor(
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        int newTrust = plugin.getVillageTrust().onRobbed(player.getUniqueId(), villageId);
        plugin.saveVillageTrust();
        vs.emotion().addFavor(player.getUniqueId(), ROBBED_FAVOR_HIT);
        String zh = ItemZhTable.zhOf(mat);
        vs.addFact("旅者 " + player.getName() + " 抢走了我的" + zh + " x" + give, player.getUniqueId());
        vs.addFact("我被旅者 " + player.getName() + " 抢了，他抢走了我的" + zh);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getAuditLog().log(npcName, vs.getUniqueId(), "surrender",
                "item=" + mat.name() + ",count=" + give + ",target=" + player.getName()
                        + ",villageTrust=" + newTrust, "OK");
        plugin.getLogger().info("§b[MC-AI] surrender(打劫交割): " + npcName + " → " + player.getName()
                + " " + mat.name() + " x" + give + "（村庄信任→" + newTrust + "）");

        JsonObject data = new JsonObject();
        data.addProperty("item", mat.name());
        data.addProperty("delivered", give);
        data.addProperty("surrendered", true);
        return ToolResult.completed(data);
    }
}
