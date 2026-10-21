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
 * npc.break_block —— 破坏一个方块（统一柜版）。
 * <p>
 * 规则一点没变，只是执行通道换了：职业白名单 + 领地保护 + 32 格距离 +
 * 神使审计，全部仍在 {@code WorldInteractionService.breakBlockAt} 里把关
 * （那里同时是插件的权限层）。别名 {@code break_block} 桥接老工具名。
 */
public final class NpcBreakBlockTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcBreakBlockTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.break_block";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("break_block");
    }

    @Override
    public String description() {
        return "破坏指定坐标的方块（正常掉落）。权限由插件把关：职业白名单、玩家领地禁拆、32 格内、神使审计。";
    }

    @Override
    public Category category() {
        return Category.ACTION;
    }

    @Override
    public Risk risk() {
        // 注意不能标 HIGH：策略层对 HIGH 工具要求神使，会把农夫正常收庄稼也挡掉；
        // 职业/领地这类细粒度权限在执行体的 PermissionManager 里判。
        return Risk.MEDIUM;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("x", ToolSchema.Param.number("方块 X 坐标").min(-30_000_000).max(30_000_000), true)
                .param("y", ToolSchema.Param.number("方块 Y 坐标").min(-64).max(320), true)
                .param("z", ToolSchema.Param.number("方块 Z 坐标").min(-30_000_000).max(30_000_000), true)
                .param("message", ToolSchema.Param.string("动手时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        String feedback = plugin.getWorldInteraction().breakBlockAt(vs, context.playerUuid(),
                ToolArgs.number(arguments, "x", Double.NaN),
                ToolArgs.number(arguments, "y", Double.NaN),
                ToolArgs.number(arguments, "z", Double.NaN));
        if (feedback != null) {
            return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED, stripColor(feedback));
        }
        JsonObject data = new JsonObject();
        data.addProperty("broken", true);
        return ToolResult.completed(data);
    }

    /** 去掉反馈行开头的 § 色彩码（统一分发层会按自己的口径重新着色/记录） */
    static String stripColor(String s) {
        return s == null ? null : s.replaceFirst("^(§.)+", "");
    }
}
