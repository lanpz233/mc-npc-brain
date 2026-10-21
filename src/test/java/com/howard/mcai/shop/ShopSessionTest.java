package com.howard.mcai.shop;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交易会话：砍价进度跨关窗保留、可多轮砍价（只降不升）、闲置过期。
 */
class ShopSessionTest {

    private static final UUID NPC = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void agreedPriceOverridesListPrice() {
        ShopSession s = new ShopSession(NPC, PLAYER, 1_000L);
        assertEquals(20, s.effectivePrice("IRON_SWORD", 20));
        assertTrue(s.recordBargain("IRON_SWORD", 15));
        assertEquals(15, s.effectivePrice("IRON_SWORD", 20));
        assertEquals(Integer.valueOf(15), s.agreedPrice("IRON_SWORD"));
    }

    @Test
    void bargainMultipleRoundsOnlyDown() {
        ShopSession s = new ShopSession(NPC, PLAYER, 1_000L);
        assertTrue(s.recordBargain("BREAD", 2));
        assertTrue(s.recordBargain("BREAD", 1), "多轮砍价：更低的价可以继续谈下来");
        assertEquals(1, s.effectivePrice("BREAD", 3));
        assertFalse(s.recordBargain("BREAD", 2), "价格只降不升：更高的价不许覆盖回去");
        assertFalse(s.recordBargain("BREAD", 1), "同价重复记也不算新进展");
        assertEquals(1, s.effectivePrice("BREAD", 3));
        assertTrue(s.hasBargained("BREAD"));
        assertFalse(s.hasBargained("IRON_SWORD"));
    }

    @Test
    void raiseMultipleRoundsOnlyUp() {
        ShopSession s = new ShopSession(NPC, PLAYER, 1_000L);
        assertEquals(3, s.effectivePay("BUY#WHITE_WOOLx1", 3));
        assertTrue(s.recordRaise("BUY#WHITE_WOOLx1", 4));
        assertTrue(s.recordRaise("BUY#WHITE_WOOLx1", 5), "多轮抬价：更高的价可以继续谈上去");
        assertEquals(5, s.effectivePay("BUY#WHITE_WOOLx1", 3));
        assertFalse(s.recordRaise("BUY#WHITE_WOOLx1", 4), "价格只升不降：更低的价不许覆盖回去");
        assertFalse(s.recordRaise("BUY#WHITE_WOOLx1", 5), "同价重复记也不算新进展");
        assertEquals(Integer.valueOf(5), s.agreedPay("BUY#WHITE_WOOLx1"));
        // 买卖两个方向各记各的，不串
        assertEquals(20, s.effectivePrice("IRON_SWORD", 20));
        assertEquals(7, s.effectivePay("BUY#BREADx1", 7));
    }

    @Test
    void sessionExpiresAfterIdleTtl() {
        ShopSession s = new ShopSession(NPC, PLAYER, 0L);
        assertFalse(s.expired(ShopSession.IDLE_TTL_MS));
        assertTrue(s.expired(ShopSession.IDLE_TTL_MS + 1));
        s.touch(ShopSession.IDLE_TTL_MS + 1);
        assertFalse(s.expired(ShopSession.IDLE_TTL_MS + 2));
    }

    @Test
    void managerReturnsSameSessionAndPrunes() {
        ShopSessionManager m = new ShopSessionManager();
        ShopSession a = m.getOrCreate(NPC, PLAYER);
        ShopSession b = m.getOrCreate(NPC, PLAYER);
        assertSame(a, b, "同一村民+玩家应拿到同一份会话（关窗重开进度不丢）");
        a.recordBargain("BREAD", 2);
        assertEquals(2, m.get(NPC, PLAYER).effectivePrice("BREAD", 3));

        m.remove(NPC, PLAYER);
        assertNull(m.get(NPC, PLAYER));
        assertEquals(0, m.size());
    }

    @Test
    void managerSeparatesPlayersAndNpcs() {
        ShopSessionManager m = new ShopSessionManager();
        UUID other = UUID.randomUUID();
        m.getOrCreate(NPC, PLAYER).recordBargain("BREAD", 2);
        assertEquals(3, m.getOrCreate(NPC, other).effectivePrice("BREAD", 3),
                "别人的砍价进度不串到其他玩家");
        assertEquals(3, m.getOrCreate(other, PLAYER).effectivePrice("BREAD", 3),
                "别的村民处也没有这份进度");
        m.removeNpc(NPC);
        assertNull(m.get(NPC, PLAYER));
        assertEquals(1, m.size());
    }
}
