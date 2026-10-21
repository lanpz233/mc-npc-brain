package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 村民对话提示词（约束词）模板 —— 原本写死在 PromptBuilder 里的固定段落，
 * 2026-10-22 按服主要求外置成 prompts.json，可在配置面板里改。
 *
 * <p>分工不变：这里只有"怎么说"的约束（身份、世界铁律、行为规矩、商店话术…），
 * 动态内容（村民名字/性格/记忆/场景/背包清单）仍由 PromptBuilder 在代码里拼。
 * identity 段的 {name} {profession} {personality} {speech} 是插件填空位。</p>
 *
 * <p>加载顺序：数据文件夹的 prompts.json 优先；读不到/坏了/缺段 → 回退 jar 内置版，
 * 保证提示词永远不会因为文件问题被掏空。</p>
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /** 全部段落键（缺一不可，面板保存时同口径校验） */
    public static final List<String> KEYS = List.of(
            "identity", "world_facts", "behavior_rules", "shop_rules", "knowledge",
            "state_rules", "needs_rules", "perm_envoy", "perm_confidant", "perm_civilian",
            "backpack_rules");

    private static volatile Map<String, String> sections;
    private static volatile Map<String, String> bundledCache;

    /** 从数据文件夹加载（没有就把内置版拷过去再读）；成功返回段数，失败返回 -1 并保持原值 */
    public static synchronized int load(Path dataFolder) {
        Path file = dataFolder.resolve("prompts.json");
        try {
            if (!Files.exists(file)) {
                try (InputStream is = PromptTemplates.class.getResourceAsStream("/prompts.json")) {
                    if (is != null) {
                        Files.createDirectories(dataFolder);
                        Files.copy(is, file);
                    }
                }
            }
            if (!Files.exists(file)) return -1;
            Map<String, String> parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed == null) return -1;
            sections = parsed;
            return parsed.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 取一段模板；从未成功 load 过时用 jar 内置版兜底 */
    public static String get(String key) {
        Map<String, String> s = sections;
        if (s == null) {
            s = bundled();
            if (s != null) sections = s;
        }
        if (s == null) return "";
        String v = s.get(key);
        return v != null ? v : "";
    }

    public static boolean isLoaded() {
        return sections != null && !sections.isEmpty();
    }

    private static Map<String, String> bundled() {
        if (bundledCache == null) {
            try (InputStream in = PromptTemplates.class.getResourceAsStream("/prompts.json")) {
                if (in != null) {
                    bundledCache = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
        return bundledCache;
    }

    /** 解析 + 完整性校验：11 段全在且非空才算数，否则返回 null */
    private static Map<String, String> parse(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("sections") || !root.get("sections").isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject("sections");
            Map<String, String> out = new LinkedHashMap<>();
            for (String key : KEYS) {
                if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return null;
                String v = obj.get(key).getAsString();
                if (v == null || v.isBlank()) return null;
                out.put(key, v);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
