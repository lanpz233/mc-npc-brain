package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NPC 人格标签库 —— 让每个村民有独特的性格，而不是只有"职业模板"。
 * <p>
 * 数据源：personality_tags.json（项目 resources 目录，onEnable 时 copy 到 data folder）。
 * JSON 结构（v2）：
 * <pre>
 * {
 *   "_meta": {
 *     "categories": { "core":"核心人格", "speech":"说话风格", "quirks":"习性怪癖", "romance":"情感倾向" },
 *     "exclusive_groups": [ ["sunny_optimist","grim_pessimist"], ... ],
 *     "nitwit_pool": { "core":[..], "speech":[..], "quirks":[..], "romance":[] }
 *   },
 *   "tags": {
 *     "loose_tongue": { "category":"quirks", "name_zh":"口风不严", "prompt":"...", "traits":{"gossip_prone":0.4} },
 *     ...
 *   }
 * }
 * </pre>
 * <p>
 * 分配规则（_meta.assign_rule，v2）：
 * 1 个 core（必选）+ 0~1 个 speech（50%）+ 1~3 个 quirks（必选，至少 1 个）+ 0~1 个 romance（50%）。
 * 互斥组内不会同时抽到；傻子村民走 nitwit_pool 隔离池，romance 完全禁抽。
 * <p>
 * 用途：
 * 1. VillagerState 构造时抽标签并持久化 → 重启不变
 * 2. PromptBuilder 把标签 prompt 拼进 system prompt（"## Personal Traits"）→ 模型按性格说话
 * 3. traits（open_mindedness / gossip_prone）是数值修正值，叠加到基础值后钳制 [0,1]
 *    —— 口风不严/社群熟客/好酒之人更爱传八卦，赤诚老实更不易被外来说法打动
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

    /** 互斥组（_meta.exclusive_groups）—— 组内标签最多只能抽到 1 个 */
    private static final List<List<String>> EXCLUSIVE_GROUPS = new ArrayList<>();

    /** 傻子村民隔离池（_meta.nitwit_pool）：分类 → 允许的 id。未列出的分类一律不抽 */
    private static final Map<String, Set<String>> NITWIT_POOL = new LinkedHashMap<>();

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
        // v2 新增的两块元数据也要清 —— /ai reload 时若不清，旧互斥组/隔离池会和新的叠加
        EXCLUSIVE_GROUPS.clear();
        NITWIT_POOL.clear();
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
        if (root.has("_meta")) {
            JsonObject meta = root.getAsJsonObject("_meta");
            // _meta.exclusive_groups → 互斥组（数组的数组）。缺这块老版 JSON 也不崩，只是不互斥
            if (meta.has("exclusive_groups") && meta.get("exclusive_groups").isJsonArray()) {
                for (var ge : meta.getAsJsonArray("exclusive_groups")) {
                    if (!ge.isJsonArray()) continue;
                    List<String> group = new ArrayList<>();
                    for (var idEl : ge.getAsJsonArray()) {
                        // 规格里互斥组可能写成对象 { ids:[..], note:".." } 或纯字符串数组，两者都吃
                        if (idEl.isJsonObject() && idEl.getAsJsonObject().has("ids")) {
                            for (var ie : idEl.getAsJsonObject().getAsJsonArray("ids")) {
                                group.add(ie.getAsString());
                            }
                        } else if (idEl.isJsonPrimitive()) {
                            group.add(idEl.getAsString());
                        }
                    }
                    if (group.size() >= 2) EXCLUSIVE_GROUPS.add(group);
                }
            }
            // _meta.nitwit_pool → 傻子村民隔离池：分类 → 允许 id 列表。
            // 注意"分类未列出 = 该类完全禁抽"（如 romance 缺席即傻子不谈恋爱），
            // 所以这张表要连空数组一起记下来，不能只在非空时填。
            if (meta.has("nitwit_pool") && meta.get("nitwit_pool").isJsonObject()) {
                for (var pe : meta.getAsJsonObject("nitwit_pool").entrySet()) {
                    if (!pe.getValue().isJsonArray()) continue;
                    Set<String> allowed = new LinkedHashSet<>();
                    for (var ie : pe.getValue().getAsJsonArray()) {
                        allowed.add(ie.getAsString());
                    }
                    NITWIT_POOL.put(pe.getKey(), allowed);
                }
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
     *
     * @param nitwit 是否傻子/无职业村民 —— true 时走 nitwit_pool 隔离池，
     *               未在隔离池中列出的分类直接禁抽（romance 通常全禁）
     */
    public static List<String> drawTagIds(boolean nitwit) {
        List<String> ids = new ArrayList<>();
        if (!loaded) return ids;

        // 1) core 必选。隔离池下没给出 core 就直接空手而归，避免傻子抽到"精明算计"这类人格
        String core = randomOf(allowedPool("core", nitwit));
        if (core == null) return ids;
        ids.add(core);

        // 2) quirks 必选 1~3 个（规格要求至少 1 个；互斥组会挡掉同组第二条）
        int wantQuirks = 1 + (Math.random() < 0.5 ? 1 : 0) + (Math.random() < 0.3 ? 1 : 0);
        pickInto(ids, "quirks", nitwit, wantQuirks);

        // 3) speech 0~1（50%）
        if (Math.random() < 0.5) pickInto(ids, "speech", nitwit, 1);

        // 4) romance 0~1（50%）—— 傻子隔离池里 romance 为空数组，allowedPool 返回空池，自然抽不到
        if (Math.random() < 0.5) pickInto(ids, "romance", nitwit, 1);

        return ids;
    }

    /**
     * 从某个分类里往 ids 里补至多 n 个不冲突的标签（原地追加）。
     * 会跳过与已选标签互斥的候选；候选抽完就提前结束，不无限重试。
     */
    private static void pickInto(List<String> ids, String category, boolean nitwit, int n) {
        List<String> pool = allowedPool(category, nitwit);
        if (pool.isEmpty()) return;
        // 洗牌式尝试：每次从未选中随机取一个，冲突则移除继续；避免死循环又保证随机性
        List<String> candidates = new ArrayList<>(pool);
        for (int i = 0; i < n && !candidates.isEmpty(); i++) {
            String pick = candidates.remove((int) (Math.random() * candidates.size()));
            if (conflictsWithChosen(pick, ids)) {
                i--;                 // 本名额没用掉，继续找下一个候选（candidates 在缩小，不会死循环）
                continue;
            }
            ids.add(pick);
        }
    }

    /** 候选标签是否与已选标签同处一个互斥组 */
    private static boolean conflictsWithChosen(String id, List<String> chosen) {
        for (List<String> group : EXCLUSIVE_GROUPS) {
            if (!group.contains(id)) continue;
            for (String c : chosen) {
                if (group.contains(c)) return true;
            }
        }
        return false;
    }

    /**
     * 取某分类的可用池。
     * 傻子村民走 NITWIT_POOL：该分类未列出 → 空池（完全禁抽）；
     * 普通村民直接返回该分类全部 id。
     */
    private static List<String> allowedPool(String category, boolean nitwit) {
        List<String> all = categoryIds(category);
        if (!nitwit) return all;
        Set<String> allowed = NITWIT_POOL.get(category);
        if (allowed == null || allowed.isEmpty()) return new ArrayList<>();
        List<String> filtered = new ArrayList<>();
        for (String id : all) {
            if (allowed.contains(id)) filtered.add(id);
        }
        return filtered;
    }

    private static List<String> categoryIds(String category) {
        List<String> ids = new ArrayList<>();
        for (var e : TAGS.entrySet()) {
            if (e.getValue().category().equals(category)) ids.add(e.getKey());
        }
        return ids;
    }

    /** 从池里随机取一个；空池返回 null（调用方负责兜底） */
    private static String randomOf(List<String> pool) {
        if (pool == null || pool.isEmpty()) return null;
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
