package com.howard.mcai.tool;

import com.google.gson.JsonObject;

/**
 * 持续行动的任务体。
 * <p>
 * 所有方法都由 {@link NpcTaskManager} 在主线程调用，实现类可以直接访问 Bukkit API。
 * 任务完成/失败/取消只能通过 {@link NpcTask} 的状态变更方法表达，不能自己从管理器里删除。
 */
public interface NpcTaskAction {

    /**
     * 任务第一次被接受时调用一次。可以在这里做前置检查，失败时直接 task.fail(...)。
     */
    void start(NpcTask task, ToolContext context, JsonObject arguments);

    /**
     * 每个心跳调用一次（当前为 1 秒）。实现应当快速返回，不要阻塞。
     */
    void tick(NpcTask task, ToolContext context, JsonObject arguments);

    /**
     * 任务被取消或超时后调用，用于清理速度、路径等运行时状态。
     */
    default void onCancel(NpcTask task) {
    }
}
