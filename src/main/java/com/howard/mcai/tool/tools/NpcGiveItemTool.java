package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.GiftPolicy;
import com.howard.mcai.security.NpcRank;
import com.howard.mcai.security.PermissionManager;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.Set;

/**
 * npc.give_item —— 把 NPC 库存里的东西给玩家（统一柜版，替代老后门
 * WorldInteractionService.giveItem 的掷骰子实现）。
 * <p>
 * 裁决全部由 {@link GiftPolicy} 按确定性规矩算（好感门槛/库存/单次上限/稀有日账/
 * 口粮保留），不再掷概率：同样的局面每次都是同一个结果，模型只有表态权。
 * 别名 {@code give_item} 桥接老工具名——对话模型菜单不变，执行已走统一分发
 * （参数校验、策略、审计都在 Dispatcher 一条路上）。
 * <p>
 * 与旧实现保留一致的口径：神使可凭空补足差额（记审计）；代码层已交付同款物品
 * 8 秒内静默去重；玩家背包满时溢出掉在脚下。
 */
public final class NpcGiveItemTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcGiveItemTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.give_item";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("give_item");
    }

    @Override
    public String description() {
        return "把 NPC 库存里的东西送给当前玩家。给不给、给几个由插件按规矩裁决"
                + "（好感门槛、库存、口粮保留、稀有物品一天一件），模型只负责表态与台词。";
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
                .param("item", ToolSchema.Param.string("物品中文名，例如 面包/铁剑/绿宝石").maxLength(32), true)
                .param("count", ToolSchema.Param.number("给几个，默认 1").min(1).max(64))
                .param("message", ToolSchema.Param.string("送东西时说的中文台词").maxLength(120))
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
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST, "附近没有玩家可给");
        }
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        Material mat = com.howard.mcai.security.WorldInteractionService.resolveItem(
                ToolArgs.string(arguments, "item", null));
        // 已登记的索要优先：物品在登记时已由插件按玩家原话定死，模型的 item 参数只是它自己的
        // 复述，写错一个字（实测把面包说成"一袋"）解析就失败、整轮交付泡汤，玩家只看到一句
        // 莫名其妙的"这次没能把东西给你"。有登记时以登记的物品为准，模型参数不算数。
        VillagerState.ItemRequest pending = vs.peekItemRequest(player.getUniqueId());
        if (pending != null) {
            mat = pending.mat();
        }
        if (mat == null || mat.isAir()) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_ARGUMENT, "不知道这是什么东西");
        }
        // 代码层刚交付过同款（索要直交/免费送）→ 静默跳过，防同轮送出两份
        if (vs.wasAutoGivenRecently(mat)) {
            JsonObject data = new JsonObject();
            data.addProperty("item", mat.name());
            data.addProperty("delivered", 0);
            data.addProperty("deduped", true);
            return ToolResult.completed(data);
        }

        // 已登记的索要（聊天讨要/GUI 点击）：数量与裁决都在登记时钉死，模型参数不算数
        VillagerState.ItemRequest pinnedReq = vs.consumeItemRequest(player.getUniqueId(), mat);
        int pinned = pinnedReq != null ? pinnedReq.count() : 0;
        boolean pinnedApproved = pinnedReq != null && pinnedReq.pluginApproved();

        boolean envoy = plugin.getPermissionManager().rankOf(vs, player.getUniqueId()) == NpcRank.ENVOY;
        String today = LocalDate.now().toString();
        GiftPolicy.Verdict verdict = GiftPolicy.decide(
                envoy,
                pinned, pinnedApproved,
                ToolArgs.integer(arguments, "count", 1),
                vs.stockOf(mat),
                vs.emotion().favor(player.getUniqueId()),
                PermissionManager.valueOf(mat),
                mat.isEdible(),
                mat.isEdible() ? vs.totalFoodStock() : 0,
                vs.rareGiftGivenOn(player.getUniqueId(), today));

        if (!verdict.allowed()) {
            plugin.getLogger().info("§c[MC-AI] give_item 拒绝(规则): " + npcName + " → " + player.getName()
                    + " " + mat.name() + " — " + verdict.reason());
            return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED,
                    npcName + verdict.reason());
        }

        int give = verdict.giveCount();
        int stock = vs.stockOf(mat);
        int fromStock = Math.min(give, stock);
        if (fromStock > 0) vs.removeFromInventory(mat, fromStock);
        if (give > fromStock) {
            // 神使凭空补足差额 —— 必须审计（与旧规一致）
            plugin.getAuditLog().log(npcName, vs.getUniqueId(), "give_item",
                    "item=" + mat.name() + ",spawned=" + (give - fromStock)
                            + ",target=" + player.getName(), "OK");
        }
        var leftover = player.getInventory().addItem(new ItemStack(mat, give));
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        if (PermissionManager.valueOf(mat) >= GiftPolicy.RARE_VALUE_MIN && !envoy) {
            vs.markRareGift(player.getUniqueId(), today);
        }
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] give_item(统一柜): " + npcName + " → " + player.getName()
                + " " + mat.name() + " x" + give
                + (give > fromStock ? "（神使凭空补 " + (give - fromStock) + "，已审计）" : "（出库存）"));

        JsonObject data = new JsonObject();
        data.addProperty("item", mat.name());
        data.addProperty("delivered", give);
        return ToolResult.completed(data);
    }
}
