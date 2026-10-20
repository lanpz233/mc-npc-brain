package com.howard.mcai.tool;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
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
    /**
     * 主线程判定。必须由注入方提供（插件里传 {@code Bukkit::isPrimaryThread}）：
     * 本类不能直接依赖 Bukkit，否则纯单元测试无法加载。
     */
    private final BooleanSupplier mainThreadCheck;
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
        // 旧构造器用于单元测试/非 Bukkit 场景：默认"永远算主线程"，保持原有行为
        this(registry, policy, tasks, mainThreadExecutor, warningSink, dedupeTtlMs, () -> true);
    }

    public ToolDispatcher(ToolRegistry registry,
                          ToolPolicyService policy,
                          NpcTaskManager tasks,
                          Consumer<Runnable> mainThreadExecutor,
                          Consumer<String> warningSink,
                          long dedupeTtlMs,
                          BooleanSupplier mainThreadCheck) {
        this.registry = registry;
        this.policy = policy;
        this.tasks = tasks;
        this.mainThreadExecutor = mainThreadExecutor;
        this.warningSink = warningSink == null ? message -> { } : warningSink;
        this.dedupeTtlMs = Math.max(1_000L, dedupeTtlMs);
        this.mainThreadCheck = mainThreadCheck == null ? () -> true : mainThreadCheck;
    }

    /**
     * 异步入口：把请求切到主线程后执行。适配层不应在异步线程直接访问 Bukkit。
     * <p>
     * 没有主线程执行器时返回失败 future，而不是"就地同步执行"：
     * 异步线程里直接跑 Bukkit API 会抛 IllegalStateException 或更糟地悄悄破坏世界状态，
     * 静默退化会把"配置漏了执行器"这种部署错误掩盖到运行时才爆。
     */
    public CompletableFuture<ToolResult> dispatchAsync(ToolRequest request) {
        if (mainThreadExecutor == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "ToolDispatcher 未配置主线程执行器，无法异步调用工具: "
                            + (request == null ? "<null>" : request.tool())));
        }
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
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
        // 兜底闸门：调用方漏切主线程时这里必须拒绝，而不是把 Bukkit 调用带到异步线程。
        // 返回 REJECTED 而不是抛异常，避免异常在 CompletableFuture 链里被吞掉后无人排查。
        if (!mainThreadCheck.getAsBoolean()) {
            String message = "工具调用必须在 Paper 主线程执行，已拒绝: "
                    + (request == null ? "<null>" : request.tool());
            warningSink.accept(message);
            return ToolResult.rejected(ToolResult.ErrorCode.INTERNAL_ERROR, message);
        }

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
