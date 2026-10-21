package com.howard.mcai.tool;

import com.howard.mcai.tool.ToolContext.CallerType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：注册、重复名称检查、查找和 schema 导出。
 * <p>
 * 注册只在插件启动时发生，因此使用同步的 LinkedHashMap 保证注册顺序稳定；
 * 工具执行阶段只做查找，不修改注册表。
 */
public final class ToolRegistry {

    private final Map<String, NpcTool> byCanonicalName = new LinkedHashMap<>();
    private final Map<String, NpcTool> byWireName = new LinkedHashMap<>();

    /**
     * 注册工具。规范名或 wire 名重复都会直接拒绝，避免模型调用到错误实现。
     *
     * @throws IllegalArgumentException 名称重复
     */
    public synchronized void register(NpcTool tool) {
        if (tool == null) throw new IllegalArgumentException("工具不能为空");

        NpcTool canonicalDup = byCanonicalName.get(tool.name());
        if (canonicalDup != null) {
            throw new IllegalArgumentException("工具名称重复: " + tool.name());
        }
        NpcTool wireDup = byWireName.get(tool.wireName());
        if (wireDup != null) {
            throw new IllegalArgumentException("工具 wire 名称重复: " + tool.wireName()
                    + "（规范名: " + tool.name() + "，已存在: " + wireDup.name() + "）");
        }
        // 别名同样要查重并索引（与 wire 名共用一张表，find 一次命中）
        for (String alias : tool.aliases()) {
            if (alias == null || alias.isBlank()) continue;
            if (byCanonicalName.containsKey(alias) || byWireName.containsKey(alias)) {
                throw new IllegalArgumentException("工具别名重复: " + alias
                        + "（规范名: " + tool.name() + "）");
            }
        }

        byCanonicalName.put(tool.name(), tool);
        byWireName.put(tool.wireName(), tool);
        for (String alias : tool.aliases()) {
            if (alias == null || alias.isBlank()) continue;
            byWireName.put(alias, tool);
        }
    }

    /**
     * 同时接受规范名和模型可见名，方便对话模型和内部调试共用一套查找逻辑。
     */
    public synchronized NpcTool find(String nameOrWireName) {
        if (nameOrWireName == null || nameOrWireName.isBlank()) return null;
        NpcTool tool = byCanonicalName.get(nameOrWireName);
        return tool != null ? tool : byWireName.get(nameOrWireName);
    }

    public synchronized List<NpcTool> all() {
        return new ArrayList<>(byCanonicalName.values());
    }

    public synchronized int size() {
        return byCanonicalName.size();
    }

    /**
     * 导出指定调用方可见的工具 schema，供模型 function calling 使用。
     * <p>
     * 只导出调用方有权限看到的工具，避免模型看到注定被拒绝的能力。
     */
    public synchronized List<Map<String, Object>> schemasFor(CallerType callerType) {
        return schemasFor(callerType, null);
    }

    /**
     * 按调用方 + 类别导出 schema。
     * <p>
     * 对话模型只暴露 QUERY 工具：它负责说话，不能通过统一入口去走路/改世界，
     * 避免和旧对话工具集的移动锁产生冲突。传 null = 不按类别过滤。
     */
    public synchronized List<Map<String, Object>> schemasFor(CallerType callerType, NpcTool.Category category) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (NpcTool tool : byCanonicalName.values()) {
            if (!tool.allowedCallers().contains(callerType)) continue;
            if (category != null && tool.category() != category) continue;

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.wireName());
            function.put("description", tool.description());
            function.put("parameters", tool.schema().toOpenAiParameters());

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "function");
            schema.put("function", function);
            schemas.add(schema);
        }
        return schemas;
    }
}
