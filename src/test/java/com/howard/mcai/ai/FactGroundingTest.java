package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.3.0 事实接地四件纯模块：档位词 / 脚下天气 / 天气账本 / 事实直答 */
class FactGroundingTest {

    // ===== StateTiers =====

    @Test
    void tiersTranslateNumbersToWords() {
        assertEquals("吃撑了", StateTiers.hunger(5));
        assertEquals("不饿", StateTiers.hunger(30));
        assertEquals("有点饿", StateTiers.hunger(50));
        assertEquals("饿了", StateTiers.hunger(80));
        assertEquals("饿得慌", StateTiers.hunger(95));
        assertEquals("精神头十足", StateTiers.energy(5));
        assertEquals("困得睁不开眼", StateTiers.energy(95));
        assertEquals("结了仇", StateTiers.favor(-50));
        assertEquals("生分", StateTiers.favor(0));
        assertEquals("处得不错", StateTiers.favor(40));
        assertEquals("跟亲人一样", StateTiers.favor(95));
        assertEquals("身子骨硬朗", StateTiers.health(95));
        assertEquals("伤得很重", StateTiers.health(20));
        assertEquals("饿得慌", StateTiers.needTier("hunger", 90));
        assertEquals("", StateTiers.needTier("unknown", 90));
    }

    // ===== LocalWeather =====

    @Test
    void localWeatherRespectsBiomes() {
        // 沙漠：世界暴雨也落不到它头上
        assertEquals(LocalWeather.Kind.CLEAR,
                LocalWeather.resolve("NORMAL", "DESERT", true, true));
        // 雪原：降水是雪，打雷是雪+雷
        assertEquals(LocalWeather.Kind.SNOW,
                LocalWeather.resolve("NORMAL", "SNOWY_PLAINS", true, false));
        assertEquals(LocalWeather.Kind.THUNDER_SNOW,
                LocalWeather.resolve("NORMAL", "FROZEN_PEAKS", true, true));
        // 平原：正常雨/雷雨
        assertEquals(LocalWeather.Kind.RAIN,
                LocalWeather.resolve("NORMAL", "PLAINS", true, false));
        assertEquals(LocalWeather.Kind.THUNDER_RAIN,
                LocalWeather.resolve("NORMAL", "PLAINS", true, true));
        // 没开天气开关：一律晴
        assertEquals(LocalWeather.Kind.CLEAR,
                LocalWeather.resolve("NORMAL", "PLAINS", false, false));
        // 下界/末地：没有天气
        assertEquals(LocalWeather.Kind.NONE,
                LocalWeather.resolve("NETHER", "NETHER_WASTES", true, true));
        assertEquals(LocalWeather.Kind.NONE,
                LocalWeather.resolve("THE_END", "THE_END", true, false));
    }

    // ===== WeatherLedger =====

    @Test
    void ledgerRecordsAndSummarizes() {
        WeatherLedger l = new WeatherLedger();
        long day1Morning = 2000;                    // 第 0 天 08:00
        l.record(day1Morning, true, false);         // 开始下
        l.record(day1Morning + 1500, true, true);    // 打雷了
        l.record(day1Morning + 3000, false, false);  // 停了（第 0 天 11:00）
        assertEquals(1, l.spanCount());
        long day2 = 24000 + 2000;                    // 第 1 天早上看账本
        String s = l.summaryZh(day2, false, false);
        assertTrue(s.contains("昨天"), s);
        assertTrue(s.contains("下过一阵雨"), s);
        assertTrue(s.contains("打过雷"), s);
        // 沙漠村民看同一本账：得注明那是别处下的
        String dry = l.summaryZh(day2, false, true);
        assertTrue(dry.contains("别处下"), dry);
        // 雪原村民：过去下的是雪
        String snowy = l.summaryZh(day2, true, false);
        assertTrue(snowy.contains("下过一阵雪"), snowy);
        // 空账本：明确说没下过，不给编造留口
        String empty = new WeatherLedger().summaryZh(day2, false, false);
        assertTrue(empty.contains("一直没下过雨"), empty);
        // 序列化往返
        WeatherLedger back = WeatherLedger.fromJsonString(l.toJsonString());
        assertEquals(1, back.spanCount());
    }

    @Test
    void ledgerPrunesIntoCompressedCounts() {
        WeatherLedger l = new WeatherLedger();
        l.record(0, true, true);
        l.record(1000, false, false);
        long now = 24000L * 10; // 第 10 天，保留 7 天 → 第 0 天的场次被压缩
        l.prune(now, 7);
        assertEquals(0, l.spanCount());
        assertEquals(1, l.compressedSpans());
        String s = l.summaryZh(now, false, false);
        assertTrue(s.contains("更早之前还下过 1 场"), s);
    }

