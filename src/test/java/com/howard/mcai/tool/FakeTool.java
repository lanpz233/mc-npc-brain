package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.ToolContext.CallerType;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单元测试用的最小工具替身，不依赖 Bukkit。
 */
class FakeTool implements NpcTool {

    private final String name;
    private final ToolSchema schema;
    private final Set<CallerType> callers;
    private final NpcTaskAction action;
    private final long timeoutMs;

    final AtomicInteger executions = new AtomicInteger();
    volatile ToolResult fixedResult;

    FakeTool(String name, ToolSchema schema, Set<CallerType> callers,
             NpcTaskAction action, long timeoutMs) {
        this.name = name;
        this.schema = schema;
        this.callers = callers;
        this.action = action;
        this.timeoutMs = timeoutMs;
    }

    static FakeTool query(String name) {
        return new FakeTool(name, ToolSchema.empty(), null, null, 30_000L);
    }

    static FakeTool withSchema(String name, ToolSchema schema) {
        return new FakeTool(name, schema, null, null, 30_000L);
    }

    static FakeTool restricted(String name, CallerType first, CallerType... rest) {
        return new FakeTool(name, ToolSchema.empty(), EnumSet.of(first, rest), null, 30_000L);
    }

    static FakeTool sustained(String name, NpcTaskAction action, long timeoutMs) {
        return new FakeTool(name, ToolSchema.empty(), null, action, timeoutMs);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "测试工具 " + name;
    }

    @Override
    public Category category() {
        return action == null ? Category.QUERY : Category.ACTION;
    }

    @Override
    public Risk risk() {
        return Risk.LOW;
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public Set<CallerType> allowedCallers() {
        return callers == null ? NpcTool.super.allowedCallers() : callers;
    }

    @Override
    public NpcTaskAction taskAction() {
        return action;
    }

    @Override
    public long defaultTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        executions.incrementAndGet();
        if (fixedResult != null) return fixedResult;
        JsonObject data = new JsonObject();
        data.addProperty("tool", name);
        data.addProperty("executions", executions.get());
        return ToolResult.completed(data);
    }
}
