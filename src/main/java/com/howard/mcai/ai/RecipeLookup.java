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
import java.util.List;

/**
 * 合成配方查表 —— 让村民对话时能说出真实的合成材料，禁止 LLM 自行编造。
 * <p>
 * 数据源：recipes.json（项目 resources 目录，onEnable 时 copy 到 data folder）。
 * JSON 结构：{ recipes: { "minecraft:iron_sword": { materials: {...}, name_zh: "铁剑", yields: 1 } } }
 * <p>
 * 两个主要用途：
 * 1. prompt 摘要：把高频物品的配方压缩成短表格拼进 system prompt（LLM 有真实数据参考）
 * 2. /ai lookup 调试命令：控制台或游戏里查某个物品的真实配方
 */
public final class RecipeLookup {

    /**
     * Minecraft item ID → 中文俗名映射。
     * 目的：system prompt 里的配方表格全部用中文，避免英文物料名带偏模型输出语言。
     * 只覆盖 recipes.json 里实际用到的（~20 种）。映射不到时 fallback 英文 ID。
     */
    private static final java.util.Map<String, String> MATERIAL_ZH = java.util.Map.ofEntries(
            java.util.Map.entry("minecraft:iron_ingot", "铁锭"),
            java.util.Map.entry("minecraft:iron_block", "铁块"),
            java.util.Map.entry("minecraft:iron_sword", "铁剑"),
            java.util.Map.entry("minecraft:iron_pickaxe", "铁镐"),
            java.util.Map.entry("minecraft:iron_axe", "铁斧"),
            java.util.Map.entry("minecraft:iron_hoe", "铁锄"),
            java.util.Map.entry("minecraft:iron_shovel", "铁锹"),
            java.util.Map.entry("minecraft:iron_helmet", "铁头盔"),
            java.util.Map.entry("minecraft:iron_chestplate", "铁胸甲"),
            java.util.Map.entry("minecraft:iron_leggings", "铁护腿"),
            java.util.Map.entry("minecraft:iron_boots", "铁靴子"),
            java.util.Map.entry("minecraft:stick", "木棍"),
            java.util.Map.entry("minecraft:diamond", "钻石"),
            java.util.Map.entry("minecraft:diamond_sword", "钻石剑"),
            java.util.Map.entry("minecraft:diamond_pickaxe", "钻石镐"),
            java.util.Map.entry("minecraft:diamond_axe", "钻石斧"),
            java.util.Map.entry("minecraft:diamond_hoe", "钻石锄"),
            java.util.Map.entry("minecraft:diamond_shovel", "钻石锹"),
            java.util.Map.entry("minecraft:diamond_helmet", "钻石头盔"),
            java.util.Map.entry("minecraft:diamond_chestplate", "钻石胸甲"),
            java.util.Map.entry("minecraft:diamond_leggings", "钻石护腿"),
            java.util.Map.entry("minecraft:diamond_boots", "钻石靴子"),
            java.util.Map.entry("minecraft:gold_ingot", "金锭"),
            java.util.Map.entry("minecraft:oak_planks", "木板"),
            java.util.Map.entry("minecraft:cobblestone", "圆石"),
            java.util.Map.entry("minecraft:wheat", "小麦"),
            java.util.Map.entry("minecraft:string", "线"),
            java.util.Map.entry("minecraft:feather", "羽毛"),
            java.util.Map.entry("minecraft:flint", "燧石"),
            java.util.Map.entry("minecraft:coal", "煤炭"),
            java.util.Map.entry("minecraft:book", "书"),
            java.util.Map.entry("minecraft:obsidian", "黑曜石"),
            java.util.Map.entry("minecraft:anvil", "铁砧"),
            java.util.Map.entry("minecraft:chest", "箱子"),
            java.util.Map.entry("minecraft:crafting_table", "工作台"),
            java.util.Map.entry("minecraft:furnace", "熔炉"),
            java.util.Map.entry("minecraft:bucket", "铁桶"),
            java.util.Map.entry("minecraft:bow", "弓"),
            java.util.Map.entry("minecraft:arrow", "箭"),
            java.util.Map.entry("minecraft:bread", "面包"),
            java.util.Map.entry("minecraft:fishing_rod", "钓鱼竿"),
            java.util.Map.entry("minecraft:enchanting_table", "附魔台"),
            java.util.Map.entry("minecraft:golden_apple", "金苹果"),
            java.util.Map.entry("minecraft:shears", "剪刀"),
            java.util.Map.entry("minecraft:shield", "盾牌"),
            java.util.Map.entry("minecraft:torch", "火把"),
            java.util.Map.entry("minecraft:stone_axe", "石斧"),
            java.util.Map.entry("minecraft:stone_pickaxe", "石镐"),
            java.util.Map.entry("minecraft:stone_sword", "石剑"),
            java.util.Map.entry("minecraft:wooden_pickaxe", "木镐"),
            java.util.Map.entry("minecraft:wooden_sword", "木剑")
    );

