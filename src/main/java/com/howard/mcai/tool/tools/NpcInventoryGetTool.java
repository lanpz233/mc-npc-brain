package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.GameStateProvider;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

/**
 * npc.inventory.get —— 读取 NPC 背包与当前交易价格。
 */
public final class NpcInventoryGetTool implements NpcTool {

    private final GameStateProvider state;

    public NpcInventoryGetTool(GameStateProvider state) {
        this.state = state;
    }

    @Override
    public String name() {
        return "npc.inventory.get";
    }

    @Override
    public String description() {
        return "读取指定 NPC 的背包物品（含中文名与数量）、当前动态价格和已上架商品。";
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
        JsonObject data = state.npcInventory(context.npcId());
        if (data == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND,
                    "NPC 不存在或实体已失效: " + context.npcId());
        }
        return ToolResult.completed(data);
    }
}
