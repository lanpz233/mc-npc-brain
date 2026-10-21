package com.howard.mcai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 砍价确定性裁决：整数金额、插件守底线。
 * 玩家在界面自选砍几块；在让价额度内按玩家价成交，超了还价到底线价。
 */
class BargainPolicyTest {

    // ===== maxCut 档位 =====

    @Test
    void strangerCutIsOne() {
        assertEquals(1, BargainPolicy.maxCut(20, 0));
        assertEquals(1, BargainPolicy.maxCut(20, 9.9));
    }

    @Test
    void familiarCutScalesWithPrice() {
        // 熟人（10–29）：1 + 标价/5
        assertEquals(1 + 4, BargainPolicy.maxCut(20, 10));
        assertEquals(1 + 4, BargainPolicy.maxCut(20, 29));
    }

    @Test
    void friendAndConfidantCutMore() {
        // 交情（30–59）：2 + 标价/4
        assertEquals(2 + 5, BargainPolicy.maxCut(20, 30));
        // 亲信（≥60）：3 + 标价/3
        assertEquals(3 + 6, BargainPolicy.maxCut(20, 60));
        assertEquals(3 + 6, BargainPolicy.maxCut(20, 500));
    }

    @Test
    void cutNeverDropsPriceBelowOne() {
        assertEquals(0, BargainPolicy.maxCut(1, 100));
        assertEquals(1, BargainPolicy.maxCut(2, 100));
        assertEquals(1, BargainPolicy.floorPrice(2, 100));
    }

    // ===== decide =====

    @Test
    void noBargainAcceptsListPrice() {
        var out = BargainPolicy.decide(20, 0, 0);
        assertTrue(out.accepted());
        assertEquals(20, out.agreedPrice());
    }

    @Test
    void cutWithinAllowanceAcceptedAtPlayerPrice() {
        var out = BargainPolicy.decide(20, 0, 1);   // 生人最多让 1
        assertTrue(out.accepted());
        assertEquals(19, out.agreedPrice());
        assertEquals(19, out.floorPrice());
    }

    @Test
    void cutBeyondAllowanceCountersAtFloor() {
        var out = BargainPolicy.decide(20, 0, 10);  // 想砍 10，生人只让 1
        assertFalse(out.accepted());
        assertEquals(19, out.agreedPrice());       // 还价到底线 19
        assertEquals(19, out.floorPrice());
    }

    @Test
    void confidantCanTakeBiggerCut() {
        var out = BargainPolicy.decide(30, 60, 13); // 亲信让价上限 3+10=13
        assertTrue(out.accepted());
        assertEquals(17, out.agreedPrice());
    }

    @Test
    void bargainOnOneEmeraldItemNeverAcceptedBelowOne() {
        var out = BargainPolicy.decide(1, 100, 1);
        assertFalse(out.accepted());
        assertEquals(1, out.agreedPrice());
    }

    // ===== 卖货方向：抬价裁决（与砍价同档位镜像） =====

    @Test
    void raiseTiersMirrorCutTiers() {
        assertEquals(1, BargainPolicy.maxRaise(20, 0));
        assertEquals(1 + 4, BargainPolicy.maxRaise(20, 10));
        assertEquals(2 + 5, BargainPolicy.maxRaise(20, 30));
        assertEquals(3 + 6, BargainPolicy.maxRaise(20, 60));
        assertEquals(20 + 1, BargainPolicy.maxPay(20, 0));
    }

    @Test
    void decideSellAcceptsWithinCap() {
        var out = BargainPolicy.decideSell(20, 0, 1);
        assertTrue(out.accepted());
        assertEquals(21, out.agreedPay());
    }

    @Test
    void decideSellCountersAtCapWhenOver() {
        var out = BargainPolicy.decideSell(20, 0, 5);
        assertFalse(out.accepted());
        assertEquals(21, out.agreedPay(), "抬超了还价到他肯出的最高价");
        assertEquals(21, out.maxPay());
    }

    @Test
    void decideSellZeroRaiseIsListPay() {
        var out = BargainPolicy.decideSell(20, 60, 0);
        assertTrue(out.accepted());
        assertEquals(20, out.agreedPay());
    }
}
