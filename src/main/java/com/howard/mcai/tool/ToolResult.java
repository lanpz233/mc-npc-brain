package com.howard.mcai.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * 统一工具响应。
 * <p>
 * 语义约定：
 * <ul>
 *   <li>{@code success} 只表示"请求被正常处理且没有协议/权限错误"；</li>
 *   <li>{@code status} 才是最终状态，持续行动可能是 ACCEPTED/RUNNING，之后通过
 *       {@code npc.task.get} 查询最终结果。</li>
 * </ul>
 * 这样调用方能明确区分"请求接受成功"和"行动最终成功"。
 */
public record ToolResult(
        boolean success,
        Status status,
        String requestId,
        String taskId,
        String errorCode,
        String errorMessage,
        JsonObject data
) {

    public enum Status {
        /** 持续行动已被接受，尚未开始或正在排队。 */
        ACCEPTED,
        /** 持续行动正在执行。 */
        RUNNING,
        /** 查询已返回，或行动已完成。 */
        COMPLETED,
        /** 行动或查询最终失败。 */
        FAILED,
        /** 请求被策略或参数校验拒绝，未执行。 */
        REJECTED,
        /** 任务被取消。 */
        CANCELLED,
        /** 任务超时。 */
        TIMEOUT,
        /** 相同 request_id 的重复请求，未再次执行。 */
        DUPLICATE
    }

    public enum ErrorCode {
        UNKNOWN_TOOL,
        INVALID_REQUEST,
        INVALID_ARGUMENT,
        NPC_NOT_FOUND,
        CALLER_NOT_AUTHORIZED,
        TOOL_NOT_ALLOWED,
        NPC_BUSY,
        TASK_NOT_FOUND,
        TASK_CANCELLED,
        TASK_TIMEOUT,
        DUPLICATE_REQUEST,
        INTERNAL_ERROR;

        public String code() {
            return name();
        }
    }

    public static ToolResult completed(JsonObject data) {
        return new ToolResult(true, Status.COMPLETED, null, null, null, null, data);
    }

    public static ToolResult accepted(String taskId, JsonObject data) {
        return new ToolResult(true, Status.ACCEPTED, null, taskId, null, null, data);
    }

    public static ToolResult running(String taskId, JsonObject data) {
        return new ToolResult(true, Status.RUNNING, null, taskId, null, null, data);
    }

    public static ToolResult failed(ErrorCode code, String message) {
        return new ToolResult(false, Status.FAILED, null, null, code.code(), message, null);
    }

    public static ToolResult rejected(ErrorCode code, String message) {
        return new ToolResult(false, Status.REJECTED, null, null, code.code(), message, null);
    }

    public static ToolResult cancelled(String taskId, String message) {
        return new ToolResult(false, Status.CANCELLED, null, taskId,
                ErrorCode.TASK_CANCELLED.code(), message, null);
    }

    public static ToolResult timeout(String taskId, String message) {
        return new ToolResult(false, Status.TIMEOUT, null, taskId,
                ErrorCode.TASK_TIMEOUT.code(), message, null);
    }

    /**
     * 重复请求：直接返回首次执行的结果，但状态标记为 DUPLICATE，避免调用方误以为又执行了一次。
     */
    public static ToolResult duplicate(ToolResult original) {
        return new ToolResult(true, Status.DUPLICATE, original.requestId, original.taskId,
                original.errorCode, original.errorMessage, original.data);
    }

    public ToolResult withRequestId(String requestId) {
        if (this.requestId != null && this.requestId.equals(requestId)) return this;
        return new ToolResult(success, status, requestId, taskId, errorCode, errorMessage, data);
    }

    /**
     * 供未来的 HTTP/MCP 适配层直接序列化使用。
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", ToolRequest.PROTOCOL_VERSION);
        addNullable(json, "request_id", requestId);
        json.addProperty("success", success);
        json.addProperty("status", status.name());
        addNullable(json, "task_id", taskId);
        addNullable(json, "error_code", errorCode);
        addNullable(json, "error_message", errorMessage);
        json.add("data", data == null ? JsonNull.INSTANCE : data);
        return json;
    }

    private static void addNullable(JsonObject json, String key, String value) {
        JsonElement element = value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);
        json.add(key, element);
    }
}
