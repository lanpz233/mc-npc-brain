package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC 人格标签库 —— 让每个村民有独特的性格，而不是只有"职业模板"。
 * <p>
 * 数据源：personality_tags.json（项目 resources 目录，onEnable 时 copy 到 data folder）。
 * JSON 结构：
 * <pre>
 * {
 *   "_meta": { "categories": { "core":"性格底色", "quirks":"怪癖", "romance":"情感倾向", "speech":"说话风格" } },
 *   "tags": {
 *     "big_mouth": { "category":"quirks", "name_zh":"大嘴巴", "prompt":"...", "traits":{"gossip_prone":0.4} },
 *     ...
 *   }
 * }
 * </pre>
 * <p>
 * 分配规则（来自 _meta.assign_rule）：每个 NPC 抽 2-4 个标签 =
 * 1 个 core（必选）+ 1 个 speech（50%）+ 1 个 quirks（50%）+ 1 个 romance（50%）。
 * <p>
 * 用途：
 * 1. VillagerState 构造时抽标签并持久化 → 重启不变
 * 2. PromptBuilder 把标签 prompt 拼进 system prompt（"## Personal Traits"）→ 模型按性格说话
 * 3. traits（open_mindedness / gossip_prone）是数值修正值，叠加到基础值后钳制 [0,1]
 *    —— 大嘴巴村民更爱传八卦、憨厚村民更容易轻信教学
 * <p>
 * 与 RecipeLookup 的关系：加载模式相同（data folder 优先，jar resources 兜底），
 * 但这里做成全 static —— VillagerState（mob 包）构造时无需实例引用也能抽标签。
 */
public final class PersonalityTagLibrary {

    /** 单条人格标签 */
    public record Tag(String id, String category, String nameZh, String prompt, Map<String, Double> traits) {
        /** 取某个修正值；没有返回 0 */
        public double trait(String key) {
            Double v = traits.get(key);
            return v == null ? 0.0 : v;
        }
    }

    /** 标签池（static）—— load() 填充，drawTagIds/tagById 直接读 */
    private static final Map<String, Tag> TAGS = new LinkedHashMap<>();

    /** 分类英文 id → 中文名（_meta.categories，加载时填充） */
    private static final Map<String, String> CATEGORY_ZH = new LinkedHashMap<>();

    private static boolean loaded = false;

    /** 无法解析/缺文件时返回空池而不是 NPE */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 标签总数（debug 用） */
    public static int size() {
        return TAGS.size();
    }

    /** 按 id 取标签；不存在返回 null */
    public static Tag tagById(String id) {
        return TAGS.get(id);
    }

