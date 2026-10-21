package com.howard.mcai.webpanel;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Set;

/**
 * 网页配置面板（第一期）可编辑的数据文件白名单与内容校验。
 *
 * <p>只允许这四个现成数据文件，文件名写死在白名单里 —— 网页传什么名字都只能
 * 落到这四个文件上，不存在借面板写任意文件的口子。校验分两层：先是合法 JSON，
 * 再按每个文件自己的结构做语义检查（配方材料数量必须为正、性格标签必须有
 * 分类/中文名/提示词、名字库不能为空等）。坏数据在写盘前就被拦下，
 * 插件现有的加载器也有降级兜底，双保险。</p>
 *
 * <p>通过校验的内容会被统一格式化（缩进排版）后再落盘，语义不变。</p>
 */
public final class PanelDataFiles {

    private PanelDataFiles() {
    }

    public record DataFile(String name, String label, String description) {
    }

    public static final List<DataFile> FILES = List.of(
            new DataFile("recipes.json", "配方表", "村民谈论合成时查的配方：材料与产出数量"),
            new DataFile("personality_tags.json", "性格标签库", "村民的性格底色：分类、中文名、言行提示"),
            new DataFile("surnames.json", "姓氏库", "村民姓氏与抽取权重"),
            new DataFile("given_names.json", "名字用字库", "村民名字用字，按男女分开"),
            new DataFile("prices.json", "定价表", "基础物品的公道价（绿宝石/个），合成品由插件按配方推导"),
            new DataFile("profession-pools.json", "职业货池", "每个职业的村民卖什么、收什么"),
            new DataFile("prompts.json", "提示词", "村民对话的约束词：身份、世界铁律、行为规矩等固定段落"),
            new DataFile("professions.json", "职业设定", "各职业的人设一句话与说话风格（口头禅在这里改）"),
            new DataFile("theft-lines.json", "偷窃台词", "被抓时骂人的话 + 察觉丢东西的嘟囔（按白天/夜里/下雨分开）")
    );

    private static final Set<String> NAMES = Set.of(
            "recipes.json", "personality_tags.json", "surnames.json", "given_names.json",
            "prices.json", "profession-pools.json", "prompts.json", "professions.json", "theft-lines.json");

    /** 职业枚举名（与 mob/VillagerProfession 对应；货池文件里的键只许是这些） */
    private static final Set<String> PROFESSIONS = Set.of(
            "TOOLSMITH", "WEAPONSMITH", "ARMORER", "CLERIC", "FARMER", "LIBRARIAN",
            "CARTOGRAPHER", "FLETCHER", "FISHERMAN", "BUTCHER", "LEATHERWORKER",
            "MASON", "SHEPHERD", "NITWIT", "NONE");

    private static final java.util.regex.Pattern ITEM_ID =
            java.util.regex.Pattern.compile("^minecraft:[a-z0-9_]+$");

    private static final Set<String> TAG_CATEGORIES = Set.of("core", "speech", "quirks", "romance");

