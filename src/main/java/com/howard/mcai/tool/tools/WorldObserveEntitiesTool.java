package com.howard.mcai.tool.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.howard.mcai.tool.GameStateProvider;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

/**
 * world.observe.entities —— 读取 NPC 周围实体（玩家 / NPC / 怪物 / 动物）。
 * <p>
 * 只读，半径和返回条数都有硬上限，避免模型一次拉取过大范围导致主线程卡顿。
 */
public final class WorldObserveEntitiesTool implements NpcTool {

    private final GameStateProvider state;

    public WorldObserveEntitiesTool(GameStateProvider state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "world.observe.entities";
    }

    @Override
    public String description() {
        return "观察 NPC 周围的实体，返回类型、名字、距离、血量、是否敌对。半径最大 32 格，最多 64 条。";
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
                .param("radius", ToolSchema.Param.number("观察半径（格），默认 8，最大 32").min(0.5).max(32))
                .param("limit", ToolSchema.Param.integer("最多返回条数，默认 32，最大 64").min(1).max(64))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        if (!state.npcExists(context.npcId())) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND,
                    "NPC 不存在或实体已失效: " + context.npcId());
        }
        double radius = ToolArgs.number(arguments, "radius", 8.0);
        int limit = ToolArgs.integer(arguments, "limit", 32);

        JsonArray entities = state.nearbyEntities(context.npcId(), radius, limit);

        JsonObject data = new JsonObject();
        data.addProperty("radius", radius);
        data.addProperty("limit", limit);
        data.addProperty("count", entities.size());
        data.add("entities", entities);
        return ToolResult.completed(data);
    }
}
