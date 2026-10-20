package com.howard.mcai.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.ToolRegistry;
import com.howard.mcai.tool.ToolRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行为模型输出解析器 —— 把 MiniCPM 的"自由文本"收敛成可校验的工具请求。
 * <p>
 * 为什么单独一层：实测 MiniCPM-1B 不返回原生 tool_calls；即使明确要求只输出 JSON，
 * 也会吐出 {@code {"action":"npc_move_to {x:100,y:64,z:100}"}} 这类非法 JSON（字符串未闭合）。
 * 直接塞给 Gson 会解析失败，整条自主行动链路就静默断了。所以这里做三件事：
 * <ol>
 *   <li>提取第一个完整 JSON 对象（括号栈 + 字符串感知，避免截断）</li>
 *   <li>对已知坏格式做定点修复（action 里混入 "{x:...,y:...}" 的裸参数）</li>
 *   <li>工具名归一 + schema 校验；失败时返回可读原因，供上层追问一次</li>
 * </ol>
 * 解析器不访问 Bukkit，可纯单元测试。
 */
public final class BehaviorDecisionParser {

    /** 模型常用来表示"什么都不做"的词 —— 没有对应工具，映射成 idle 决策 */
    private static final Set<String> IDLE_WORDS = Set.of(
            "idle", "none", "nothing", "stop", "wait", "noop", "skip");

    /** 坏格式：action 值里混进了裸参数对象，且没有闭合引号，如 "npc_move_to {x:1,y:2}" */
    private static final Pattern ACTION_WITH_INLINE_ARGS = Pattern.compile(
            "\"action\"\\s*:\\s*\"\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\{([^}]*)\\}");

    private final LlmToolAdapter adapter;
    private final ToolRegistry registry;

    public BehaviorDecisionParser(ToolRegistry registry) {
        this.registry = registry;
        this.adapter = new LlmToolAdapter(registry);
    }

    /**
     * 解析结果。
     *
     * @param request 可执行的统一请求；idle 或失败时为 null
     * @param error   失败原因（给模型重试时的反馈）；成功/idle 时为 null
     */
    public record Outcome(ToolRequest request, String error) {
        /** 合法但决定什么都不做 */
        public boolean idle() {
            return request == null && error == null;
        }

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * 解析模型原始输出。
     *
     * @param raw 模型 content（可能带 markdown 代码围栏 / think 块 / 前后废话）
     */
    public Outcome parse(String raw, CallerType callerType, String callerId,
                         UUID npcId, UUID playerUuid) {
        String text = normalizeText(raw);
        if (text.isBlank()) {
            return new Outcome(null, "输出为空，必须返回一个 JSON 对象");
        }

        String json = extractFirstObject(text);
        JsonObject obj = tryParse(json);
        if (obj == null) {
            // 定点修复已知坏格式后重试一次
            String repaired = repairInlineActionArgs(text);
            if (!repaired.equals(text)) {
                obj = tryParse(extractFirstObject(repaired));
            }
        }
        if (obj == null) {
            return new Outcome(null, "不是合法 JSON: " + abbreviate(text));
        }

        String action = firstString(obj, "action", "tool", "name", "action_id");
        if (action == null || action.isBlank()) {
            return new Outcome(null, "缺少 action 字段");
        }
        action = action.trim();

        JsonObject args = resolveArgs(obj, action);
        // action 里残留的 "{x:1,y:2}" 兜底再抽一次（Gson 能解析但名字带花括号的情况）
        int brace = action.indexOf('{');
        if (brace >= 0) {
            String inline = action.substring(brace);
            action = action.substring(0, brace).trim();
            if (args.entrySet().isEmpty()) {
                args = parseLooseObject(inline);
            }
        }

        if (IDLE_WORDS.contains(action.toLowerCase())) {
            return new Outcome(null, null);
        }

        NpcTool tool = resolveTool(action);
        if (tool == null) {
            return new Outcome(null, "未知 action: " + action
                    + "；可用: " + String.join(", ", registry.all().stream().map(NpcTool::name).toList()));
        }

        List<String> errors = tool.schema().validate(args);
        if (!errors.isEmpty()) {
            return new Outcome(null, "参数不合法: " + String.join("; ", errors));
        }

        ToolRequest request = adapter.toRequest(new ToolCall(tool.name(), args),
                callerType, callerId, npcId, playerUuid);
        if (request == null) {
            return new Outcome(null, "工具未注册: " + tool.name());
        }
        return new Outcome(request, null);
    }

    // ===== 内部解析 =====

