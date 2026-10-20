package com.howard.mcai.tool;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 持续行动任务的生命周期管理。
 * <p>
 * 职责：创建、冲突检查、每秒 tick、超时、取消、关服清理，以及保留最近完成的任务供查询。
 * 所有 tick 都必须由主线程调用（CentralPlanner 心跳）。
 */
public final class NpcTaskManager {

    private static final long DEFAULT_RETENTION_MS = 5 * 60_000L;
    private static final long MIN_RETENTION_MS = 30_000L;

    private final Map<String, NpcTask> tasks = new ConcurrentHashMap<>();
    private final long retentionMs;
    /** 已上报终止状态的任务 ID，避免 tick 每轮重复打日志 */
    private final Set<String> reportedTerminal = ConcurrentHashMap.newKeySet();
    /** 生命周期日志出口（插件 Logger）；测试传 null 时不输出 */
    private final Consumer<String> logSink;

    public NpcTaskManager() {
        this(DEFAULT_RETENTION_MS, null);
    }

    public NpcTaskManager(long retentionMs) {
        this(retentionMs, null);
    }

    /** 插件用：任务创建/完成/失败/取消/超时全部打到控制台 */
    public NpcTaskManager(Consumer<String> logSink) {
        this(DEFAULT_RETENTION_MS, logSink);
    }

    public NpcTaskManager(long retentionMs, Consumer<String> logSink) {
        this.retentionMs = Math.max(MIN_RETENTION_MS, retentionMs);
        this.logSink = logSink;
    }

