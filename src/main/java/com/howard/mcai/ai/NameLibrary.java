package com.howard.mcai.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC 名字库 —— 姓氏池（加权）+ 名字用字池（分男女），生成"姓 + 1~2 字"的中文名，
 * 并支持<b>从名字反推性别</b>。
 * <p>
 * 为什么需要它：
 * 1. 名字必须符合中文习惯，且<b>性别要跟着名字走</b> —— 之前性别是随机抽的，
 *    出现"刘翠花自称俺老汉"这种崩人设的问题。用字池天然分男女，名字一生成性别就定了。
 * 2. 姓氏按真实分布加权（王李张刘最多），并提供"本村主姓"让大部分村民同姓，像真村庄。
 * <p>
 * 数据源（onEnable 时 copy 到 data folder，同 personality_tags.json 加载模式）：
 * <ul>
 *   <li>surnames.json —— 姓氏 + 权重</li>
 *   <li>given_names.json —— female/male 用字池</li>
 * </ul>
 * 本村主姓落在 data folder 的 village_names.json（首次启动随机抽一个并落盘），
 * 保证重启后新村民的姓氏分布稳定，不会每次重启换一个主姓。
 */
public final class NameLibrary {

    /** 名字用字池（按性别） */
    private static final List<String> FEMALE_CHARS = new ArrayList<>();
    private static final List<String> MALE_CHARS = new ArrayList<>();

    /** 姓氏池 + 对应权重（同下标） */
    private static final List<String> SURNAMES = new ArrayList<>();
    private static final List<Integer> SURNAME_WEIGHTS = new ArrayList<>();
    private static int SURNAME_WEIGHT_TOTAL = 0;

    /** 双字名概率（given_names.json _meta：70% 双字 / 30% 单字） */
    private static final double DOUBLE_CHAR_RATE = 0.70;

    /** 本村主姓使用概率（surnames.json _meta：75–85% 村民用主姓） */
    private static final double MAIN_SURNAME_RATE = 0.80;

    /** 本村主姓（village_names.json 持久化；加载失败时为 null，走加权随机） */
    private static String mainSurname = null;

    /**
     * 每村一个主姓（Fong 2026-10-23 定）：村庄 id（VillageTrust 的划分）→ 该村主姓。
     * <p>
     * 旧实现只有一份全服主姓，结果走到天涯海角都是同一个姓。改为按村落盘：
     * 第一次给某个村的村民起名时抽定，之后该村新村民 80% 随本村主姓、20% 杂姓。
     * 旧档迁移：第一个被问到的村子继承旧主姓（老服的主村就是它，“李村”不改口）。
     */
    private static final Map<Integer, String> VILLAGE_SURNAMES = new HashMap<>();

    /** village_names.json 的落盘位置（load 时记下，新村子抽姓后回写用） */
    private static Path villageProfileFile = null;

    private static boolean loaded = false;

    /** 名字池加载失败时的兜底用字（尽量少用，保证不 NPE、不生成空白名） */
    private static final String[] FB_SURNAMES = {"王", "李", "张", "刘", "陈"};
    private static final String[] FB_FEMALE = {"花", "兰", "秀", "娟", "梅"};
    private static final String[] FB_MALE = {"柱", "铁", "山", "福", "强"};

    private NameLibrary() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** 用字池总数（debug 用） */
    public static int givenCharCount() {
        return FEMALE_CHARS.size() + MALE_CHARS.size();
    }

    /** 姓氏总数（debug 用） */
    public static int surnameCount() {
        return SURNAMES.size();
    }

    /** 本村主姓（debug / 日志用）；未加载返回 null */
    public static String mainSurname() {
        return mainSurname;
    }

    /**
     * 从 jar resources 或 data folder 加载两个名字池（data folder 优先，缺则从 jar copy）。
     * 任一池为空视作加载失败（isLoaded=false），此时所有生成方法走兜底用字。
     */
    public static void load(Path dataFolder) {
        FEMALE_CHARS.clear();
        MALE_CHARS.clear();
        SURNAMES.clear();
        SURNAME_WEIGHTS.clear();
        SURNAME_WEIGHT_TOTAL = 0;
        mainSurname = null;
        VILLAGE_SURNAMES.clear();
        villageProfileFile = null;
        loaded = false;

        boolean okGiven = loadJson(dataFolder, "given_names.json", NameLibrary::parseGiven);
        boolean okSurnames = loadJson(dataFolder, "surnames.json", NameLibrary::parseSurnames);
        loaded = okGiven && okSurnames
                && !FEMALE_CHARS.isEmpty() && !MALE_CHARS.isEmpty() && !SURNAMES.isEmpty();
        if (loaded) {
            loadVillageProfile(dataFolder);
        }
    }

