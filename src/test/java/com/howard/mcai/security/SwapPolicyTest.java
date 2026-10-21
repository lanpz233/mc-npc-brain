package com.howard.mcai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交换公平裁决：陌生人不许占便宜，交情越深 NPC 越肯吃亏，神使豁免。
 * 价值口径与 PermissionManager.valueOf 一致（普通 10 / 铁级 100 / 钻石级 400）。
 */
class SwapPolicyTest {

    @Test
    void ratioTiersByFavor() {
        assertEquals(1.00, SwapPolicy.minRatio(0));
        assertEquals(1.00, SwapPolicy.minRatio(9.9));
        assertEquals(0.90, SwapPolicy.minRatio(10));
        assertEquals(0.75, SwapPolicy.minRatio(30));
        assertEquals(0.50, SwapPolicy.minRatio(60));
        assertEquals(0.50, SwapPolicy.minRatio(100));
    }

    @Test
    void strangerMustMatchFullValue() {
        // 1 个面包(10) 换 1 把铁剑(100)：陌生人不行，等价的 10 个面包(100)可以
        assertFalse(SwapPolicy.isFair(10, 100, 0, false));
        assertTrue(SwapPolicy.isFair(100, 100, 0, false));
        // 等值普通交换永远可以
        assertTrue(SwapPolicy.isFair(10, 10, 0, false));
    }

    @Test
    void confidantMayTakeHalfLoss() {
        // 亲信：出价达到一半即可（50 对 100）
        assertTrue(SwapPolicy.isFair(50, 100, 60, false));
        assertFalse(SwapPolicy.isFair(49, 100, 60, false));
        // 熟人(30)：需要 3/4（75 对 100）
        assertTrue(SwapPolicy.isFair(75, 100, 30, false));
        assertFalse(SwapPolicy.isFair(74, 100, 30, false));
    }

    @Test
    void envoyAlwaysFair() {
        assertTrue(SwapPolicy.isFair(0, 400, 0, true));
    }

    @Test
    void zeroNpcValueAlwaysFair() {
        assertTrue(SwapPolicy.isFair(0, 0, 0, false));
    }
}
