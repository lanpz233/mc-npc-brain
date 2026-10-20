package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.GameStateProvider;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

/**
 * world.nearby.blocks —— 统计 NPC 周围的方块类型与数量。
 * <p>
 * 只读，用于让行为模型知道"旁边有没有田/树/水/房子"这类环境事实。
 * 半径上限 16 格：方块只能逐个读取，扫描成本远高于实体查询，必须在 schema 层就挡住大范围请求。
 */
public final class WorldNearbyBlocksTool implements NpcTool {

    private final GameStateProvider state;

    public WorldNearbyBlocksTool(GameStateProvider state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "world.nearby.blocks";
    }

    @Override
    public String description() {
        return "统计 NPC 周围方块：按方块类型聚合数量，从多到少返回。半径最大 16 格，最多 64 种。";
    }

    @Override
    public Category category() {
        return Category.QUERY;
    }

    @Override
    public Risk risk() {
        return Risk.LOW;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("radius", ToolSchema.Param.number("统计半径（格），默认 8，最大 16").min(1).max(16))
                .param("limit", ToolSchema.Param.integer("最多返回多少种方块，默认 12，最大 64").min(1).max(64))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        double radius = ToolArgs.number(arguments, "radius", 8.0);
        int limit = ToolArgs.integer(arguments, "limit", 12);

        JsonObject data = state.nearbyBlocks(context.npcId(), radius, limit);
        if (data == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND,
                    "NPC 不存在或实体已失效: " + context.npcId());
        }
        return ToolResult.completed(data);
    }
}
