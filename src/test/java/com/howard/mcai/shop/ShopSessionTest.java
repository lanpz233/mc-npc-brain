package com.howard.mcai.shop;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交易会话：砍价进度跨关窗保留、一商品只砍一次、闲置过期。
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
    void bargainOnlyOncePerItemPerSession() {
        ShopSession s = new ShopSession(NPC, PLAYER, 1_000L);
        assertTrue(s.recordBargain("BREAD", 2));
        assertFalse(s.recordBargain("BREAD", 1), "同一商品一会话只砍一次，第二次不覆盖");
        assertEquals(2, s.effectivePrice("BREAD", 3));
        assertTrue(s.hasBargained("BREAD"));
        assertFalse(s.hasBargained("IRON_SWORD"));
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
