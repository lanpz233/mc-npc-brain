package com.howard.mcai.tool;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 统一工具分发入口。
 * <p>
 * 所有调用方（对话模型、行为模型、插件命令、未来的 HTTP/MCP）都必须经过这里：
 * 协议校验 → 工具查找 → 参数校验 → 权限策略 → 幂等检查 → 执行或创建任务。
 * <p>
 * Bukkit 状态访问要求主线程：插件内部直接调用 {@link #dispatchSync}；
 * 异步适配层使用 {@link #dispatchAsync}，由构造时注入的主线程执行器切回主线程。
 */
public final class ToolDispatcher {

    private static final long DEFAULT_DEDUPE_TTL_MS = 5 * 60_000L;

    private record CachedResult(ToolResult result, long atMs) {
    }

    private final ToolRegistry registry;
    private final ToolPolicyService policy;
    private final NpcTaskManager tasks;
    private final Consumer<Runnable> mainThreadExecutor;
    private final Consumer<String> warningSink;
    private final long dedupeTtlMs;
    private final Map<String, CachedResult> recentRequests = new ConcurrentHashMap<>();

    public ToolDispatcher(ToolRegistry registry,
                          ToolPolicyService policy,
                          NpcTaskManager tasks,
                          Consumer<Runnable> mainThreadExecutor) {
        this(registry, policy, tasks, mainThreadExecutor, message -> { }, DEFAULT_DEDUPE_TTL_MS);
    }

    public ToolDispatcher(ToolRegistry registry,
                          ToolPolicyService policy,
                          NpcTaskManager tasks,
                          Consumer<Runnable> mainThreadExecutor,
                          Consumer<String> warningSink,
                          long dedupeTtlMs) {
        this.registry = registry;
        this.policy = policy;
        this.tasks = tasks;
        this.mainThreadExecutor = mainThreadExecutor;
        this.warningSink = warningSink == null ? message -> { } : warningSink;
        this.dedupeTtlMs = Math.max(1_000L, dedupeTtlMs);
    }

    /**
     * 异步入口：把请求切到主线程后执行。适配层不应在异步线程直接访问 Bukkit。
     */
    public CompletableFuture<ToolResult> dispatchAsync(ToolRequest request) {
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        if (mainThreadExecutor == null) {
            try {
                future.complete(dispatchSync(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        }
        mainThreadExecutor.accept(() -> {
            try {
                future.complete(dispatchSync(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * 同步入口，必须在主线程调用。
     */
    public ToolResult dispatchSync(ToolRequest request) {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        if (request == null) {
            return ToolResult.rejected(ToolResult.ErrorCode.INVALID_REQUEST, "请求为空");
        }
        String requestId = request.requestId();
        if (request.protocol() != null
                && !ToolRequest.PROTOCOL_VERSION.equals(request.protocol())) {
            return ToolResult.rejected(ToolResult.ErrorCode.INVALID_REQUEST,
                    "不支持的协议版本: " + request.protocol()).withRequestId(requestId);
        }

        NpcTool tool = registry.find(request.tool());
        if (tool == null) {
            return ToolResult.rejected(ToolResult.ErrorCode.UNKNOWN_TOOL,
                    "未知工具: " + request.tool()).withRequestId(requestId);
        }

        String dedupeKey = dedupeKey(request, tool);
        if (dedupeKey != null) {
            CachedResult cached = recentRequests.get(dedupeKey);
            if (cached != null && now - cached.atMs() <= dedupeTtlMs) {
                return ToolResult.duplicate(cached.result()).withRequestId(requestId);
            }
        }

        List<String> argumentErrors = tool.schema().validate(request.arguments());
        if (!argumentErrors.isEmpty()) {
            return ToolResult.rejected(ToolResult.ErrorCode.INVALID_ARGUMENT,
                    String.join("; ", argumentErrors)).withRequestId(requestId);
        }

        ToolPolicyService.Decision decision = policy.check(request, tool);
        if (!decision.allowed()) {
            return ToolResult.rejected(decision.code(), decision.message()).withRequestId(requestId);
        }

        ToolResult result;
        NpcTaskAction taskAction = tool.taskAction();
        if (taskAction != null) {
            result = startActionTask(tool, request);
        } else {
            result = executeImmediately(tool, request);
        }

        result = result.withRequestId(requestId);
        if (dedupeKey != null) {
            recentRequests.put(dedupeKey, new CachedResult(result, now));
        }
        return result;
    }

    private ToolResult startActionTask(NpcTool tool, ToolRequest request) {
        NpcTask task = tasks.createAndStart(tool, request.toContext(), request.arguments());
        if (task == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_BUSY,
                    "该 NPC 已有进行中的行动任务");
        }
        return switch (task.state()) {
            case COMPLETED -> ToolResult.completed(task.data());
            case FAILED -> ToolResult.failed(
                    task.errorCode() != null ? task.errorCode() : ToolResult.ErrorCode.INTERNAL_ERROR,
                    task.errorMessage());
            case CANCELLED -> ToolResult.cancelled(task.id(), task.errorMessage());
            case TIMEOUT -> ToolResult.timeout(task.id(), task.errorMessage());
            default -> ToolResult.accepted(task.id(), taskSummary(tool, task));
        };
    }

    private ToolResult executeImmediately(NpcTool tool, ToolRequest request) {
        try {
            ToolResult result = tool.execute(request.toContext(), request.arguments());
            if (result == null) {
                return ToolResult.failed(ToolResult.ErrorCode.INTERNAL_ERROR,
                        "工具 " + tool.name() + " 未返回结果");
            }
            return result;
        } catch (Throwable t) {
            warningSink.accept("工具执行异常 " + tool.name() + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return ToolResult.failed(ToolResult.ErrorCode.INTERNAL_ERROR,
                    "工具执行异常: " + t.getClass().getSimpleName());
        }
    }

    private static JsonObject taskSummary(NpcTool tool, NpcTask task) {
        JsonObject data = new JsonObject();
        data.addProperty("tool", tool.name());
        data.addProperty("task_id", task.id());
        data.addProperty("state", task.state().name());
        return data;
    }

    private String dedupeKey(ToolRequest request, NpcTool tool) {
        String requestId = request.requestId();
        if (requestId == null || requestId.isBlank() || request.npcId() == null) {
            return null;
        }
        return request.callerType() + "|"
                + (request.callerId() == null ? "" : request.callerId()) + "|"
                + request.npcId() + "|"
                + tool.name() + "|"
                + requestId;
    }

    private void purgeExpired(long now) {
        recentRequests.entrySet().removeIf(entry -> now - entry.getValue().atMs() > dedupeTtlMs);
    }
}
