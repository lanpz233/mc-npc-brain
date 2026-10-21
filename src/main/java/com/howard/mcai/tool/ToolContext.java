package com.howard.mcai.tool;

import java.util.UUID;

/**
 * 一次工具调用的可信上下文。
 * <p>
 * 只能由 {@link ToolDispatcher} 根据服务端已认证的请求构造，
 * 模型输出中的任何字段都不能直接变成这里的 callerType —— 否则等于让模型自己声明权限。
 *
 * @param callerType 调用方类型（对话模型 / 插件自主决策 / 插件内部 / 控制台 / 外部协议）
 * @param callerId   调用方标识（模型名、插件名、外部客户端 ID 等），用于幂等与审计
 * @param npcId      目标 NPC 的实体 UUID
 * @param playerUuid 互动玩家 UUID；没有玩家上下文时为 null
 * @param requestId  本次请求 ID，用于去重
 */
public record ToolContext(
        CallerType callerType,
        String callerId,
        UUID npcId,
        UUID playerUuid,
        String requestId
) {

    /**
     * 调用方类型。
     * <p>
     * 外部协议（HTTP/MCP）在适配层完成认证后，也必须映射到这里的类型再进入 Dispatcher，
     * 不允许绕过策略服务直接执行工具。
     */
    public enum CallerType {
        DIALOG_MODEL,
        /** 插件自主决策（需求驱动）—— v0.1.4 起取代旧的行为模型调用方 */
        AUTONOMY,
        INTERNAL,
        PLUGIN,
        CONSOLE,
        HTTP,
        MCP;

        public boolean fromModel() {
            return this == DIALOG_MODEL;
        }
    }
}
