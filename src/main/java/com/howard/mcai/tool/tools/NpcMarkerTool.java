package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

import java.util.Set;

/**
 * 登记标记工具 —— 给「效果不在工具执行里、而在对话管线里」的轻动作占一个统一柜位置：
 * <ul>
 *   <li>{@code npc.say}：台词由 CentralPlanner 的 speak 统一发出（气泡+聊天框+历史），
 *       工具本身无事可做；</li>
 *   <li>{@code npc.wave} / {@code npc.nod} / {@code npc.shake_head}：可见小动作由
 *       CentralPlanner.playGesture 按工具名统一触发（在执行分发之前），工具只登记；</li>
 *   <li>{@code npc.idle}：本 tick 不行动，天然无操作。</li>
 * </ul>
 * 登记的意义：全部模型动作收敛到同一本账——注册表可查、分发有日志、策略可管控，
 * 不再有「某些动作走柜子、某些动作走 switch」的双轨。执行恒为成功且无副作用。
 */
public final class NpcMarkerTool implements NpcTool {

    private final String name;
    private final String alias;
    private final String description;
    private final ToolSchema schema;

    public NpcMarkerTool(String name, String alias, String description, ToolSchema schema) {
        this.name = name;
        this.alias = alias;
        this.description = description;
        this.schema = schema;
    }

    /** 只说话：npc.say（message 必填，与模型菜单一致） */
    public static NpcMarkerTool say() {
        return new NpcMarkerTool("npc.say", "say",
                "说一句话给玩家听。台词由对话管线统一发出，本工具只做登记。",
                ToolSchema.builder()
                        .param("message", ToolSchema.Param.string("要对玩家说的中文台词").maxLength(120), true)
                        .build());
    }

    /** 挥手：npc.wave */
    public static NpcMarkerTool wave() {
        return new NpcMarkerTool("npc.wave", "wave",
                "挥手打招呼。可见动作由对话管线按工具名触发，本工具只做登记。",
                ToolSchema.builder().build());
    }

    /** 点头：npc.nod */
    public static NpcMarkerTool nod() {
        return new NpcMarkerTool("npc.nod", "nod",
                "点头表示同意、认可。可见动作由对话管线按工具名触发，本工具只做登记。",
                ToolSchema.builder().build());
    }

    /** 摇头：npc.shake_head */
    public static NpcMarkerTool shakeHead() {
        return new NpcMarkerTool("npc.shake_head", "shake_head",
                "摇头表示不赞同或拒绝。可见动作由对话管线按工具名触发，本工具只做登记。",
                ToolSchema.builder().build());
    }

    /** 待机：npc.idle */
    public static NpcMarkerTool idle() {
        return new NpcMarkerTool("npc.idle", "idle",
                "什么都不做（本 tick 不需要行为）。",
                ToolSchema.builder().build());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> aliases() {
        return Set.of(alias);
    }

    @Override
    public String description() {
        return description;
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
        return schema;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        JsonObject data = new JsonObject();
        data.addProperty("registered", true);
        return ToolResult.completed(data);
    }
}