    /**
     * 从 jar 内部 resources 或 data folder 文件加载。
     * 优先读 data folder（用户可能在那里改），没有就从 jar resources copy。
     */
    public static void load(Path dataFolder) {
        TAGS.clear();
        CATEGORY_ZH.clear();
        loaded = false;
        // 1. 先试 data folder 里的文件（用户可能编辑过）
        Path file = dataFolder.resolve("personality_tags.json");
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    loaded = parse(JsonParser.parseReader(r).getAsJsonObject());
                    return;
                }
            }
            // 2. data folder 没有 → 从 jar resources copy 过来再读
            try (InputStream is = PersonalityTagLibrary.class.getClassLoader()
                    .getResourceAsStream("personality_tags.json")) {
                if (is != null) {
                    Files.createDirectories(dataFolder);
                    Files.copy(is, file);
                    try (Reader r = Files.newBufferedReader(file)) {
                        loaded = parse(JsonParser.parseReader(r).getAsJsonObject());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 绝不静默吞掉：曾经 personality_tags.json 少一个右括号导致整库加载失败，
            // 日志却只报"未找到"，排查了很久。打印出来一眼可见根因。
            loaded = false;
            System.out.println("[MC-AI] 人格标签加载失败 (" + file + "): " + e);
        }
    }

    /** 解析 JSON 进 static 池子；解析失败返回 false */
    private static boolean parse(JsonObject root) {
        if (root == null) return false;
        // _meta.categories → 分类中文名（用于 prompt 里标注释）
        if (root.has("_meta") && root.getAsJsonObject("_meta").has("categories")) {
            JsonObject cats = root.getAsJsonObject("_meta").getAsJsonObject("categories");
            for (var e : cats.entrySet()) {
                CATEGORY_ZH.put(e.getKey(), e.getValue().getAsString());
            }
        }
        JsonObject tags = root.has("tags") ? root.getAsJsonObject("tags") : null;
        if (tags == null) return false;
        for (var e : tags.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Map<String, Double> traits = new LinkedHashMap<>();
            if (o.has("traits") && o.get("traits").isJsonObject()) {
                for (var te : o.getAsJsonObject("traits").entrySet()) {
                    traits.put(te.getKey(), te.getValue().getAsDouble());
                }
            }
            TAGS.put(e.getKey(), new Tag(
                    e.getKey(),
                    o.has("category") ? o.get("category").getAsString() : "core",
                    o.has("name_zh") ? o.get("name_zh").getAsString() : e.getKey(),
                    o.has("prompt") ? o.get("prompt").getAsString() : "",
                    traits));
        }
        return !TAGS.isEmpty();
    }

    /**
     * 抽取一个 NPC 的人格标签 id 列表 —— 分配规则见类注释。
     * 未加载/缺 core 标签时返回空列表（NPC 退回纯职业人设，不崩）。
     */
    // 硬性互斥组（按旧版 id；若新增 hoarder/generous_spirit 已入 JSON，最后一组自动生效）
    private static final List<String[]> HARD_EXCLUSIVE = List.of(
        new String[]{"optimist", "pessimist"},
        new String[]{"hot_tempered", "aloof"},
        new String[]{"talkative", "terse"},
        new String[]{"show_off", "coward"},
        new String[]{"hoarder", "generous_spirit"}
    );
    private static final java.util.Random RANDOM = new java.util.Random();
    public static List<String> drawTagIds(int playerAffection) {
        List<String> ids = new ArrayList<>();
        if (!loaded) return ids;

        // 1 core 必选
        List<String> cores = categoryIds("core");
        if (cores.isEmpty()) return ids;
        ids.add(randomOf(cores));

        // speech 可选（40%）
        List<String> speeches = categoryIds("speech");
        if (!speeches.isEmpty() && RANDOM.nextDouble() < 0.4) {
            ids.add(randomOf(speeches));
        }

        // quirks 强制 1~3（防御性复制 + 均匀分布）
        List<String> quirks = new ArrayList<>(categoryIds("quirks"));
        if (!quirks.isEmpty()) {
            Collections.shuffle(quirks, RANDOM);
            int count = 1 + RANDOM.nextInt(Math.min(3, quirks.size()));
            ids.addAll(quirks.subList(0, count));
        }

        // biography 强制 1~2（新增，P0）
        List<String> bios = new ArrayList<>(categoryIds("biography"));
        if (!bios.isEmpty()) {
            Collections.shuffle(bios, RANDOM);
            int count = 1 + RANDOM.nextInt(Math.min(2, bios.size()));
            ids.addAll(bios.subList(0, count));
        }

        // romance：好感 ≥25 才抽，且低概率（方案 A）
        List<String> romances = categoryIds("romance");
        if (!romances.isEmpty() && playerAffection >= 25 && RANDOM.nextDouble() < 0.10) {
            ids.add(randomOf(romances));
        }

        // 互斥剔除（保留先抽到的）
        resolveExclusive(ids);
        return ids;
    }

    private static void resolveExclusive(List<String> ids) {
        for (String[] pair : HARD_EXCLUSIVE) {
            if (ids.contains(pair[0]) && ids.contains(pair[1])) {
                ids.remove(pair[1]);
            }
        }
    }

    public static List<String> categoryIds(String category) {
        List<String> ids = new ArrayList<>();
        for (var e : TAGS.entrySet()) {
            if (e.getValue().category().equals(category)) ids.add(e.getKey());
        }
        return ids;
    }

    private static String randomOf(List<String> pool) {
        return pool.get((int) (Math.random() * pool.size()));
    }

    /**
     * 把标签列表拼成 system prompt 里的"## Personal Traits"段落（全中文）。
     * 格式：- 【性格底色】憨厚老实：待人真诚，从不撒谎...
     * 空标签返回空串（调用方跳过，不污染 prompt）。
     */
    public static String buildPromptTraits(List<String> ids) {
        if (!loaded || ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Personal Traits (IRON — this is WHO YOU ARE, weave it into every reply)\n");
        for (String id : ids) {
            Tag t = TAGS.get(id);
            if (t == null) continue;
            String catZh = CATEGORY_ZH.getOrDefault(t.category(), t.category());
            sb.append("- 【").append(catZh).append("】").append(t.nameZh())
                    .append("：").append(t.prompt()).append("\n");
        }
        return sb.toString();
    }
}
