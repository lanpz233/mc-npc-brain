package com.howard.mcai.webpanel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 针对 config.yml 的"保注释"标量编辑器。
 *
 * <p>为什么不用 YAML 库整体重写：服主的 config.yml 里有大量说明注释，
 * 整体序列化会把注释和排版全冲掉（服主靠注释看懂每一项）。本类只做一件事：
 * 按路径找到某一行叶子设置，只替换它的值，缩进、键名、行尾注释一律原样保留；
 * 路径不存在时，按父段落的缩进把新行插进正确位置。</p>
 *
 * <p>只支持本插件 config.yml 的形态：最多三层嵌套映射、叶子全是标量
 * （布尔/数字/字符串，可带引号与行尾注释）。纯文本处理、无外部依赖，可单测。</p>
 */
public final class YamlScalarFile {

    private YamlScalarFile() {
    }

    /** 一行解析结果：indent = 前导空格数；key = 键名；value = 原始值文本（段落行为 null） */
    private record Line(int indent, String key, String valueToken, String comment, boolean leaf) {
    }

    /** 读取指定路径的当前值（原始文本 → 按引号解码后的字符串）；读不到的路径不在结果里 */
    public static Map<String, String> readValues(String text, Collection<String> paths) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String p : paths) wanted.put(p, null);
        List<String> stack = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            Line line = parseLine(raw);
            if (line == null) continue;
            applyStack(stack, line);
            if (line.leaf()) {
                String path = String.join(".", stack);
                if (wanted.containsKey(path)) wanted.put(path, decode(line.valueToken()));
            }
        }
        wanted.values().removeIf(v -> v == null);
        return wanted;
    }

    /**
     * 把一批 path → Java 值写回文本：已有行只换值（保留注释与原引号风格），
     * 缺失的键插入父段落末尾，父段落也不存在时在文件尾新建。
     *
     * @return 修改后的完整文本
     */
    public static String applyValues(String text, Map<String, Object> values) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        // split 尾部会带一个空串（文件以 \n 结尾）；统一处理：先摘掉，结尾再补
        boolean trailingNewline = text.endsWith("\n");
        if (trailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        for (var entry : values.entrySet()) {
            applyOne(lines, entry.getKey(), entry.getValue());
        }
        return String.join("\n", lines) + "\n";
    }

    private static void applyOne(List<String> lines, String path, Object value) {
        String[] parts = path.split("\\.");
        String leafKey = parts[parts.length - 1];
        // 先找已有叶子行
        List<String> stack = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = parseLine(lines.get(i));
            if (line == null) continue;
            int stackBefore = stack.size();
            applyStack(stack, line);
            if (line.leaf() && String.join(".", stack).equals(path)) {
                String token = formatValue(value, line.valueToken());
                String rebuilt = " ".repeat(line.indent()) + line.key() + ": " + token
                        + (line.comment() != null ? " " + line.comment() : "");
                lines.set(i, rebuilt);
                return;
            }
            if (stackBefore > stack.size() && String.join(".", stack).equals(path)) {
                // 同名段落行不算叶子，继续找
            }
        }
        // 找不到 → 插入父段落。父路径 = path 去掉叶子
        String parentPath = path.substring(0, path.lastIndexOf('.'));
        int parentIndent = (parts.length - 2) * 2;
        int leafIndent = (parts.length - 1) * 2;
        int insertAt = findSectionEnd(lines, parentPath, parentIndent);
        if (insertAt >= 0) {
            lines.add(insertAt, " ".repeat(leafIndent) + leafKey + ": " + formatValue(value, null));
            return;
        }
        // 父段落不存在 → 文件尾新建整条链
        StringBuilder chain = new StringBuilder();
        for (int depth = 0; depth < parts.length - 1; depth++) {
            // 只补缺失的那部分链：简单起见整条父链都写，已存在的段会被 YAML 后值覆盖，
            // 但本插件的路径父段都存在于内置 config，走到这里只是极端兜底
            chain.append(" ".repeat(depth * 2)).append(parts[depth]).append(":\n");
        }
        chain.append(" ".repeat(leafIndent)).append(leafKey).append(": ").append(formatValue(value, null));
        lines.add(chain.toString());
    }

    /** 段落结束位置 = 段落行之后、缩进大于该段的所有行走完的下标；段落不存在返回 -1 */
    private static int findSectionEnd(List<String> lines, String parentPath, int parentIndent) {
        List<String> stack = new ArrayList<>();
        int sectionLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            Line line = parseLine(lines.get(i));
            if (line == null) continue;
            applyStack(stack, line);
            if (!line.leaf() && String.join(".", stack).equals(parentPath)) {
                sectionLine = i;
                break;
            }
        }
        if (sectionLine < 0) return -1;
        int end = sectionLine + 1;
        while (end < lines.size()) {
            String raw = lines.get(end);
            if (raw.isBlank()) {
                end++;
                continue;
            }
            int indent = raw.length() - raw.stripLeading().length();
            if (indent > parentIndent) {
                end++;
            } else {
                break;
            }
        }
        return end;
    }

    /** 维护路径栈：按缩进弹出/压入当前键 */
    private static void applyStack(List<String> stack, Line line) {
        int depth = line.indent() / 2;
        while (stack.size() > depth) stack.remove(stack.size() - 1);
        if (stack.size() == depth) {
            stack.add(line.key());
        } else {
            // 缩进跳级（不规范文件）时直接按当前深度截断重建
            while (stack.size() > depth) stack.remove(stack.size() - 1);
            stack.add(line.key());
        }
    }

    /** 解析一行；注释行/空行/列表行返回 null */
    private static Line parseLine(String raw) {
        if (raw == null) return null;
        String stripped = raw.strip();
        if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("-")) return null;
        int indent = raw.length() - raw.stripLeading().length();
        int colon = stripped.indexOf(':');
        if (colon <= 0) return null;
        String key = stripped.substring(0, colon).trim();
        String rest = stripped.substring(colon + 1);
        // 行尾注释：值部分里第一个不在引号内的 " #"
        String comment = null;
        String valuePart = rest;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            if (c == '"' && !inSingle && (i == 0 || rest.charAt(i - 1) != '\\')) inDouble = !inDouble;
            if (c == '#' && !inSingle && !inDouble && i > 0 && rest.charAt(i - 1) == ' ') {
                comment = rest.substring(i);
                valuePart = rest.substring(0, i);
                break;
            }
        }
        String value = valuePart.trim();
        boolean leaf = !value.isEmpty();
        return new Line(indent, key, leaf ? value : null, comment, leaf);
    }

    /** 引号解码：'..'（'' 转义）、".."（\" \\ 转义）、无引号原样 */
    private static String decode(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1).replace("''", "'");
        }
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }

    /** 值 → YAML 标量文本；originalToken 非空时尽量沿用它的引号风格 */
    private static String formatValue(Object value, String originalToken) {
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Integer i) return i.toString();
        if (value instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
            return d.toString();
        }
        String s = value == null ? "" : value.toString();
        boolean wasSingle = originalToken != null && originalToken.startsWith("'");
        boolean wasDouble = originalToken != null && originalToken.startsWith("\"");
        if (wasDouble) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (wasSingle || needsQuoting(s)) {
            return "'" + s.replace("'", "''") + "'";
        }
        return s;
    }

    private static boolean needsQuoting(String s) {
        if (s.isEmpty()) return true;
        if (!s.equals(s.trim())) return true;
        if (s.contains("#") || s.contains(": ") || s.endsWith(":")
                || s.contains("'") || s.contains("\"") || s.contains("\n")) return true;
        String lower = s.toLowerCase();
        return lower.equals("true") || lower.equals("false") || lower.equals("null")
                || lower.equals("~") || s.matches("-?\\d+(\\.\\d+)?");
    }
}
