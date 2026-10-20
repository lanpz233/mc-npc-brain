package com.howard.mcai.tool.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.howard.mcai.tool.NpcTask;
import com.howard.mcai.tool.NpcTaskManager;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;

import java.util.List;

/**
 * npc.task.get —— 查询持续行动的进度/结果。
 * <p>
 * 不传 task_id 时返回该 NPC 当前及最近完成的任务列表；传了则只查指定任务。
 * 任务不存在或不属于该 NPC 时返回 TASK_NOT_FOUND，不伪造状态。
 */
public final class NpcTaskGetTool implements NpcTool {

    private final NpcTaskManager tasks;

    public NpcTaskGetTool(NpcTaskManager tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "npc.task.get";
    }

    @Override
    public String description() {
        return "查询 NPC 的持续行动任务：状态、开始/结束时间、错误和结果。省略 task_id 时返回该 NPC 的任务列表。";
    }

    @Override
    public Category category() {
        return Category.QUERY;
    }

    @Override
    public Risk risk() {
        return Risk.LOW;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("task_id", ToolSchema.Param.string("任务 ID；省略时返回该 NPC 的任务列表").maxLength(64))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String taskId = ToolArgs.string(arguments, "task_id", null);
        if (taskId != null && !taskId.isBlank()) {
            NpcTask task = tasks.get(taskId);
            if (task == null || !task.npcId().equals(context.npcId())) {
                return ToolResult.failed(ToolResult.ErrorCode.TASK_NOT_FOUND,
                        "任务不存在或不属于该 NPC: " + taskId);
            }
            return ToolResult.completed(task.toJson());
        }

        List<NpcTask> list = tasks.listFor(context.npcId());
        JsonArray array = new JsonArray();
        for (NpcTask task : list) {
            array.add(task.toJson());
        }
        NpcTask active = tasks.activeFor(context.npcId());

        JsonObject data = new JsonObject();
        data.addProperty("npc_id", context.npcId().toString());
        data.add("active_task_id", active == null
                ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(active.id()));
        data.addProperty("count", array.size());
        data.add("tasks", array);
        return ToolResult.completed(data);
    }
}
