package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打劫意志（RobberyWill）曲线性质 —— Fong 定稿口径：
 * 恐惧没起来绝不破防；恐惧越高越容易破防；胆量越大越难破防；
 * 最倔的人恐惧拉满也留得住“宁死不屈”的可能；最怂的人吓几句就崩。
 */
class RobberyWillTest {

    @Test
    void noFearNoBreak() {
        assertEquals(0.0, RobberyWill.breakChance(0, 15));
        assertEquals(0.0, RobberyWill.breakChance(5, 15));
        assertEquals(0.0, RobberyWill.breakChance(5, 98));
    }

    @Test
    void chanceRisesWithFear() {
        assertTrue(RobberyWill.breakChance(30, 50) < RobberyWill.breakChance(60, 50));
        assertTrue(RobberyWill.breakChance(60, 50) < RobberyWill.breakChance(90, 50));
    }

    @Test
    void chanceFallsWithCourage() {
        assertTrue(RobberyWill.breakChance(70, 20) > RobberyWill.breakChance(70, 90));
    }

    @Test
    void stubbornCanHoldAtMaxFear() {
        // 胆量 98 的倔种：恐惧 100 也只有不到一半概率破防 —— 死扛到底真实存在
        assertTrue(RobberyWill.breakChance(100, 98) <= 0.5);
    }

    @Test
    void timidBreaksEasily() {
        // 胆量 20 的胆小鬼：恐惧才 50 就大概率破防
        assertTrue(RobberyWill.breakChance(50, 20) >= 0.6);
    }

    @Test
    void chanceCappedBelowOne() {
        assertTrue(RobberyWill.breakChance(100, 15) <= 0.95);
    }

    @Test
    void courageRollInRange() {
        Random rnd = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            int c = RobberyWill.rollCourage(rnd);
            assertTrue(c >= 15 && c <= 98, "胆量越界: " + c);
        }
    }

    @Test
    void courageDistributionMostlyOrdinary() {
        // 一万次抽签：普通胆量（40~65）应占大头，倔种（86+）是少数
        Random rnd = new Random(7);
        int ordinary = 0;
        int stubborn = 0;
        for (int i = 0; i < 10_000; i++) {
            int c = RobberyWill.rollCourage(rnd);
            if (c >= 40 && c <= 65) ordinary++;
            if (c >= 86) stubborn++;
        }
        assertTrue(ordinary > 4_000, "普通胆量应占大头: " + ordinary);
        assertTrue(stubborn < 1_000, "倔种应是少数: " + stubborn);
    }

    @Test
    void tierWordsPresent() {
        assertFalse(RobberyWill.courageTierZh(20).isBlank());
        assertFalse(RobberyWill.courageTierZh(95).isBlank());
        assertFalse(RobberyWill.fearTierZh(10).isBlank());
        assertFalse(RobberyWill.fearTierZh(90).isBlank());
    }
}
