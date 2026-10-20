package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.NpcTask;
import com.howard.mcai.tool.NpcTaskManager;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

/**
 * npc.task.cancel —— 取消持续行动。
 * <p>
 * 传 task_id 取消指定任务；省略时取消该 NPC 当前所有行动任务。
 * 任务已结束时返回 completed + cancelled=false，而不是伪造成功取消。
 */
public final class NpcTaskCancelTool implements NpcTool {

    private final NpcTaskManager tasks;

    public NpcTaskCancelTool(NpcTaskManager tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "npc.task.cancel";
    }

    @Override
    public String description() {
        return "取消 NPC 的持续行动任务。省略 task_id 时取消该 NPC 当前所有进行中的任务。";
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
        return ToolSchema.builder()
                .param("task_id", ToolSchema.Param.string("要取消的任务 ID；省略时取消该 NPC 的全部进行中任务").maxLength(64))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String taskId = ToolArgs.string(arguments, "task_id", null);
        JsonObject data = new JsonObject();

        if (taskId != null && !taskId.isBlank()) {
            NpcTask task = tasks.get(taskId);
            if (task == null || !task.npcId().equals(context.npcId())) {
                return ToolResult.failed(ToolResult.ErrorCode.TASK_NOT_FOUND,
                        "任务不存在或不属于该 NPC: " + taskId);
            }
            if (task.isTerminal()) {
                data.addProperty("task_id", task.id());
                data.addProperty("cancelled", false);
                data.addProperty("state", task.state().name());
                return ToolResult.completed(data);
            }
            boolean cancelled = tasks.cancel(task.id(), "通过 npc.task.cancel 取消");
            data.addProperty("task_id", task.id());
            data.addProperty("cancelled", cancelled);
            data.addProperty("state", tasks.get(task.id()) == null
                    ? "CANCELLED" : tasks.get(task.id()).state().name());
            return ToolResult.completed(data);
        }

        int count = tasks.cancelForNpc(context.npcId(), "通过 npc.task.cancel 批量取消");
        data.addProperty("npc_id", context.npcId().toString());
        data.addProperty("cancelled", count > 0);
        data.addProperty("cancelled_count", count);
        return ToolResult.completed(data);
    }
}
