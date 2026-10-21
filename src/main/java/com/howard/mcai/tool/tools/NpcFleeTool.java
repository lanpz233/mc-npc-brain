package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;

/**
 * npc.flee —— 朝互动玩家反方向窜一步并涨恐惧（统一柜版）。
 * <p>
 * 行为与老后门逐行一致：对话中「站立不动」——只涨恐惧不真移动；
 * 非对话状态才给一个背离玩家的速度冲量。别名 {@code flee} 桥接老工具名。
 */
public final class NpcFleeTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcFleeTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.flee";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("flee");
    }

    @Override
    public String description() {
        return "从威胁源处逃跑：朝当前玩家反方向窜几步（对话中不移动，只涨恐惧）。";
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
        return ToolSchema.builder().build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null || vs.entity() == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        Player player = context.playerUuid() != null ? Bukkit.getPlayer(context.playerUuid()) : null;
        boolean moved = false;
        if (player != null && player.isOnline() && vs.interactingPlayer() == null) {
            var villager = vs.entity();
            double dx = villager.getLocation().getX() - player.getLocation().getX();
            double dz = villager.getLocation().getZ() - player.getLocation().getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 0) {
                villager.setVelocity(new Vector(dx / dist * 2, 0.3, dz / dist * 2));
                moved = true;
            }
        }
        vs.emotion().addFear(10);
        JsonObject data = new JsonObject();
        data.addProperty("fled", moved);
        return ToolResult.completed(data);
    }
}
