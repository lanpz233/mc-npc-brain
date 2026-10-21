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
 * npc.place_block —— 在指定坐标放一个方块（统一柜版）。
 * <p>
 * 规则一点没变：领地保护 + 必须扣 NPC 真实库存（神使免库存、凭空部分审计）+
 * 32 格距离，全部仍在 {@code WorldInteractionService.placeBlockAt} 里把关。
 * 别名 {@code place_block} 桥接老工具名。
 */
public final class NpcPlaceBlockTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcPlaceBlockTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.place_block";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("place_block");
    }

    @Override
    public String description() {
        return "在指定坐标放置一个方块（消耗 NPC 库存；神使免库存并审计）。领地保护与距离由插件把关。";
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
                .param("x", ToolSchema.Param.number("方块 X 坐标").min(-30_000_000).max(30_000_000), true)
                .param("y", ToolSchema.Param.number("方块 Y 坐标").min(-64).max(320), true)
                .param("z", ToolSchema.Param.number("方块 Z 坐标").min(-30_000_000).max(30_000_000), true)
                .param("material", ToolSchema.Param.string("方块中文名，例如 圆石/橡木板/火把").maxLength(32), true)
                .param("message", ToolSchema.Param.string("动手时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        Material mat = WorldInteractionService.resolveItem(ToolArgs.string(arguments, "material", null));
        if (mat == null) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_ARGUMENT, "不知道这是什么方块");
        }
        String feedback = plugin.getWorldInteraction().placeBlockAt(vs, context.playerUuid(),
                ToolArgs.number(arguments, "x", Double.NaN),
                ToolArgs.number(arguments, "y", Double.NaN),
                ToolArgs.number(arguments, "z", Double.NaN),
                mat);
        if (feedback != null) {
            return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED,
                    NpcBreakBlockTool.stripColor(feedback));
        }
        JsonObject data = new JsonObject();
        data.addProperty("placed", true);
        data.addProperty("material", mat.name());
        return ToolResult.completed(data);
    }
}
