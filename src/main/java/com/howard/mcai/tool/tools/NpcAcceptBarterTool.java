package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.WorldInteractionService;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * accept_barter —— 背包换物的成交开关（v0.4.1，服主定的新流程）。
 * <p>
 * 玩家在背包界面选中 NPC 的东西、拿自己的一格东西出价后，插件把价值与
 * 有利程度算成简报给模型（ai/BarterBrief）；模型愿意换才调本工具。
 * 本工具不带任何数量参数——换什么、换多少全在登记时钉死（VillagerState.PendingBarter），
 * 执行时 WorldInteractionService 复核双方物品还在、一次性交换，
 * 模型改口或东西没了都换不成，绝不半执行。
 */
public final class NpcAcceptBarterTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcAcceptBarterTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.accept_barter";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("accept_barter");
    }

    @Override
    public String description() {
        return "同意玩家在你背包前提的换物（他选你的 1 件东西、拿他的一格东西换）；"
                + "价值与划算程度系统简报已算清，你点头交换才成交";
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
                .param("message", ToolSchema.Param.string("同意时说的一句中文台词").maxLength(120))
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
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST, "玩家不在线");
        }
        WorldInteractionService.BarterResult r = plugin.getWorldInteraction().executeBarter(vs, player);
        JsonObject data = new JsonObject();
        data.addProperty("swapped", r.swapped());
        if (!r.swapped()) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST,
                    r.errorForPlayer() != null ? r.errorForPlayer() : "换物没能成交");
        }
        return ToolResult.completed(data);
    }
}