    /**
     * 创建并启动任务。
     * <p>
     * 同一 NPC 同时只允许一个行动任务，避免"一边走去 A 一边走去 B"这类互相覆盖的状态。
     *
     * @return 新任务；NPC 已有进行中的任务时返回 null（调用方应返回 NPC_BUSY）
     */
    public synchronized NpcTask createAndStart(NpcTool tool, ToolContext context, JsonObject arguments) {
        if (tool == null || context == null || context.npcId() == null || tool.taskAction() == null) {
            return null;
        }
        UUID npcId = context.npcId();
        for (NpcTask existing : tasks.values()) {
            if (existing.npcId().equals(npcId) && existing.isActive()) {
                log("[task] 拒绝创建 npc=" + shortId(npcId) + " tool=" + tool.name()
                        + "：已有进行中的任务 " + existing.id() + "(" + existing.toolName() + ")");
                return null;
            }
        }

        long now = System.currentTimeMillis();
        NpcTask task = new NpcTask(
                UUID.randomUUID().toString(),
                npcId,
                tool.name(),
                context,
                arguments,
                tool.taskAction(),
                tool.defaultTimeoutMs(),
                now
        );
        tasks.put(task.id(), task);
        task.markRunning(now);

        log("[task] 创建 " + task.id() + " npc=" + shortId(npcId) + " tool=" + tool.name()
                + " args=" + trim(arguments == null ? "{}" : arguments.toString()));

        try {
            task.action().start(task, context, task.arguments());
        } catch (Throwable t) {
            task.fail(now, ToolResult.ErrorCode.INTERNAL_ERROR,
                    "任务启动异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        // 有的工具在 start 里就直接判定完成/失败，这里补一条终止日志，避免任务"只创建不结束"
        if (task.isTerminal()) reportTerminal(task);
        return task;
    }

    public NpcTask get(String taskId) {
        return taskId == null ? null : tasks.get(taskId);
    }

    /**
     * 返回该 NPC 当前进行中的任务；没有则返回 null。
     */
    public NpcTask activeFor(UUID npcId) {
        if (npcId == null) return null;
        for (NpcTask task : tasks.values()) {
            if (task.npcId().equals(npcId) && task.isActive()) return task;
        }
        return null;
    }

    /**
     * 返回该 NPC 仍在保留期内的任务（进行中 + 最近完成），按创建时间倒序。
     */
    public List<NpcTask> listFor(UUID npcId) {
        List<NpcTask> result = new ArrayList<>();
        if (npcId == null) return result;
        for (NpcTask task : tasks.values()) {
            if (task.npcId().equals(npcId)) result.add(task);
        }
        result.sort((a, b) -> Long.compare(b.createdAtMs(), a.createdAtMs()));
        return result;
    }

    /**
     * 取消指定任务。任务不存在或已结束时返回 false。
     */
    public synchronized boolean cancel(String taskId, String reason) {
        NpcTask task = tasks.get(taskId);
        if (task == null || !task.isActive()) return false;

        long now = System.currentTimeMillis();
        task.cancel(now, reason);
        notifyCancel(task);
        reportTerminal(task);
        return true;
    }

    /**
     * 取消该 NPC 的全部进行中任务，返回取消数量。
     */
    public synchronized int cancelForNpc(UUID npcId, String reason) {
        if (npcId == null) return 0;
        int count = 0;
        long now = System.currentTimeMillis();
        for (NpcTask task : tasks.values()) {
            if (!task.npcId().equals(npcId) || !task.isActive()) continue;
            task.cancel(now, reason);
            notifyCancel(task);
            reportTerminal(task);
            count++;
        }
        return count;
    }

    /**
     * 主线程心跳：推进任务、处理超时、清理过期结果。
     */
    public void tick() {
        tick(System.currentTimeMillis());
    }

    /**
     * 可注入时间的心跳，便于单元测试覆盖超时逻辑。
     */
    public synchronized void tick(long nowMs) {
        for (NpcTask task : tasks.values()) {
            if (task.isActive()) {
                long startedAt = task.startedAtMs();
                if (startedAt > 0 && nowMs - startedAt >= task.timeoutMs()) {
                    task.timeout(nowMs);
                    notifyCancel(task);
                    reportTerminal(task);
                    continue;
                }
                try {
                    task.action().tick(task, task.context(), task.arguments());
                } catch (Throwable t) {
                    task.fail(nowMs, ToolResult.ErrorCode.INTERNAL_ERROR,
                            "任务 tick 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                // 任务可能在 tick 里完成/失败（到达、卡住判定），这里统一上报一次
                if (task.isTerminal()) reportTerminal(task);
            }

            if (task.isTerminal() && task.finishedAtMs() > 0
                    && nowMs - task.finishedAtMs() > retentionMs) {
                tasks.remove(task.id(), task);
                reportedTerminal.remove(task.id());
            }
        }
    }

    /**
     * 插件关闭时取消所有进行中的任务，避免残留速度/状态。
     */
    public synchronized void shutdown() {
        long now = System.currentTimeMillis();
        for (NpcTask task : tasks.values()) {
            if (!task.isActive()) continue;
            task.cancel(now, "插件关闭");
            notifyCancel(task);
            reportTerminal(task);
        }
        tasks.clear();
        reportedTerminal.clear();
    }

    /** 任务终止日志：状态 / 工具 / 时长 / 错误 / 最终数据，一行一条便于控制台排查 */
    private void reportTerminal(NpcTask task) {
        if (task == null || !reportedTerminal.add(task.id())) return;
        StringBuilder sb = new StringBuilder("[task] ")
                .append(task.state()).append(' ')
                .append(task.toolName())
                .append(" id=").append(task.id())
                .append(" npc=").append(shortId(task.npcId()));
        if (task.startedAtMs() > 0 && task.finishedAtMs() > 0) {
            sb.append(" 用时=").append(task.finishedAtMs() - task.startedAtMs()).append("ms");
        }
        if (task.errorCode() != null) {
            sb.append(" err=").append(task.errorCode()).append(':').append(task.errorMessage());
        }
        if (task.data() != null) sb.append(" data=").append(trim(task.data().toString()));
        log(sb.toString());
    }

    private static String trim(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }

    private static String shortId(UUID id) {
        if (id == null) return "-";
        String s = id.toString();
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private void log(String message) {
        if (logSink != null) logSink.accept(message);
    }

    private static void notifyCancel(NpcTask task) {
        if (task.action() == null) return;
        try {
            task.action().onCancel(task);
        } catch (Throwable ignored) {
            // 清理失败不应影响关服或其他任务
        }
    }
}
