package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 口语化时间换算：钉给模型照着念的版本，不许它自己把 08:10 换算成中文 */
class SpokenClockTest {

    @Test
    void spokenClockFormats() {
        // tick 0 = 06:00 日出；1000 tick = 1 小时
        assertEquals("六点整", PromptBuilder.mcSpokenClock(0));
        assertEquals("八点十分", PromptBuilder.mcSpokenClock(2167));      // 08:10 实测翻车点
        assertEquals("六点二十六分", PromptBuilder.mcSpokenClock(434));   // 06:26
        assertEquals("十二点整", PromptBuilder.mcSpokenClock(6000));       // 正午
        assertEquals("十九点整", PromptBuilder.mcSpokenClock(13000));      // 入夜
        assertEquals("零点零五分", PromptBuilder.mcSpokenClock(18084));    // 00:05
        assertEquals("二十三点五十九分", PromptBuilder.mcSpokenClock(17984));   // 23:59
    }
}