    public static boolean isAllowed(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * 校验并格式化一个数据文件的内容。
     *
     * @return 格式化后的 JSON 文本
     * @throws IllegalArgumentException 文件名不在白名单、JSON 非法或结构不对（消息是人话）
     */
    public static String validateAndFormat(String name, String content) {
        if (!isAllowed(name)) {
            throw new IllegalArgumentException("不认识的数据文件：" + name);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容是空的");
        }
        if (content.length() > 1_000_000) {
            throw new IllegalArgumentException("文件太大了（超过 1MB）");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(content);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 格式不对：" + e.getMessage());
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("文件最外层应该是一个 { } 对象");
        }
        JsonObject root = parsed.getAsJsonObject();
        switch (name) {
            case "recipes.json" -> validateRecipes(root);
            case "personality_tags.json" -> validateTags(root);
            case "surnames.json" -> validateSurnames(root);
            case "given_names.json" -> validateGivenNames(root);
            case "prices.json" -> validatePrices(root);
            case "profession-pools.json" -> validatePools(root);
            case "prompts.json" -> validatePrompts(root);
            case "professions.json" -> validateProfessions(root);
            case "theft-lines.json" -> validateTheftLines(root);
            default -> throw new IllegalArgumentException("不认识的数据文件：" + name);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }


    private static void validatePrices(JsonObject root) {
        JsonObject anchors = objectField(root, "anchors", "定价表");
        if (anchors.isEmpty()) throw new IllegalArgumentException("定价表是空的，至少留一条");
        for (var e : anchors.entrySet()) {
            if (!ITEM_ID.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("定价表里有认不出的物品 ID：" + e.getKey());
            }
            if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("定价表里 " + e.getKey() + " 的价格不是数字");
            }
            double v = e.getValue().getAsDouble();
            if (!(v > 0) || v > 100000) {
                throw new IllegalArgumentException("定价表里 " + e.getKey() + " 的价格要在 0 到 100000 之间");
            }
        }
    }

    private static void validatePools(JsonObject root) {
        JsonObject professions = objectField(root, "professions", "职业货池");
        if (professions.isEmpty()) throw new IllegalArgumentException("职业货池是空的，至少留一个职业");
        for (var e : professions.entrySet()) {
            if (!PROFESSIONS.contains(e.getKey())) {
                throw new IllegalArgumentException("不认识的职业：" + e.getKey() + "（职业名是英文枚举，不要改）");
            }
            if (!e.getValue().isJsonObject()) {
                throw new IllegalArgumentException("职业 " + e.getKey() + " 的内容应该是对象");
            }
            JsonObject o = e.getValue().getAsJsonObject();
            if (o.has("sell")) validatePoolArray(o.get("sell"), e.getKey() + " 的卖出清单");
            if (o.has("buy")) validatePoolArray(o.get("buy"), e.getKey() + " 的收购清单");
        }
        if (root.has("guest")) validatePoolArray(root.get("guest"), "杂货池");
    }

    private static void validatePoolArray(JsonElement el, String label) {
        if (!el.isJsonArray()) throw new IllegalArgumentException(label + "应该是数组");
        for (JsonElement pair : el.getAsJsonArray()) {
            if (!pair.isJsonArray() || pair.getAsJsonArray().size() != 2) {
                throw new IllegalArgumentException(label + "里每一项应该是 [物品, 个数]");
            }
            String id = pair.getAsJsonArray().get(0).isJsonPrimitive()
                    ? pair.getAsJsonArray().get(0).getAsString() : "";
            if (!ITEM_ID.matcher(id).matches()) {
                throw new IllegalArgumentException(label + "里有认不出的物品 ID：" + id);
            }
            JsonElement count = pair.getAsJsonArray().get(1);
            if (!count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()
                    || count.getAsInt() < 1 || count.getAsDouble() != count.getAsInt()) {
                throw new IllegalArgumentException(label + "里 " + id + " 的个数要是正整数");
            }
        }
    }


    /** 提示词段落键（与 ai/PromptTemplates.KEYS 对应，改键等于删段，不许） */
    private static final Set<String> PROMPT_KEYS = Set.of(
            "identity", "world_facts", "behavior_rules", "shop_rules", "knowledge",
            "state_rules", "needs_rules", "perm_envoy", "perm_confidant", "perm_civilian",
            "backpack_rules");

    /** 职业设定：键只许是职业枚举名（或 _meta），值是 {persona, speech}，字段可缺省（缺了用内置默认） */
    /** 偷窃台词：五个分组都必须是"字符串数组"，键只许这五个 + _meta */
    private static void validateTheftLines(JsonObject root) {
        java.util.Set<String> keys = java.util.Set.of(
                "first", "repeat", "confused_day", "confused_night", "confused_rain");
        for (java.util.Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().equals("_meta")) continue;
            if (!keys.contains(e.getKey())) {
                throw new IllegalArgumentException("偷窃台词里不认识的分组：" + e.getKey());
            }
            if (!e.getValue().isJsonArray()) {
                throw new IllegalArgumentException("偷窃台词的 " + e.getKey() + " 必须是一组句子");
            }
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()
                        || el.getAsString().isBlank()) {
                    throw new IllegalArgumentException("偷窃台词的 " + e.getKey() + " 里有空句子或非文字内容");
                }
            }
        }
    }

    private static void validateProfessions(JsonObject root) {
        for (String key : root.keySet()) {
            if ("_meta".equals(key)) continue;
            if (!PROFESSIONS.contains(key)) {
                throw new IllegalArgumentException("不认识的职业：" + key);
            }
            if (!root.get(key).isJsonObject()) {
                throw new IllegalArgumentException(key + " 的设定必须是一个对象 {persona, speech}");
            }
            JsonObject o = root.getAsJsonObject(key);
            for (String field : new String[]{"persona", "speech"}) {
                if (o.has(field) && (!o.get(field).isJsonPrimitive() || o.get(field).getAsString().isBlank())) {
                    throw new IllegalArgumentException(key + " 的 " + field + " 必须是非空文字（不想改就删掉这个字段）");
                }
            }
        }
    }

    private static void validatePrompts(JsonObject root) {
        JsonObject sections = objectField(root, "sections", "提示词");
        for (String key : PROMPT_KEYS) {
            if (!sections.has(key) || !sections.get(key).isJsonPrimitive()) {
                throw new IllegalArgumentException("提示词少了「" + key + "」段（段落不能删，只能改内容）");
            }
            String v = sections.get(key).getAsString();
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("提示词的「" + key + "」段是空的");
            }
            if (v.length() > 20000) {
                throw new IllegalArgumentException("提示词的「" + key + "」段太长了（超过 2 万字）");
            }
        }
        String identity = sections.get("identity").getAsString();
        for (String ph : new String[]{"{name}", "{profession}", "{personality}", "{speech}"}) {
            if (!identity.contains(ph)) {
                throw new IllegalArgumentException("identity 段里的填空位 " + ph + " 不能删（插件要往这里填村民的名字/职业）");
            }
        }
    }

    private static void validateRecipes(JsonObject root) {
        JsonObject recipes = objectField(root, "recipes", "配方表");
        if (recipes.isEmpty()) throw new IllegalArgumentException("配方表是空的，至少要留一条配方");
        for (var e : recipes.entrySet()) {
            String id = e.getKey();
            if (!e.getValue().isJsonObject()) {
                throw new IllegalArgumentException("配方 " + id + " 应该是一个 { } 对象");
            }
            JsonObject recipe = e.getValue().getAsJsonObject();
            JsonObject materials = objectField(recipe, "materials", "配方 " + id + " 的材料");
            if (materials.isEmpty()) {
                throw new IllegalArgumentException("配方 " + id + " 没有任何材料");
            }
            for (var m : materials.entrySet()) {
                if (!isPositiveNumber(m.getValue())) {
                    throw new IllegalArgumentException(
                            "配方 " + id + " 的材料 " + m.getKey() + " 数量必须是正数");
                }
            }
            if (recipe.has("yields") && !isPositiveNumber(recipe.get("yields"))) {
                throw new IllegalArgumentException("配方 " + id + " 的产出数量必须是正数");
            }
            if (recipe.has("name_zh") && !isNonBlankString(recipe.get("name_zh"))) {
                throw new IllegalArgumentException("配方 " + id + " 的中文名应该是文字");
            }
        }
    }

    private static void validateTags(JsonObject root) {
        JsonObject tags = objectField(root, "tags", "性格标签库");
        if (tags.isEmpty()) throw new IllegalArgumentException("性格标签库是空的，至少要留一个标签");
        for (var e : tags.entrySet()) {
            String id = e.getKey();
            if (!e.getValue().isJsonObject()) {
                throw new IllegalArgumentException("标签 " + id + " 应该是一个 { } 对象");
            }
            JsonObject tag = e.getValue().getAsJsonObject();
            if (!isNonBlankString(tag.get("category"))
                    || !TAG_CATEGORIES.contains(tag.get("category").getAsString())) {
                throw new IllegalArgumentException(
                        "标签 " + id + " 的分类不对，只能是 core / speech / quirks / romance");
            }
            if (!isNonBlankString(tag.get("name_zh"))) {
                throw new IllegalArgumentException("标签 " + id + " 缺中文名（name_zh）");
            }
            if (!isNonBlankString(tag.get("prompt"))) {
                throw new IllegalArgumentException("标签 " + id + " 缺言行提示（prompt）");
            }
            if (tag.has("traits")) {
                if (!tag.get("traits").isJsonObject()) {
                    throw new IllegalArgumentException("标签 " + id + " 的 traits 应该是一个 { } 对象");
                }
                for (var t : tag.getAsJsonObject("traits").entrySet()) {
                    if (!t.getValue().isJsonPrimitive() || !t.getValue().getAsJsonPrimitive().isNumber()) {
                        throw new IllegalArgumentException("标签 " + id + " 的 traits 里只能是数字");
                    }
                }
            }
        }
    }

    private static void validateSurnames(JsonObject root) {
        if (!root.has("surnames") || !root.get("surnames").isJsonArray()) {
            throw new IllegalArgumentException("姓氏库里应该有 surnames 列表");
        }
        var arr = root.getAsJsonArray("surnames");
        if (arr.isEmpty()) throw new IllegalArgumentException("姓氏库是空的，至少要留一个姓");
        for (JsonElement el : arr) {
            if (!el.isJsonObject() || !isNonBlankString(el.getAsJsonObject().get("name"))) {
                throw new IllegalArgumentException("姓氏库里每一条都要有姓氏（name）");
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("weight") && !isPositiveNumber(o.get("weight"))) {
                throw new IllegalArgumentException("姓氏 " + o.get("name").getAsString() + " 的权重必须是正数");
            }
        }
    }

    private static void validateGivenNames(JsonObject root) {
        for (String key : List.of("female", "male")) {
            if (!root.has(key) || !root.get(key).isJsonArray()) {
                throw new IllegalArgumentException("名字用字库里应该有 " + key + " 列表");
            }
            var arr = root.getAsJsonArray(key);
            if (arr.isEmpty()) {
                throw new IllegalArgumentException("名字用字库的 " + key + " 列表是空的");
            }
            for (JsonElement el : arr) {
                if (!isNonBlankString(el)) {
                    throw new IllegalArgumentException("名字用字库的 " + key + " 列表里只能是一个个字");
                }
            }
        }
    }

    private static JsonObject objectField(JsonObject parent, String key, String what) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IllegalArgumentException(what + "里应该有 " + key + " 对象");
        }
        return parent.getAsJsonObject(key);
    }

    private static boolean isPositiveNumber(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()
                && el.getAsDouble() > 0;
    }

    private static boolean isNonBlankString(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                && !el.getAsString().isBlank();
    }
}
