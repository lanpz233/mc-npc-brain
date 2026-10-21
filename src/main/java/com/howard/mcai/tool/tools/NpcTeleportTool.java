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
 * npc.teleport —— NPC 把自己传送到坐标（统一柜版）。
 * <p>
 * 规则不变：只传自己、32 格内、落点安全检查（悬空/岩浆/站不住都拒绝）、
 * 神使审计——把关仍在 {@code WorldInteractionService.teleportSelfTo}。
 * 别名 {@code teleport} 桥接老工具名。
 */
public final class NpcTeleportTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcTeleportTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.teleport";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("teleport");
    }

    @Override
    public String description() {
        return "把 NPC 自己传送到指定坐标。只传自己、32 格内、落点必须安全，由插件把关。";
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
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("x", ToolSchema.Param.number("目标 X 坐标").min(-30_000_000).max(30_000_000), true)
                .param("y", ToolSchema.Param.number("目标 Y 坐标").min(-64).max(512), true)
                .param("z", ToolSchema.Param.number("目标 Z 坐标").min(-30_000_000).max(30_000_000), true)
                .param("message", ToolSchema.Param.string("传送时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        String feedback = plugin.getWorldInteraction().teleportSelfTo(vs, context.playerUuid(),
                ToolArgs.number(arguments, "x", Double.NaN),
                ToolArgs.number(arguments, "y", Double.NaN),
                ToolArgs.number(arguments, "z", Double.NaN));
        if (feedback != null) {
            return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED,
                    NpcBreakBlockTool.stripColor(feedback));
        }
        JsonObject data = new JsonObject();
        data.addProperty("teleported", true);
        return ToolResult.completed(data);
    }
}
