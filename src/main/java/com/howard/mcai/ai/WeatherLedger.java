package com.howard.mcai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 天气账本 —— 插件给全村记的天气历史账（2026-10-22，服主定）。
 * <p>
 * 起因：村民闲聊爱编天气历史（“昨天晚上下雨打雷”），而插件从没给过它们任何
 * 天气历史，只能凭感觉编。对策与报时同理：事实由插件记账 —— 每个世界的
 * 降水开始/结束、是否打雷，按游戏时间记下来存盘，注入上下文当共同记忆，
 * 村民聊到过去的雨雪必须照账本说。
 * <p>
 * 保留策略：默认留最近 7 个游戏日（可配）；更早的场次不直接删，压进
 * 汇总计数（下过几场、几场打雷），摘要里以一句话带过。
 * <p>
 * 账本记的是**世界级**事件（原版天气开关全世界统一）；落到某个村民头上是
 * 雨是雪还是根本没下，由 {@link LocalWeather} 按他脚下群系在渲染时换算。
 * 纯逻辑（只吃世界全程刻度），采样与落盘由插件调度层负责。
 */
public final class WeatherLedger {

    /** 一场降水：[start, end] 为世界全程刻度（fullTime），end=-1 表示还在下 */
    public static final class Span {
        public long start;
        public long end;
        public boolean thunder;

        public Span(long start, long end, boolean thunder) {
            this.start = start;
            this.end = end;
            this.thunder = thunder;
        }
    }

    private final List<Span> spans = new ArrayList<>();
    private long compressedSpans;
    private long compressedThunderSpans;

    /** 采样入口：调度层周期性把当前世界天气喂进来，账本自己维护起止 */
    public synchronized void record(long nowFull, boolean storm, boolean thundering) {
        Span open = openSpan();
        if (storm) {
            if (open == null) {
                spans.add(new Span(nowFull, -1, thundering));
            } else if (thundering) {
                open.thunder = true;
            }
        } else if (open != null) {
            open.end = nowFull;
        }
    }

    private Span openSpan() {
        if (spans.isEmpty()) return null;
        Span last = spans.get(spans.size() - 1);
        return last.end < 0 ? last : null;
    }

    /** 把超出保留期的场次压进汇总计数（保留期按游戏日计） */
    public synchronized void prune(long nowFull, int retentionDays) {
        long cutoff = nowFull - Math.max(1, retentionDays) * 24000L;
        var it = spans.iterator();
        while (it.hasNext()) {
            Span s = it.next();
            long ref = s.end >= 0 ? s.end : s.start;
            if (ref < cutoff) {
                compressedSpans++;
                if (s.thunder) compressedThunderSpans++;
                it.remove();
            }
        }
    }

    /** 当前是否有一场还没停的降水（调度层据此判断“刚开始下”） */
    public synchronized boolean hasOpenSpan() {
        return openSpan() != null;
    }

    public synchronized int spanCount() {
        return spans.size();
    }

    public synchronized long compressedSpans() {
        return compressedSpans;
    }

    // ===== 摘要渲染 =====

    /** 一天内的刻度 → "HH:MM"（与 PromptBuilder.mcClock 同公式，本类不依赖它以保持纯净） */
    static String clockOf(long full) {
        long t = ((full % 24000) + 24000) % 24000;
        int hour = (int) ((t / 1000 + 6) % 24);
        int minute = (int) ((t % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hour, minute);
    }

    private static String dayLabel(long spanFull, long nowFull) {
        long diff = nowFull / 24000 - spanFull / 24000;
        if (diff <= 0) return "今天";
        if (diff == 1) return "昨天";
        if (diff == 2) return "前天";
        return diff + "天前";
    }

    /**
     * 给村民看的账本摘要（一两行）。
     *
     * @param viewerSnowy 读账村民在雪系群系（过去的降水对他来说是雪）
     * @param viewerDry   读账村民在干旱群系（过去的降水他那儿根本没下）
     */
    public synchronized String summaryZh(long nowFull, boolean viewerSnowy, boolean viewerDry) {
        String precip = viewerSnowy ? "雪" : "雨";
        StringBuilder sb = new StringBuilder("天气账本（全村共同记忆，聊到过去的天气照这个说）：");
        boolean any = false;
        // 最近的场次优先，最多列 4 场
        int shown = 0;
        for (int i = spans.size() - 1; i >= 0 && shown < 4; i--, shown++) {
            Span s = spans.get(i);
            any = true;
            sb.append("\n· ").append(dayLabel(s.start, nowFull)).append(' ').append(clockOf(s.start));
            if (s.end < 0) {
                sb.append(" 开始一直在下");
            } else {
                sb.append("–").append(clockOf(s.end)).append(" 下过一阵");
            }
            if (viewerDry) {
                sb.append("（那是别处下的，你这边干旱、没下）");
            } else {
                sb.append(precip);
            }
            if (s.thunder) sb.append("，打过雷");
            sb.append("。");
        }
        if (compressedSpans > 0) {
            any = true;
            sb.append("\n· 更早之前还下过 ").append(compressedSpans).append(" 场");
            if (!viewerDry) sb.append(precip);
            if (compressedThunderSpans > 0) {
                sb.append("，其中 ").append(compressedThunderSpans).append(" 场打过雷");
            }
            sb.append("。");
        }
        if (!any) {
            sb.append("\n· 最近这些天一直没下过").append(viewerDry ? "雨" : precip).append("。");
        }
        return sb.toString();
    }

    // ===== 序列化（落盘用） =====

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Span s : spans) {
            JsonObject so = new JsonObject();
            so.addProperty("s", s.start);
            so.addProperty("e", s.end);
            so.addProperty("t", s.thunder);
            arr.add(so);
        }
        o.add("spans", arr);
        o.addProperty("cs", compressedSpans);
        o.addProperty("ct", compressedThunderSpans);
        return o;
    }

    public static WeatherLedger fromJson(JsonObject o) {
        WeatherLedger l = new WeatherLedger();
        if (o == null) return l;
        if (o.has("spans") && o.get("spans").isJsonArray()) {
            for (var el : o.getAsJsonArray("spans")) {
                JsonObject so = el.getAsJsonObject();
                l.spans.add(new Span(
                        so.has("s") ? so.get("s").getAsLong() : 0,
                        so.has("e") ? so.get("e").getAsLong() : -1,
                        so.has("t") && so.get("t").getAsBoolean()));
            }
        }
        if (o.has("cs")) l.compressedSpans = o.get("cs").getAsLong();
        if (o.has("ct")) l.compressedThunderSpans = o.get("ct").getAsLong();
        return l;
    }

    public String toJsonString() {
        return toJson().toString();
    }

    public static WeatherLedger fromJsonString(String json) {
        if (json == null || json.isBlank()) return new WeatherLedger();
        try {
            return fromJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return new WeatherLedger();
        }
    }
}