    /**
     * 名字归一：先按规范名/wire 名精确查，再按唯一后缀匹配。
     * <p>
     * 实测 MiniCPM 会把 {@code npc.move_to} 输出成 {@code move_to}（丢掉命名空间）。
     * 只在“唯一命中”时才接受后缀匹配，避免 {@code get} 这种短词误配到多个工具。
     */
    private NpcTool resolveTool(String action) {
        NpcTool direct = registry.find(action);
        if (direct != null) return direct;

        String key = action.replace('.', '_').toLowerCase();
        List<NpcTool> candidates = new ArrayList<>();
        for (NpcTool tool : registry.all()) {
            String wire = tool.wireName().toLowerCase();
            if (wire.equals(key) || wire.endsWith("_" + key)) {
                candidates.add(tool);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }


    /** 去掉 think 块与 markdown 围栏，压成干净文本 */
    private static String normalizeText(String raw) {
        if (raw == null) return "";
        String text = raw
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<\\|think\\|>.*?</\\|think\\|>", "")
                .trim();
        // ```json ... ``` 或 ``` ... ```
        Matcher fence = Pattern.compile("(?s)```(?:json)?\\s*(.*?)```").matcher(text);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            if (!inner.isBlank()) return inner;
        }
        return text;
    }

    /**
     * 括号栈提取第一个完整 JSON 对象（字符串内的括号不计数）。
     * 找不到完整对象时返回从第一个 '{' 到文本末尾的片段（让修复逻辑还有机会处理）。
     */
    static String extractFirstObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return text;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        // 没闭合（MiniCPM 常见）→ 补上闭合符，交给修复逻辑
        return text.substring(start) + "}";
    }

    private static JsonObject tryParse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 修复 {@code "action":"npc_move_to {x:100,y:64,z:100,speed:1.0}"}：
     * 把 action 值里的裸参数拆成标准 args。
     */
    static String repairInlineActionArgs(String text) {
        Matcher m = ACTION_WITH_INLINE_ARGS.matcher(text);
        if (!m.find()) return text;
        String tool = m.group(1);
        String body = m.group(2);

        String replacement = "\"action\":\"" + tool + "\"";
        String rest = text.substring(m.end());
        // 实测坏格式里 action 的字符串根本没闭合，后面的引号/逗号是残留垃圾：
        //   {"action":"npc_move_to {x:1,...},"
        // 先丢掉残留的闭合引号，再丢掉只剩 "," 的尾巴，交给括号补齐逻辑收口。
        if (rest.startsWith("\"")) {
            rest = rest.substring(1);
        }
        rest = rest.replaceFirst("^\\s*,\\s*\"?\\s*$", "");
        // 后面已经显式给了 args/arguments 时不要重复插入（Gson 会保留最后一个，但仍避免混淆）
        boolean hasExplicitArgs = rest.contains("\"args\"") || rest.contains("\"arguments\"");
        if (!hasExplicitArgs) {
            replacement += ",\"args\":" + looseToJson(body);
        }
        return text.substring(0, m.start()) + replacement + rest;
    }

    /** 从对象里取参数：优先 args/arguments/parameters，其次把所有非元字段当参数 */
    private static JsonObject resolveArgs(JsonObject obj, String action) {
        for (String key : List.of("args", "arguments", "parameters")) {
            JsonElement el = obj.get(key);
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonObject()) return el.getAsJsonObject();
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                // arguments 被写成 JSON 字符串（部分模板会这样）
                JsonObject parsed = tryParse(extractFirstObject(el.getAsString()));
                if (parsed != null) return parsed;
                return parseLooseObject(el.getAsString());
            }
        }
        // 参数与 action 平铺：{"action":"npc_move_to","x":1,"y":2,"z":3}
        JsonObject args = new JsonObject();
        for (String key : new ArrayList<>(obj.keySet())) {
            if (key.equals("action") || key.equals("tool") || key.equals("name")
                    || key.equals("action_id") || key.equals("reason")
                    || key.equals("args") || key.equals("arguments") || key.equals("parameters")) {
                continue;
            }
            args.add(key, obj.get(key));
        }
        return args;
    }

    /**
     * 裸参数对象 → JSON：{@code {x:100,y:64, speed:1.0}} → {@code {"x":100,"y":64,"speed":1.0}}。
     * 只处理简单标量，够 MiniCPM 这种 1B 模型的输出形态。
     */
    static JsonObject parseLooseObject(String body) {
        JsonObject out = new JsonObject();
        if (body == null) return out;
        String trimmed = body.trim();
        int l = trimmed.indexOf('{');
        int r = trimmed.lastIndexOf('}');
        if (l >= 0) trimmed = trimmed.substring(l + 1, r > l ? r : trimmed.length());

        for (String pair : trimmed.split(",")) {
            int sep = pair.indexOf(':');
            if (sep < 0) sep = pair.indexOf('=');
            if (sep < 0) continue;
            String key = unquote(pair.substring(0, sep).trim());
            String value = unquote(pair.substring(sep + 1).trim());
            if (key.isEmpty()) continue;
            if (isNumeric(value)) {
                out.addProperty(key, Double.parseDouble(value));
            } else if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                out.addProperty(key, Boolean.parseBoolean(value));
            } else {
                out.addProperty(key, value);
            }
        }
        return out;
    }

    /** 裸参数对象转成合法 JSON 文本（供插入到 action 后面） */
    static String looseToJson(String body) {
        JsonObject obj = parseLooseObject(body);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : obj.keySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(key)).append("\":");
            JsonElement el = obj.get(key);
            if (el.getAsJsonPrimitive().isNumber() || el.getAsJsonPrimitive().isBoolean()) {
                sb.append(el.getAsString());
            } else {
                sb.append('"').append(escape(el.getAsString())).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String unquote(String s) {
        String v = s.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static boolean isNumeric(String s) {
        return s != null && s.matches("-?\\d+(\\.\\d+)?");
    }

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull() && el.isJsonPrimitive()
                    && el.getAsJsonPrimitive().isString()) {
                return el.getAsString();
            }
        }
        return null;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
