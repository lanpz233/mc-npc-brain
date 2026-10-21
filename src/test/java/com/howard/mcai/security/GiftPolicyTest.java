package com.howard.mcai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 送礼确定性裁决：同一局面永远同一结果，逐条覆盖 GiftPolicy 的规矩。
 * 价值口径：普通=10，稀有线=100（铁级），顶级=400（钻石级）。
 */
class GiftPolicyTest {

    private static final int COMMON = 10;
    private static final int RARE = 100;

    // ===== willingToGive（索要登记时的裁决） =====

    @Test
    void begStrangerDeniedCommon() {
        assertFalse(GiftPolicy.willingToGive(0, COMMON, false, false));
        assertFalse(GiftPolicy.willingToGive(9.9, COMMON, false, false));
        assertTrue(GiftPolicy.willingToGive(10, COMMON, false, false));
    }

    @Test
    void begRareNeedsConfidantFavor() {
        assertFalse(GiftPolicy.willingToGive(59, RARE, false, false));
        assertTrue(GiftPolicy.willingToGive(60, RARE, false, false));
    }

    @Test
    void begReclaimAndEnvoyAlwaysWilling() {
        assertTrue(GiftPolicy.willingToGive(0, RARE, false, true));
        assertTrue(GiftPolicy.willingToGive(0, RARE, true, false));
    }

    // ===== decide（give_item 执行裁决） =====

    @Test
    void spontaneousCommonGiftCappedAtThree() {
        var v = GiftPolicy.decide(false, 0, false, 10, 64, 50, COMMON, false, 0, false);
        assertTrue(v.allowed());
        assertEquals(3, v.giveCount());
    }

    @Test
    void spontaneousCommonGiftBelowFavorDenied() {
        var v = GiftPolicy.decide(false, 0, false, 1, 5, 5, COMMON, false, 0, false);
        assertFalse(v.allowed());
    }

    @Test
    void spontaneousRareGiftOnePerDay() {
        var ok = GiftPolicy.decide(false, 0, false, 1, 2, 80, RARE, false, 0, false);
        assertTrue(ok.allowed());
        assertEquals(1, ok.giveCount());
        var again = GiftPolicy.decide(false, 0, false, 1, 2, 80, RARE, false, 0, true);
        assertFalse(again.allowed());
    }

    @Test
    void insufficientStockDeniedWhole() {
        var v = GiftPolicy.decide(false, 0, false, 5, 2, 50, COMMON, false, 0, false);
        assertFalse(v.allowed());
    }

    @Test
    void pinnedApprovedHonoredBeyondSingleCap() {
        // GUI/聊天索要已批 10 个普通物品：兑现时不再套单次上限、也不再查好感
        var v = GiftPolicy.decide(false, 10, true, 1, 20, 0, COMMON, false, 0, false);
        assertTrue(v.allowed());
        assertEquals(10, v.giveCount());
    }

    @Test
    void pinnedRejectedStaysRejected() {
        // 登记时裁决不给，模型改口答应也不算数
        var v = GiftPolicy.decide(false, 1, false, 1, 5, 0, COMMON, false, 0, false);
        assertFalse(v.allowed());
    }

    @Test
    void foodReserveKeepsFour() {
        // 库存食物总共 6 个、要送 5 个面包：压到 2 个（留 4 个口粮）
        var v = GiftPolicy.decide(false, 0, false, 5, 6, 50, COMMON, true, 6, false);
        assertTrue(v.allowed());
        assertEquals(2, v.giveCount());
        // 只剩 4 个口粮：一个也不能送
        var none = GiftPolicy.decide(false, 0, false, 1, 4, 50, COMMON, true, 4, false);
        assertFalse(none.allowed());
    }

    @Test
    void envoyExemptFromEverything() {
        var v = GiftPolicy.decide(true, 0, false, 64, 0, 0, 400, false, 0, true);
        assertTrue(v.allowed());
        assertEquals(64, v.giveCount());
    }

    @Test
    void deterministicSameInputSameVerdict() {
        var a = GiftPolicy.decide(false, 0, false, 2, 5, 30, COMMON, false, 0, false);
        var b = GiftPolicy.decide(false, 0, false, 2, 5, 30, COMMON, false, 0, false);
        assertEquals(a, b);
    }
}
