package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.GameStateProvider;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

/**
 * world.environment —— 读取 NPC 当前所在世界的环境事实。
 * <p>
 * 时间、天气、群系这些是模型最容易凭空编造的部分（例如明明是夜里却说天很亮）。
 * 单独做成只读工具，让行为模型能直接拿到服务端权威值，而不是从长 prompt 里猜。
 */
public final class WorldEnvironmentTool implements NpcTool {

    private final GameStateProvider state;

    public WorldEnvironmentTool(GameStateProvider state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "world.environment";
    }

    @Override
    public String description() {
        return "读取 NPC 所在世界的环境：群系、游戏内时间、昼夜时段、天气、降雪/降雨、光照。";
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
        return ToolSchema.empty();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        JsonObject data = state.environment(context.npcId());
        if (data == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND,
                    "NPC 不存在或实体已失效: " + context.npcId());
        }
        return ToolResult.completed(data);
    }
}
