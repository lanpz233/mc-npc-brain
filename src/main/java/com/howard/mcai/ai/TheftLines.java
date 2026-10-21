package com.howard.mcai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 偷窃被抓的骂人词库 —— 可外置编辑（数据文件夹 theft-lines.json，面板「偷窃台词」页）。
 * <p>
 * 词分五组：first = 初犯骂人，repeat = 惯犯骂人；confused_day / confused_night /
 * confused_rain = 他察觉丢了东西但没看清人时的嘟囔（按当时是白天/夜里/下雨选组，
 * 下雨优先）。一行一句，支持占位符 {物品}（赃物中文名）与 {玩家}。
 * 文件缺失/损坏时用内置默认（与插件最早的内置词池一致），村民永远不会哑巴。
 * <p>
 * 注：面板设置里「被抓台词由模型现编」打开时，模型按人设现场生成；
 * 模型忙/失败/没开时也自动退回本词库。
 */
public final class TheftLines {

    private TheftLines() {
    }

    public static final String FILE_NAME = "theft-lines.json";

    private static final List<String> BUILTIN_FIRST = List.of(
            "哎！你干嘛呢！手往哪儿伸！",
            "好哇，偷到我头上来了？！",
            "抓住你了！大半夜翻我包干嘛！",
            "你你你……把东西放下！");
    private static final List<String> BUILTIN_REPEAT = List.of(
            "又是你！村里都传遍了，你还敢来！",
            "没完了是吧？这事儿我得让全村都知道！",
            "你这名声在村里算是臭了，还敢伸手！",
            "一而再再而三，当我是瞎子吗！");

    private static final List<String> BUILTIN_CONFUSED_DAY = List.of(
            "光天化日之下，谁这么大胆子……",
            "大白天的，我{物品}呢？",
            "青天白日的，我东西还能没了？");
    private static final List<String> BUILTIN_CONFUSED_NIGHT = List.of(
            "大晚上的，谁摸到我这儿来了……",
            "这深更半夜的，我{物品}呢？",
            "黑灯瞎火的，是谁……");
    private static final List<String> BUILTIN_CONFUSED_RAIN = List.of(
            "大雨天都有人偷东西，真是……",
            "冒这么大雨来惦记我{物品}？",
            "外头哗哗下着，还有人摸我的东西……");

    private static volatile List<String> first = BUILTIN_FIRST;
    private static volatile List<String> repeat = BUILTIN_REPEAT;
    private static volatile List<String> confusedDay = BUILTIN_CONFUSED_DAY;
    private static volatile List<String> confusedNight = BUILTIN_CONFUSED_NIGHT;
    private static volatile List<String> confusedRain = BUILTIN_CONFUSED_RAIN;

    private static final List<String> KEYS = List.of(
            "first", "repeat", "confused_day", "confused_night", "confused_rain");

    /** 启动/热重载时调用：读数据文件夹的 theft-lines.json（没有就把内置版拷过去） */
    public static synchronized void load(Path dataFolder) {
        first = BUILTIN_FIRST;
        repeat = BUILTIN_REPEAT;
        confusedDay = BUILTIN_CONFUSED_DAY;
        confusedNight = BUILTIN_CONFUSED_NIGHT;
        confusedRain = BUILTIN_CONFUSED_RAIN;
        try {
            Path file = dataFolder.resolve(FILE_NAME);
            if (!Files.exists(file)) {
                try (InputStream in = TheftLines.class.getResourceAsStream("/" + FILE_NAME)) {
                    if (in != null) Files.write(file, in.readAllBytes());
                }
            }
            if (Files.exists(file)) {
                var parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed != null) {
                    if (!parsed.getOrDefault("first", List.of()).isEmpty()) first = parsed.get("first");
                    if (!parsed.getOrDefault("repeat", List.of()).isEmpty()) repeat = parsed.get("repeat");
                    if (!parsed.getOrDefault("confused_day", List.of()).isEmpty()) confusedDay = parsed.get("confused_day");
                    if (!parsed.getOrDefault("confused_night", List.of()).isEmpty()) confusedNight = parsed.get("confused_night");
                    if (!parsed.getOrDefault("confused_rain", List.of()).isEmpty()) confusedRain = parsed.get("confused_rain");
                }
            }
        } catch (Exception ignored) {
            // 读坏了就用内置版，村民不能没话说
        }
    }

    /** 解析词库 JSON（可单测）：{"first":[...], "repeat":[...]}；坏内容返回 null */
    public static Map<String, List<String>> parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String key : KEYS) {
                List<String> lines = new ArrayList<>();
                if (root.has(key) && root.get(key).isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray(key);
                    for (var el : arr) {
                        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                            String line = el.getAsString().trim();
                            if (!line.isEmpty()) lines.add(line);
                        }
                    }
                }
                out.put(key, lines);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 抽一句“察觉丢东西”的嘟囔：下雨优先，其次夜里，最后白天。
     * 同样支持 {物品}/{玩家} 占位符，池为空退内置。
     */
    public static String pickConfused(boolean rainingHere, boolean night, String itemZh, String playerName) {
        List<String> pool = rainingHere ? confusedRain : night ? confusedNight : confusedDay;
        List<String> builtin = rainingHere ? BUILTIN_CONFUSED_RAIN : night ? BUILTIN_CONFUSED_NIGHT : BUILTIN_CONFUSED_DAY;
        if (pool == null || pool.isEmpty()) pool = builtin;
        String base = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        return base.replace("{物品}", itemZh == null ? "" : itemZh)
                .replace("{玩家}", playerName == null ? "" : playerName);
    }

    /** 抽一句骂人话并填占位符（池为空时退内置） */
    public static String pick(boolean repeatOffender, String itemZh, String playerName) {
        List<String> pool = repeatOffender ? repeat : first;
        if (pool == null || pool.isEmpty()) pool = repeatOffender ? BUILTIN_REPEAT : BUILTIN_FIRST;
        String base = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        String line = base.replace("{物品}", itemZh == null ? "" : itemZh)
                .replace("{玩家}", playerName == null ? "" : playerName);
        if (!line.contains(itemZh == null ? "\u0000" : itemZh) && (itemZh != null && !itemZh.isBlank())) {
            line = line + "（指你刚摸的" + itemZh + "）";
        }
        return line;
    }
}
