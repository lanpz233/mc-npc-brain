package com.howard.mcai.ai;

import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.ToolRegistry;
import com.howard.mcai.tool.ToolRequest;

import java.util.List;
import java.util.UUID;

/**
 * 模型输出 → 统一工具请求的适配层。
 * <p>
 * 模型（Qwen 原生 tool_calls）与插件自主决策最终都归一到 {@link ToolRequest}，
 * 之后的参数校验、权限、幂等、任务生命周期完全一致 —— 模型不直接接触 Bukkit。
 * <p>
 * 这里只做"名字归一 + 请求包装"，不做权限判断：那是 {@code ToolPolicyService} 的职责，
 * 避免两处逻辑不一致。
 */
public final class LlmToolAdapter {

    private final ToolRegistry registry;

    public LlmToolAdapter(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 把一次模型工具调用转成统一请求。
     *
     * @param call       模型给出的调用（name 可能是 wire 名 npc_move_to，也可能是规范名 npc.move_to）
     * @param callerType 调用来源（DIALOG_MODEL / AUTONOMY 等）
     * @param callerId   调用方标识（对话:玩家名；行为:模型名），用于幂等键与日志
     * @return 请求；工具未注册时返回 null（由调用方决定降级策略）
     */
    public ToolRequest toRequest(ToolCall call, CallerType callerType, String callerId,
                                 UUID npcId, UUID playerUuid) {
        if (call == null) return null;
        NpcTool tool = registry.find(call.name());
        if (tool == null) return null;

        return ToolRequest.create(callerType, callerId, npcId, playerUuid,
                tool.name(), call.arguments());
    }

    /** 该名字是否属于统一注册表（规范名或 wire 名都算） */
    public boolean isRegisteredTool(String name) {
        return registry.find(name) != null;
    }

    /**
     * 对话模型可见的统一工具集：只给只读查询，不给行动。
     * <p>
     * Qwen 负责台词与意图理解，行动由旧工具集或行为模型执行；
     * 把 npc.move_to 之类同时暴露给对话模型会绕过"对话中站立不动"的既有约束。
     */
    public List<java.util.Map<String, Object>> dialogSchemas() {
        return registry.schemasFor(CallerType.DIALOG_MODEL, NpcTool.Category.QUERY);
    }

    public ToolRegistry registry() {
        return registry;
    }
}
