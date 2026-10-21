package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.howard.mcai.mob.VillagerProfession;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * 职业设定（人设 + 说话风格/口头禅）—— 原本写死在 VillagerProfession 枚举与
 * PromptBuilder 的风格表里，2026-10-22 按服主要求外置成 professions.json，
 * 可在配置面板「职业设定」页签里改（起因：服主想改铁匠的口头禅“叮当”，
 * 在面板里翻不到——它当时只存在于代码）。
 * <p>
 * 加载顺序与 PromptTemplates 同路：数据文件夹的 professions.json 优先；
 * 文件缺失就把内置版拷过去；某个职业没写或字段空 → 回退枚举内置默认，
 * 保证任何文件问题都不会把人设掏空。
 */
public final class ProfessionData {

    private ProfessionData() {
    }

    public record Entry(String persona, String speech) {
    }

    private static volatile Map<VillagerProfession, Entry> overrides = Map.of();

    /** 从数据文件夹加载（没有就把内置版拷过去再读）；成功返回覆盖到的职业数，失败返回 -1 并保持原值 */
    public static synchronized int load(Path dataFolder) {
        Path file = dataFolder.resolve("professions.json");
        try {
            if (!Files.exists(file)) {
                try (InputStream is = ProfessionData.class.getResourceAsStream("/professions.json")) {
                    if (is != null) {
                        Files.createDirectories(dataFolder);
                        Files.copy(is, file);
                    }
                }
            }
            if (!Files.exists(file)) return -1;
            Map<VillagerProfession, Entry> parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed == null) return -1;
            overrides = parsed;
            return parsed.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 解析 professions.json 文本；坏 JSON 返回 null，单个坏条目跳过（不连累整表） */
    public static Map<VillagerProfession, Entry> parse(String json) {
        if (json == null || json.isBlank()) return null;
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        Map<VillagerProfession, Entry> out = new EnumMap<>(VillagerProfession.class);
        for (VillagerProfession p : VillagerProfession.values()) {
            if (!root.has(p.name()) || !root.get(p.name()).isJsonObject()) continue;
            JsonObject o = root.getAsJsonObject(p.name());
            String persona = str(o, "persona");
            String speech = str(o, "speech");
            if (persona == null && speech == null) continue;
            out.put(p, new Entry(persona, speech));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        String v = o.get(key).getAsString();
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** 某职业的人设一句话：文件有就用文件的，没有回退枚举内置 */
    public static String persona(VillagerProfession p) {
        Entry e = overrides.get(p);
        return e != null && e.persona() != null ? e.persona() : p.personalityEn;
    }

    /** 某职业的说话风格（口头禅在此）：文件有就用文件的，没有回退枚举内置 */
    public static String speech(VillagerProfession p) {
        Entry e = overrides.get(p);
        return e != null && e.speech() != null ? e.speech() : p.speechNotes;
    }
}
