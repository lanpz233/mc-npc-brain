package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

import java.util.Set;

/**
 * npc.teleport_to —— 把目标（玩家/生物）拉到 NPC 身边（统一柜版）。
 * <p>
 * 规则不变，且都是硬规矩：只有神使能传别人；传玩家必须先征得同意——
 * 发起后进入待确认，玩家在聊天框回复「好」（全词匹配、30 秒内）才真正传送，
 * 确认流程的状态与消费仍在 {@code WorldInteractionService} 里。
 * 发起确认时返回的 data.notice 是要原样转达给玩家的系统提示
 * （CentralPlanner 统一分发分支负责发送），不是失败。
 * <p>
 * 风险标 HIGH：策略层会因此要求调用方 NPC 必须是神使——与执行体的
 * 权限判定同向，多一层闸。别名 {@code teleport_to} 桥接老工具名。
 */
public final class NpcTeleportToTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcTeleportToTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.teleport_to";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("teleport_to");
    }

    @Override
    public String description() {
        return "把目标玩家或生物拉到 NPC 身边。只有神使能用；传玩家要等对方在聊天里回复「好」确认。";
    }

    @Override
    public Category category() {
        return Category.ACTION;
    }

    @Override
    public Risk risk() {
        return Risk.HIGH;
    }

    @Override
    public boolean requiresPlayer() {
        return true;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("target_id", ToolSchema.Param.string("目标的 id 或名字（玩家名/实体 id）").maxLength(64), true)
                .param("message", ToolSchema.Param.string("传送时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        String feedback = plugin.getWorldInteraction().teleportToTarget(vs,
                ToolArgs.string(arguments, "target_id", null));
        if (feedback == null) {
            JsonObject data = new JsonObject();
            data.addProperty("teleported", true);
            return ToolResult.completed(data);
        }
        String plain = NpcBreakBlockTool.stripColor(feedback);
        // 「想传送你，请回复好确认」是待确认提示而非失败：以 notice 形式完成，由上层原样转达玩家
        if (plain.contains("想传送你")) {
            JsonObject data = new JsonObject();
            data.addProperty("teleported", false);
            data.addProperty("confirm_pending", true);
            data.addProperty("notice", plain);
            return ToolResult.completed(data);
        }
        return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED, plain);
    }
}
