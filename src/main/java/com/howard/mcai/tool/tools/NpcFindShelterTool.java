package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.planner.CentralPlanner;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Location;

import java.util.Set;

/**
 * npc.find_shelter —— 找最近的遮雨处并挪过去（统一柜版）。
 * <p>
 * 行为与老后门一致：对话中不挪窝（站立不动）；非对话时用
 * {@link CentralPlanner#findNearestShelter} 在 20 格内找「上方有遮挡」的位置，
 * 再用一次性速度冲量挪过去（短距离进屋檐，没必要起一个寻路任务）。
 * 找不到时返回 shelter=false（老实现此时会把台词换成「（到处找遮雨的地方...）」，
 * 统一柜版改为照常说模型台词，此差异已知且可接受）。
 * 别名 {@code find_shelter} 桥接老工具名。
 */
public final class NpcFindShelterTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcFindShelterTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.find_shelter";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("find_shelter");
    }

    @Override
    public String description() {
        return "找最近的遮雨处（屋檐下、洞里）并挪过去。对话中不移动。";
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
        boolean found = false;
        if (vs.interactingPlayer() == null && plugin.getPlanner() != null) {
            Location shelter = plugin.getPlanner()
                    .findNearestShelter(vs.entity().getLocation(), 20);
            if (shelter != null) {
                CentralPlanner.moveEntityTowards(vs.entity(), shelter, 1.2);
                found = true;
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("shelter", found);
        return ToolResult.completed(data);
    }
}
