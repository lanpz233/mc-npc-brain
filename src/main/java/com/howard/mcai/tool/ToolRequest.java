package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.ToolContext.CallerType;

import java.util.UUID;

/**
 * 统一工具请求。
 * <p>
 * 对话模型、行为模型、插件内部和未来的 HTTP/MCP 适配层全部转换成这个结构，
 * 再交给 {@link ToolDispatcher}。适配层不得直接调用工具实现。
 *
 * @param protocol   协议版本，当前固定为 {@link #PROTOCOL_VERSION}
 * @param requestId  请求 ID；为空时 Dispatcher 不做幂等缓存
 * @param callerType 调用方类型
 * @param callerId   调用方标识
 * @param npcId      目标 NPC UUID
 * @param playerUuid 互动玩家 UUID，可为 null
 * @param tool       工具规范名（如 {@code npc.status.get}）
 * @param arguments  参数对象，可为 null
 */
public record ToolRequest(
        String protocol,
        String requestId,
        CallerType callerType,
        String callerId,
        UUID npcId,
        UUID playerUuid,
        String tool,
        JsonObject arguments
) {

    public static final String PROTOCOL_VERSION = "npc-tool/v1";

    /**
     * 参数可能来自模型输出，统一在这里兜底为空对象，避免每个工具重复判空。
     */
    @Override
    public JsonObject arguments() {
        return arguments == null ? new JsonObject() : arguments;
    }

    /**
     * 创建一个标准请求并自动生成 request_id。
     */
    public static ToolRequest create(CallerType callerType,
                                     String callerId,
                                     UUID npcId,
                                     UUID playerUuid,
                                     String tool,
                                     JsonObject arguments) {
        return new ToolRequest(PROTOCOL_VERSION, UUID.randomUUID().toString(),
                callerType, callerId, npcId, playerUuid, tool, arguments);
    }

    public ToolContext toContext() {
        return new ToolContext(callerType, callerId, npcId, playerUuid, requestId);
    }
}
