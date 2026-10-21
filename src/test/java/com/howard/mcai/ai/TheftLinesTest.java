package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** v0.4.2：偷窃台词库（解析/占位符/场景选组） */
class TheftLinesTest {

    @Test
    void parseGroupsAndSkipBadContent() {
        var parsed = TheftLines.parse(
                "{\"first\":[\"甲\"],\"repeat\":[],\"confused_rain\":[\"雨句\",\"  \"],\"zzz\":[\"不认识的键也收下但加载时不用\"]}");
        assertNotNull(parsed);
        assertEquals(java.util.List.of("甲"), parsed.get("first"));
        assertEquals(java.util.List.of("雨句"), parsed.get("confused_rain"));   // 空行剔掉
        assertEquals(java.util.List.of(), parsed.get("confused_day"));
        assertNull(TheftLines.parse("{oops"));
        assertNull(TheftLines.parse(""));
    }

    @Test
    void pickFillsPlaceholdersAndAppendsItemHint() {
        // 没加载文件时用内置池；内置每句都不含占位符 → 必带"（指你刚摸的面包）"后缀
        String line = TheftLines.pick(false, "面包", "lanpz");
        assertTrue(line.contains("面包"), line);
        String repeat = TheftLines.pick(true, "钻石", "lanpz");
        assertTrue(repeat.contains("钻石"), repeat);
    }

    @Test
    void confusedPoolsByScene() {
        // 下雨优先于夜里
        String rain = TheftLines.pickConfused(true, true, "面包", "lanpz");
        assertTrue(rain.contains("雨") || rain.contains("惦记") || rain.contains("摸"), rain);
        String night = TheftLines.pickConfused(false, true, "面包", "lanpz");
        assertTrue(night.contains("晚上") || night.contains("深更半夜") || night.contains("黑灯瞎火"), night);
        String day = TheftLines.pickConfused(false, false, "面包", "lanpz");
        assertTrue(day.contains("光天化日") || day.contains("大白天") || day.contains("青天白日"), day);
        // {物品} 占位符被填上（夜池里有“我{物品}呢”）
        boolean anyItem = false;
        for (int i = 0; i < 30; i++) {
            if (TheftLines.pickConfused(false, true, "面包", "lanpz").contains("面包")) anyItem = true;
        }
        assertTrue(anyItem);
    }

}
