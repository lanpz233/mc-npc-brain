package com.howard.mcai.tool;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 一个持续行动任务的运行时状态。
 * <p>
 * 状态变更全部在 {@link NpcTaskManager} 的调用路径上发生（主线程），
 * 方法本身加 synchronized，避免未来 HTTP 查询从异步线程读到时看到半更新状态。
 */
public final class NpcTask {

    private final String id;
    private final UUID npcId;
    private final String toolName;
    private final ToolContext context;
    private final JsonObject arguments;
    private final NpcTaskAction action;
    private final long timeoutMs;
    private final long createdAtMs;

    private ToolResult.Status state = ToolResult.Status.ACCEPTED;
    private long startedAtMs;
    private long finishedAtMs;
    private ToolResult.ErrorCode errorCode;
    private String errorMessage;
    private JsonObject data;

    NpcTask(String id,
            UUID npcId,
            String toolName,
            ToolContext context,
            JsonObject arguments,
            NpcTaskAction action,
            long timeoutMs,
            long createdAtMs) {
        this.id = id;
        this.npcId = npcId;
        this.toolName = toolName;
        this.context = context;
        this.arguments = arguments == null ? new JsonObject() : arguments;
        this.action = action;
        this.timeoutMs = timeoutMs;
        this.createdAtMs = createdAtMs;
    }

    public String id() { return id; }
    public UUID npcId() { return npcId; }
    public String toolName() { return toolName; }
    public ToolContext context() { return context; }
    public JsonObject arguments() { return arguments; }
    public NpcTaskAction action() { return action; }
    public long timeoutMs() { return timeoutMs; }
    public long createdAtMs() { return createdAtMs; }

    public synchronized ToolResult.Status state() { return state; }
    public synchronized long startedAtMs() { return startedAtMs; }
    public synchronized long finishedAtMs() { return finishedAtMs; }
    public synchronized ToolResult.ErrorCode errorCode() { return errorCode; }
    public synchronized String errorMessage() { return errorMessage; }
    public synchronized JsonObject data() { return data; }

    public synchronized boolean isActive() {
        return state == ToolResult.Status.ACCEPTED || state == ToolResult.Status.RUNNING;
    }

    public synchronized boolean isTerminal() {
        return !isActive();
    }

    synchronized void markRunning(long nowMs) {
        if (!isActive()) return;
        state = ToolResult.Status.RUNNING;
        startedAtMs = nowMs;
    }

    public synchronized void complete(long nowMs, JsonObject resultData) {
        if (!isActive()) return;
        state = ToolResult.Status.COMPLETED;
        finishedAtMs = nowMs;
        data = resultData;
        errorCode = null;
        errorMessage = null;
    }

    public synchronized void fail(long nowMs, ToolResult.ErrorCode code, String message) {
        if (!isActive()) return;
        state = ToolResult.Status.FAILED;
        finishedAtMs = nowMs;
        errorCode = code;
        errorMessage = message;
    }

    public synchronized void cancel(long nowMs, String reason) {
        if (!isActive()) return;
        state = ToolResult.Status.CANCELLED;
        finishedAtMs = nowMs;
        errorCode = ToolResult.ErrorCode.TASK_CANCELLED;
        errorMessage = reason;
    }

    public synchronized void timeout(long nowMs) {
        if (!isActive()) return;
        state = ToolResult.Status.TIMEOUT;
        finishedAtMs = nowMs;
        errorCode = ToolResult.ErrorCode.TASK_TIMEOUT;
        errorMessage = "任务超时（" + timeoutMs + "ms）";
    }

    /**
     * 返回给 npc.task.get / 调试命令的状态快照。
     */
    public synchronized JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("task_id", id);
        json.addProperty("npc_id", npcId.toString());
        json.addProperty("tool", toolName);
        json.addProperty("state", state.name());
        json.addProperty("created_at", createdAtMs);
        json.add("started_at", startedAtMs > 0
                ? new com.google.gson.JsonPrimitive(startedAtMs) : JsonNull.INSTANCE);
        json.add("finished_at", finishedAtMs > 0
                ? new com.google.gson.JsonPrimitive(finishedAtMs) : JsonNull.INSTANCE);
        json.addProperty("timeout_ms", timeoutMs);
        json.add("error_code", errorCode == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(errorCode.code()));
        json.add("error_message", errorMessage == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(errorMessage));
        json.add("data", data == null ? JsonNull.INSTANCE : data);
        return json;
    }
}
