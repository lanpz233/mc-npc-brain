package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.planner.CentralPlanner;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * npc.look_at —— 转头看向当前互动玩家（统一柜版）。
 * <p>
 * 行为与老后门一致：用 setRotation 平滑转头（{@link CentralPlanner#faceTowards}），
 * 只认上下文里的互动玩家（老实现同样忽略 target_id 细节、只看眼前人）。
 * 别名 {@code look_at} 桥接老工具名。
 */
public final class NpcLookAtTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcLookAtTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.look_at";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("look_at");
    }

    @Override
    public String description() {
        return "转头看向当前互动玩家。";
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
                .param("target_id", ToolSchema.Param.string("目标实体 ID，如 player_1").maxLength(64), true)
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null || vs.entity() == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        Player player = context.playerUuid() != null ? Bukkit.getPlayer(context.playerUuid()) : null;
        if (player != null && player.isOnline()) {
            CentralPlanner.faceTowards(vs.entity(), player.getLocation());
        }
        JsonObject data = new JsonObject();
        data.addProperty("looked", player != null);
        return ToolResult.completed(data);
    }
}