    // ===== FactAnswers =====

    private static FactAnswers.Facts facts(long tick, LocalWeather.Kind w) {
        return new FactAnswers.Facts(tick, w, false, false, 6000,
                "有点饿", "还算精神", "有点闲", "身子骨硬朗", 10);
    }

    @Test
    void detectionIsScopedToShortFactQuestions() {
        assertEquals(EnumSet.of(FactAnswers.Kind.TIME_EXACT), FactAnswers.detect("几点了"));
        // 服主实测漏网句式（v0.3.0）：“现在是什么时候”曾漏给模型，报出“三点零九分”（实际 18:13）
        assertEquals(EnumSet.of(FactAnswers.Kind.TIME_EXACT), FactAnswers.detect("现在是什么时候"));
        assertEquals(EnumSet.of(FactAnswers.Kind.TIME_EXACT), FactAnswers.detect("什么时辰了"));
        assertEquals(EnumSet.of(FactAnswers.Kind.TIME_PERIOD), FactAnswers.detect("天黑了没"));
        assertEquals(EnumSet.of(FactAnswers.Kind.WEATHER_NOW), FactAnswers.detect("有没有下雨"));
        assertEquals(EnumSet.of(FactAnswers.Kind.RAIN_FORECAST), FactAnswers.detect("这雨什么时候停啊"));
        assertEquals(EnumSet.of(FactAnswers.Kind.SELF_STATE), FactAnswers.detect("你饿不饿"));
        // 复合问句（服主实测原话）：时间和天气都接
        EnumSet<FactAnswers.Kind> both = FactAnswers.detect("几点了，有没有下雨");
        assertTrue(both.contains(FactAnswers.Kind.TIME_EXACT));
        assertTrue(both.contains(FactAnswers.Kind.WEATHER_NOW));
        // 叙事句、闲聊句不接管
        assertTrue(FactAnswers.detect("我昨天三点就去河边钓鱼了，那条鱼可真大").isEmpty());
        assertTrue(FactAnswers.detect("你做的面包真好吃").isEmpty());
        assertTrue(FactAnswers.detect("几点了" + "啊".repeat(40)).isEmpty()); // 超长不接管
    }

    @Test
    void approxClockRoundsLikeAPerson() {
        assertEquals("六点整", FactAnswers.approxClock(0, 10));            // 06:00
        assertEquals("八点十分左右", FactAnswers.approxClock(2167, 10));  // 08:10
        assertEquals("八点半左右", FactAnswers.approxClock(2500, 10));      // 08:30
        assertEquals("八点四十分左右", FactAnswers.approxClock(2683, 10));  // 08:40
        assertEquals("快九点了", FactAnswers.approxClock(2850, 10));        // 08:51
    }

    @Test
    void composeAnswersFactsDirectly() {
        // 15:55 晴天：精确问法 → 时段+约数；天气问法 → 没下雨
        long t1555 = 9917;
        String time = FactAnswers.compose(EnumSet.of(FactAnswers.Kind.TIME_EXACT),
                facts(t1555, LocalWeather.Kind.CLEAR));
        assertTrue(time.contains("下午"), time);
        assertTrue(time.contains("快四点"), time); // 15:55 → 快十六点？不：15:55 快到 16 点
        String weather = FactAnswers.compose(EnumSet.of(FactAnswers.Kind.WEATHER_NOW),
                facts(t1555, LocalWeather.Kind.CLEAR));
        assertEquals("晴着呢，没下雨。", weather);
        String rain = FactAnswers.compose(EnumSet.of(FactAnswers.Kind.WEATHER_NOW),
                facts(t1555, LocalWeather.Kind.THUNDER_RAIN));
        assertTrue(rain.contains("暴雨"), rain);
        // 时段问法：按太阳位置说，不报点
        String period = FactAnswers.compose(EnumSet.of(FactAnswers.Kind.TIME_PERIOD),
                facts(t1555, LocalWeather.Kind.CLEAR));
        assertTrue(period.contains("下午"), period);
        assertFalse(period.contains("点"), period);
        // 已入夜问离天黑
        String dark = FactAnswers.compose(EnumSet.of(FactAnswers.Kind.DARK_SOON),
                facts(14000, LocalWeather.Kind.CLEAR));
        assertTrue(dark.contains("已经天黑了"), dark);
    }
}
