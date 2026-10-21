package com.howard.mcai.mob;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletAmountsTest {

    private static Random fixedRandom(int value) {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    @Test
    void tradeWalletRangeIsTripleTheOldRange() {
        assertEquals(36, WalletAmounts.minimum(VillagerProfession.FARMER));
        assertEquals(90, WalletAmounts.maximum(VillagerProfession.FARMER));
        assertEquals(36, WalletAmounts.seedAmount(VillagerProfession.FARMER, fixedRandom(0)));
        assertEquals(90, WalletAmounts.seedAmount(
                VillagerProfession.FARMER, fixedRandom(90 - 36)));
    }

    @Test
    void nonTradeWalletRangeIsTripleTheOldRange() {
        assertEquals(9, WalletAmounts.minimum(VillagerProfession.NONE));
        assertEquals(30, WalletAmounts.maximum(VillagerProfession.NONE));
        assertEquals(9, WalletAmounts.seedAmount(VillagerProfession.NITWIT, fixedRandom(0)));
        assertEquals(30, WalletAmounts.seedAmount(
                VillagerProfession.NITWIT, fixedRandom(30 - 9)));
    }

    @Test
    void seedAmountAlwaysStaysInRange() {
        Random rng = new Random(20261021L);
        for (int i = 0; i < 500; i++) {
            int trade = WalletAmounts.seedAmount(VillagerProfession.TOOLSMITH, rng);
            int nonTrade = WalletAmounts.seedAmount(VillagerProfession.NONE, rng);
            assertTrue(trade >= 36 && trade <= 90);
            assertTrue(nonTrade >= 9 && nonTrade <= 30);
        }
    }

    @Test
    void topUpOnlyFillsTheGapToTheNewWalletTarget() {
        Random maxRandom = fixedRandom(90 - 36);
        assertEquals(70, WalletAmounts.topUpAmount(VillagerProfession.FARMER, 20, maxRandom));
        assertEquals(0, WalletAmounts.topUpAmount(VillagerProfession.FARMER, 120, maxRandom));
        assertEquals(90, WalletAmounts.topUpAmount(VillagerProfession.FARMER, -5, maxRandom));
    }
}
