package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.ToolContext.CallerType;

import java.util.EnumSet;
import java.util.Set;

/**
 * 所有 AI 可调用能力的统一接口。
 * <p>
 * 新增工具只需要实现这个接口并注册到 {@link ToolRegistry}，
 * 不需要再修改 CentralPlanner / NeedsLayer 的调用入口。
 */
public interface NpcTool {

    enum Category {
        /** 只读查询，可以直接返回结果。 */
        QUERY,
        /** 会产生副作用或持续执行的动作。 */
        ACTION
    }

    enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * 规范名，供内部注册、协议与调试命令使用，例如 {@code npc.status.get}。
     */
    String name();

    /**
     * 模型可见名。部分 OpenAI 兼容后端不接受点号，因此默认把点号替换成下划线。
     */
    default String wireName() {
        return name().replace('.', '_');
    }

    /**
     * 额外的可识别名（别名）——注册表会一并索引，{@code find} 用任一名都能命中。
     * <p>
     * 用途：老工具集（BehaviorRegistry）的动作名（如 {@code give_item}）与统一柜的
     * 规范名（如 {@code npc.give_item}）不一致时，迁移期用别名桥接：对话模型继续用
     * 旧名调用，执行已经走统一分发。别名只做查找，不导出 schema、不改变模型菜单。
     */
    default Set<String> aliases() {
        return Set.of();
    }

    String description();

    Category category();

    Risk risk();

    ToolSchema schema();

    /**
     * 默认允许所有模型与插件内部调用；外部 HTTP/MCP 需要工具显式声明后再加入白名单。
     */
    default Set<CallerType> allowedCallers() {
        return EnumSet.of(
                CallerType.DIALOG_MODEL,
                CallerType.BEHAVIOR_MODEL,
                CallerType.INTERNAL,
                CallerType.PLUGIN,
                CallerType.CONSOLE
        );
    }

    /**
     * 是否必须带玩家上下文。为 true 时没有 player_uuid 会被策略层拒绝。
     */
    default boolean requiresPlayer() {
        return false;
    }

    /**
     * 对话状态下是否允许调用。移动类工具应返回 false，保证 NPC 对话时站立不动。
     */
    default boolean allowedWhileConversing() {
        return true;
    }

    /**
     * 持续行动的任务体；返回 null 表示即时工具（查询或一次性动作）。
     * 返回非 null 时，Dispatcher 会创建任务并返回 ACCEPTED + task_id。
     */
    default NpcTaskAction taskAction() {
        return null;
    }

    /**
     * 持续行动默认超时时间。
     */
    default long defaultTimeoutMs() {
        return 30_000L;
    }

    /**
     * 即时工具的执行入口。持续行动工具也会实现该方法，但正常调用路径不会走这里。
     * <p>
     * 该方法只在主线程被 Dispatcher 调用，可以直接访问 Bukkit API。
     */
    ToolResult execute(ToolContext context, JsonObject arguments);
}