    /** data folder 优先，缺则从 jar resources copy 到 data folder 再读；统一解析入口 */
    private static boolean loadJson(Path dataFolder, String fileName,
                                    java.util.function.Function<JsonObject, Boolean> parser) {
        Path file = dataFolder.resolve(fileName);
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    return Boolean.TRUE.equals(parser.apply(JsonParser.parseReader(r).getAsJsonObject()));
                }
            }
            try (InputStream is = NameLibrary.class.getClassLoader().getResourceAsStream(fileName)) {
                if (is == null) return false;
                Files.createDirectories(dataFolder);
                Files.copy(is, file);
                try (Reader r = Files.newBufferedReader(file)) {
                    return Boolean.TRUE.equals(parser.apply(JsonParser.parseReader(r).getAsJsonObject()));
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;  // 解析失败静默兜底，不阻断插件启动
        }
    }

    private static Boolean parseGiven(JsonObject root) {
        if (root == null) return false;
        if (root.has("female")) {
            for (JsonElement e : root.getAsJsonArray("female")) FEMALE_CHARS.add(e.getAsString());
        }
        if (root.has("male")) {
            for (JsonElement e : root.getAsJsonArray("male")) MALE_CHARS.add(e.getAsString());
        }
        return !FEMALE_CHARS.isEmpty() && !MALE_CHARS.isEmpty();
    }

    private static Boolean parseSurnames(JsonObject root) {
        if (root == null || !root.has("surnames")) return false;
        for (JsonElement e : root.getAsJsonArray("surnames")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
            SURNAMES.add(name);
            SURNAME_WEIGHTS.add(Math.max(1, weight));
            SURNAME_WEIGHT_TOTAL += Math.max(1, weight);
        }
        return !SURNAMES.isEmpty();
    }

    /**
     * 读取/初始化各村主姓（village_names.json）。
     * <p>
     * 文件格式 v2：{@code {"mainSurname": "李", "villages": {"1": "李", "2": "王"}}}。
     * 旧格式只有 mainSurname（全服主姓）也能读：它先留作全局回退，等第一个村子来问时
     * 由 {@link #surnameForVillage(int)} 继承过去。文件不存在/损坏则抽一个全局回退主姓。
     */
    private static void loadVillageProfile(Path dataFolder) {
        villageProfileFile = dataFolder.resolve("village_names.json");
        try {
            if (Files.exists(villageProfileFile)) {
                try (Reader r = Files.newBufferedReader(villageProfileFile)) {
                    JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                    if (o.has("mainSurname")) {
                        String m = o.get("mainSurname").getAsString();
                        if (SURNAMES.contains(m)) mainSurname = m;
                    }
                    if (o.has("villages") && o.get("villages").isJsonObject()) {
                        for (var e : o.getAsJsonObject("villages").entrySet()) {
                            String s = e.getValue().getAsString();
                            if (SURNAMES.contains(s)) {
                                VILLAGE_SURNAMES.put(Integer.parseInt(e.getKey()), s);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 读坏了不阻断：下面统一兜底抽一个
        }
        if (mainSurname == null && !SURNAMES.isEmpty()) mainSurname = weightedSurname();
        saveVillageProfile();
    }

    /** 把全局回退主姓 + 各村主姓写回 village_names.json（新村子抽姓后调用） */
    private static void saveVillageProfile() {
        if (villageProfileFile == null) return;
        try {
            JsonObject o = new JsonObject();
            if (mainSurname != null) o.addProperty("mainSurname", mainSurname);
            JsonObject vs = new JsonObject();
            for (var e : VILLAGE_SURNAMES.entrySet()) {
                vs.addProperty(String.valueOf(e.getKey()), e.getValue());
            }
            o.add("villages", vs);
            Files.createDirectories(villageProfileFile.getParent());
            try (Writer w = Files.newBufferedWriter(villageProfileFile)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
            }
        } catch (IOException | RuntimeException e) {
            // 落盘失败不阻断起名：各村主姓本次运行内仍有效，下次启动重新抽
        }
    }

    /**
     * 某个村的主姓：已抽定则复用（重启稳定）；第一次问到这个村时抽定并落盘。
     * villageId &lt; 0（不知道在哪个村）→ 全局回退主姓（旧口径）。
     */
    public static synchronized String surnameForVillage(int villageId) {
        if (villageId < 0) return mainSurname;
        String s = VILLAGE_SURNAMES.get(villageId);
        if (s != null) return s;
        if (VILLAGE_SURNAMES.isEmpty() && mainSurname != null) {
            s = mainSurname;  // 第一个村子继承旧档主姓：老服的主村不改口
        } else {
            s = drawVillageSurname();
        }
        VILLAGE_SURNAMES.put(villageId, s);
        saveVillageProfile();
        return s;
    }

    /** 给新村子抽主姓：尽量不跟已有村子撞姓——各村一个姓才认得出谁是谁，抽 8 次还撞就认了 */
    private static String drawVillageSurname() {
        java.util.Set<String> used = new java.util.HashSet<>(VILLAGE_SURNAMES.values());
        String s = weightedSurname();
        for (int i = 0; i < 8 && used.contains(s); i++) {
            s = weightedSurname();
        }
        return s;
    }

    // ===== 性别反推 =====

    /**
     * 从名字反推性别 —— 跳过姓氏（首字），扫描名字用字（第 2 字起）在男女池里的命中数：
     * 女字多 → "F"，男字多 → "M"，持平/都没命中 → null（调用方回退持久化性别）。
     * <p>
     * 这样旧数据（如"刘翠花"）也能纠正过来：名字带"花"→ 判为女，不再随机。
     */
    public static String inferGender(String name) {
        if (name == null) return null;
        String clean = name.replaceAll("§.", "").trim();
        if (clean.length() <= 1) return null;   // 只有姓、无名字用字 → 判不出
        int f = 0;
        int m = 0;
        for (int i = 1; i < clean.length(); i++) {
            String c = String.valueOf(clean.charAt(i));
            if (FEMALE_CHARS.contains(c)) f++;
            if (MALE_CHARS.contains(c)) m++;
        }
        if (f > m) return "F";
        if (m > f) return "M";
        return null;
    }

    // ===== 名字生成 =====

    /** 性别随机（50/50）—— 新村民没有性别约束时用 */
    public static String randomGender() {
        return Math.random() < 0.5 ? "F" : "M";
    }

    /** 取姓氏（不知道在哪个村时）：80% 用全局回退主姓，其余按权重抽杂姓 */
    public static String randomSurname() {
        return randomSurname(-1);
    }

    /**
     * 取某村的姓氏：80% 用本村主姓，其余按权重抽杂姓
     * （模拟真实村庄：一个村一个大姓，再掺个别外来户）。
     */
    public static String randomSurname(int villageId) {
        String main = villageId >= 0 ? surnameForVillage(villageId) : mainSurname;
        if (main != null && Math.random() < MAIN_SURNAME_RATE) return main;
        return weightedSurname();
    }

    private static String weightedSurname() {
        if (SURNAMES.isEmpty()) return FB_SURNAMES[(int) (Math.random() * FB_SURNAMES.length)];
        if (SURNAME_WEIGHT_TOTAL <= 0) return SURNAMES.get(0);
        int r = (int) (Math.random() * SURNAME_WEIGHT_TOTAL);
        for (int i = 0; i < SURNAMES.size(); i++) {
            r -= SURNAME_WEIGHTS.get(i);
            if (r < 0) return SURNAMES.get(i);
        }
        return SURNAMES.get(SURNAMES.size() - 1);
    }

    /** 按性别生成名字主体（不含姓）：70% 双字、30% 单字；池缺时用兜底字 */
    public static String randomGivenName(String gender) {
        boolean female = "F".equals(gender);
        List<String> pool = female ? FEMALE_CHARS : MALE_CHARS;
        if (pool.isEmpty()) return randomFallbackGiven(female);
        StringBuilder sb = new StringBuilder(randomOf(pool));
        if (Math.random() < DOUBLE_CHAR_RATE) sb.append(randomOf(pool));
        return sb.toString();
    }

    /** 生成完整名：姓氏 + 按性别的名字用字（性别天然跟着名字走） */
    public static String fullName(String gender) {
        return fullName(gender, -1);
    }

    /** 生成某村村民的完整名：80% 随本村主姓 */
    public static String fullName(String gender, int villageId) {
        return randomSurname(villageId) + randomGivenName(gender);
    }

    private static String randomOf(List<String> pool) {
        return pool.get((int) (Math.random() * pool.size()));
    }

    private static String randomFallbackGiven(boolean female) {
        String[] pool = female ? FB_FEMALE : FB_MALE;
        StringBuilder sb = new StringBuilder(pool[(int) (Math.random() * pool.length)]);
        if (Math.random() < DOUBLE_CHAR_RATE) sb.append(pool[(int) (Math.random() * pool.length)]);
        return sb.toString();
    }
}
