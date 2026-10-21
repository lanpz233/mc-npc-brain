package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * npc.learn —— 把学到的新知识记进永久记忆（统一柜版）。
 * <p>
 * 把关与对话老路径完全同一套（经 {@code CentralPlanner.learnViaTool}）：
 * 世界观铁律（串戏词拒存）、信任/开放度门槛、40 字截断、强制带出处。
 * 「没学成」是正常裁决而非系统失败——返回 completed 且 learned=false，
 * 对话管线的承诺守卫据此决定「我记住了」这句能不能留。
 * 别名 {@code learn} 桥接老工具名。
 */
public final class NpcLearnTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcLearnTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.learn";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("learn");
    }

    @Override
    public String description() {
        return "把刚学到的新知识记进永久记忆（带出处）。是否采信由插件按世界观铁律与信任门槛把关。";
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
                .param("content", ToolSchema.Param.string("学到的新知识本体，短中文（≤30字）").maxLength(60), true)
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        if (plugin.getPlanner() == null) {
            return ToolResult.failed(ToolResult.ErrorCode.INTERNAL_ERROR, "决策层未就绪");
        }
        Player player = context.playerUuid() != null ? Bukkit.getPlayer(context.playerUuid()) : null;
        boolean learned = plugin.getPlanner().learnViaTool(vs, arguments, player);
        JsonObject data = new JsonObject();
        data.addProperty("learned", learned);
        data.addProperty("content", ToolArgs.string(arguments, "content", ""));
        return ToolResult.completed(data);
    }
}
