package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.WorldInteractionService;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Material;

import java.util.Set;

/**
 * npc.equip —— 从 NPC 库存拿一件东西穿戴/拿在手上展示（统一柜版）。
 * <p>
 * 规则不变：必须真有库存、旧装备先收回库存再换新（不凭空消失）——
 * 执行体仍是 {@code WorldInteractionService.equipItem}。
 * 别名 {@code equip} 桥接老工具名。它是老后门最后一件迁柜的世界交互动作。
 */
public final class NpcEquipTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcEquipTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.equip";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("equip");
    }

    @Override
    public String description() {
        return "从 NPC 库存拿一件东西穿戴上或拿在主手展示（消耗库存，旧装备收回库存）。";
    }

    @Override
    public Category category() {
        return Category.ACTION;
    }

    @Override
    public Risk risk() {
        return Risk.LOW;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("item", ToolSchema.Param.string("物品中文名（必须在 NPC 库存里）").maxLength(32), true)
                .param("slot", ToolSchema.Param.string("mainhand/helmet/chestplate/leggings/boots，默认 mainhand").maxLength(16))
                .param("message", ToolSchema.Param.string("穿戴时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        Material mat = WorldInteractionService.resolveItem(ToolArgs.string(arguments, "item", null));
        if (mat == null || mat.isAir()) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_ARGUMENT, "不知道这是什么东西");
        }
        String slot = ToolArgs.string(arguments, "slot", "mainhand");
        String feedback = plugin.getWorldInteraction().equipItem(vs, mat, slot);
        if (feedback != null) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST,
                    NpcBreakBlockTool.stripColor(feedback));
        }
        JsonObject data = new JsonObject();
        data.addProperty("equipped", true);
        data.addProperty("item", mat.name());
        data.addProperty("slot", slot);
        return ToolResult.completed(data);
    }
}