    /** 把 item ID 翻成中文，映射不到就用简化英文 ID（防 NPE） */
    private static String materialToZh(String id) {
        if (MATERIAL_ZH.containsKey(id)) return MATERIAL_ZH.get(id);
        // 去掉 minecraft: 前缀 + 下划线 → 空格
        return id.replace("minecraft:", "").replace('_', ' ');
    }

    /** 原始配方 JSON（整个文件） */
    private JsonObject root = new JsonObject();

    /** recipes 子对象（key = minecraft:xxx, value = 配方对象） */
    private JsonObject recipes = new JsonObject();

    /** 加载成功与否（如果文件不存在或损坏，返回空表而不是 NPE） */
    private boolean loaded = false;

    /**
     * 从 jar 内部 resources 或 data folder 文件加载。
     * 优先读 data folder（用户可能在那里改了），没有就从 jar resources copy。
     */
    public void load(Path dataFolder) {
        // 1. 先试 data folder 里的文件（用户可能编辑过）
        Path file = dataFolder.resolve("recipes.json");
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    root = JsonParser.parseReader(r).getAsJsonObject();
                    recipes = root.has("recipes") ? root.getAsJsonObject("recipes") : new JsonObject();
                    loaded = true;
                    return;
                }
            }
            // 2. data folder 没有 → 从 jar resources copy 过来再读
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("recipes.json")) {
                if (is != null) {
                    Files.createDirectories(dataFolder);
                    Files.copy(is, file);
                    try (Reader r = Files.newBufferedReader(file)) {
                        root = JsonParser.parseReader(r).getAsJsonObject();
                        recipes = root.has("recipes") ? root.getAsJsonObject("recipes") : new JsonObject();
                        loaded = true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 损坏的 recipes.json 会抛 JsonSyntaxException（RuntimeException），只 catch IOException
            // 会把异常一路炸到 onEnable 导致插件起不来 —— 对齐 PersonalityTagLibrary 的写法，
            // 坏数据一律降级为"未加载"空表，PromptBuilder 侧已有 isLoaded() 判断兜底
            loaded = false;
        }
    }

    /** 是否加载成功（debug 用） */
    public boolean isLoaded() {
        return loaded;
    }

    /** 配方总数 */
    public int size() {
        return recipes.size();
    }

    /**
     * 按中文名字或物品 ID 查找配方。
     * 支持两种输入："铁剑" 或 "minecraft:iron_sword"。
     * 找不到返回 null。
     */
    public RecipeEntry lookup(String query) {
        if (!loaded || query == null) return null;
        String q = query.trim().toLowerCase();

        // 先按 key 精确匹配
        if (recipes.has(query)) {
            return new RecipeEntry(query, recipes.getAsJsonObject(query));
        }
        // 再按中文名字模糊匹配（contains）
        for (var entry : recipes.entrySet()) {
            JsonObject r = entry.getValue().getAsJsonObject();
            if (r.has("name_zh") && r.get("name_zh").getAsString().contains(query)) {
                return new RecipeEntry(entry.getKey(), r);
            }
        }
        return null;
    }

    /** 列出所有配方的中文名字（/ai lookup 调试用） */
    public List<String> listAllNames() {
        if (!loaded) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (var entry : recipes.entrySet()) {
            JsonObject r = entry.getValue().getAsJsonObject();
            names.add(r.has("name_zh") ? r.get("name_zh").getAsString() : entry.getKey());
        }
        return names;
    }

    /**
     * 生成 system prompt 里的配方摘要表格（英文指令 + 中文数据）。
     * 目标：~1500 字符内，高频物品优先，让 LLM 有真实参考但不要吃太多 token。
     * <p>
     * 格式示例：
     * <pre>
     * ## Knowledge: Minecraft Crafting Recipes (SHORT REFERENCE — NEVER fabricate numbers!)
     * - iron_sword → 2 iron_ingot + 1 stick
     * - iron_pickaxe → 3 iron_ingot + 2 stick
     * - diamond_sword → 2 diamond + 1 stick
     * ...
     * Items NOT listed here → say "这个我也不太懂" (never invent!).
     * </pre>
     */
    public String buildPromptSummary() {
        if (!loaded || recipes.size() == 0) {
            return "## Knowledge: No crafting recipes loaded (use generic knowledge but never fabricate exact numbers).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Knowledge: Minecraft Crafting Recipes (CRITICAL — NEVER fabricate numbers!)\n");
        sb.append("Use THESE exact materials when talking about crafting. For items NOT listed, say you don't know.\n");

        // 高频物品优先（按工具类、装备类、基础物品分组）
        // 先挑全部（当前 JSON 只有 ~40 个，全部塞 prompt 也才 ~1500 token）
        for (var entry : recipes.entrySet()) {
            JsonObject r = entry.getValue().getAsJsonObject();
            String zhName = r.has("name_zh") ? r.get("name_zh").getAsString() : entry.getKey();
            sb.append("- ").append(zhName).append(" → ");

            // materials: { "minecraft:iron_ingot": 2, "minecraft:stick": 1 }
            // 缺 materials 字段（或类型不是对象）时 getAsJsonObject 返回 null → 直接 entrySet 会 NPE，
            // 手改坏数据的玩家不该让整段 prompt 构建崩掉，缺了就标 "?" 占位
            JsonObject mats = (r.has("materials") && r.get("materials").isJsonObject())
                    ? r.getAsJsonObject("materials") : null;
            List<String> parts = new ArrayList<>();
            if (mats != null) {
                for (var mat : mats.entrySet()) {
                    parts.add(mat.getValue().getAsInt() + " " + materialToZh(mat.getKey()));
                }
            }
            sb.append(parts.isEmpty() ? "?" : String.join(" + ", parts));

            if (r.has("yields")) {
                sb.append(" (yields ").append(r.get("yields").getAsInt()).append(")");
            }
            sb.append("\n");
        }

        sb.append("RULE: If a player asks about an item NOT in this list, say \"这个我也不太懂\" — NEVER guess!\n");
        return sb.toString();
    }

    /** 单条配方记录（/ai lookup 返回用） */
    public record RecipeEntry(String key, String nameZh, String materials, int yields) {
        public RecipeEntry(String key, JsonObject raw) {
            this(key,
                    raw.has("name_zh") ? raw.get("name_zh").getAsString() : key,
                    // materials 可能缺失（手改坏数据）→ 传 null 交给 formatMaterials 判空，
                    // 避免 getAsJsonObject(...).entrySet() NPE
                    formatMaterials((raw.has("materials") && raw.get("materials").isJsonObject())
                            ? raw.getAsJsonObject("materials") : null),
                    raw.has("yields") ? raw.get("yields").getAsInt() : 1);
        }

        private static String formatMaterials(JsonObject mats) {
            if (mats == null) return "?";  // 数据缺字段时给占位符而不是抛 NPE
            List<String> parts = new ArrayList<>();
            for (var e : mats.entrySet()) {
                parts.add(e.getValue().getAsInt() + " " + materialToZh(e.getKey()));
            }
            return String.join(" + ", parts);
        }
    }
}
