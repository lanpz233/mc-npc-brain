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
            new DataFile("given_names.json", "名字用字库", "村民名字用字，按男女分开")
    );

    private static final Set<String> NAMES = Set.of(
            "recipes.json", "personality_tags.json", "surnames.json", "given_names.json");

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
            default -> throw new IllegalArgumentException("不认识的数据文件：" + name);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
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
