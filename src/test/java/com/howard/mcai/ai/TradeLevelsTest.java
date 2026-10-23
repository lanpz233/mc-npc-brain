package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 买卖等级回归（Fong 2026-10-23，仿原版）：交易攒笔数升级、出生随机但 4 级不出生。
 */
class TradeLevelsTest {

    @Test
    void levelThresholds() {
        assertEquals(0, TradeLevels.levelForTrades(0));
        assertEquals(0, TradeLevels.levelForTrades(5));
        assertEquals(1, TradeLevels.levelForTrades(6));
        assertEquals(1, TradeLevels.levelForTrades(15));
        assertEquals(2, TradeLevels.levelForTrades(16));
        assertEquals(2, TradeLevels.levelForTrades(31));
        assertEquals(3, TradeLevels.levelForTrades(32));
        assertEquals(3, TradeLevels.levelForTrades(55));
        assertEquals(4, TradeLevels.levelForTrades(56));
        assertEquals(4, TradeLevels.levelForTrades(10000));
    }

    @Test
    void initialLevelNeverFourAndMostlyLow() {
        Random rng = new Random(42);
        int[] counts = new int[5];
        for (int i = 0; i < 20000; i++) {
            int lvl = TradeLevels.initialLevel(rng);
            assertTrue(lvl >= 0 && lvl <= 3, "出生等级必须在 0~3，实际 " + lvl);
            counts[lvl]++;
        }
        assertEquals(0, counts[4], "4 级不许出生，只能靠交易养出来");
        assertTrue(counts[0] > counts[1] && counts[1] > counts[2] && counts[2] > counts[3],
                "出生分布应递减：0 级最多、3 级极少，实际 "
                        + counts[0] + "/" + counts[1] + "/" + counts[2] + "/" + counts[3]);
    }

    @Test
    void tradesToNext() {
        assertEquals(6, TradeLevels.tradesToNextLevel(0, 0));
        assertEquals(10, TradeLevels.tradesToNextLevel(6, 1));
        // 出生 3 级的老师傅：没成交过，离 4 级还差满门槛
        assertEquals(56, TradeLevels.tradesToNextLevel(0, 3));
        assertEquals(-1, TradeLevels.tradesToNextLevel(100, 4));
    }
}
