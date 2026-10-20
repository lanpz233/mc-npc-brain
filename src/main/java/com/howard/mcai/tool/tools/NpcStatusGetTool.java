package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.GameStateProvider;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

/**
 * npc.status.get —— 读取 NPC 的权威状态快照（血量、职业、位置、情绪、需求、对话状态）。
 */
public final class NpcStatusGetTool implements NpcTool {

    private final GameStateProvider state;

    public NpcStatusGetTool(GameStateProvider state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "npc.status.get";
    }

    @Override
    public String description() {
        return "读取指定 NPC 的当前状态：血量、职业、位置、开放度、情绪、需求、是否在对话、LLM 是否忙碌。";
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
        JsonObject data = state.npcStatus(context.npcId(), context.playerUuid());
        if (data == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND,
                    "NPC 不存在或实体已失效: " + context.npcId());
        }
        return ToolResult.completed(data);
    }
}
