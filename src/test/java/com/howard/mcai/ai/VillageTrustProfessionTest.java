package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** v0.4.0：村庄信任账 + 职业设定外置的纯逻辑测试 */
class VillageTrustProfessionTest {

    @Test
    void villageFindOrCreateByRadius() {
        VillageTrust t = new VillageTrust();
        int a = t.villageIdFor("world", 100, 64, 100);
        // 64 格内 → 同一个村
        assertEquals(a, t.villageIdFor("world", 130, 64, 120));
        // 远处 → 新村
        int b = t.villageIdFor("world", 500, 64, 500);
        assertNotEquals(a, b);
        // 不同世界 → 新村
        assertNotEquals(a, t.villageIdFor("world_nether", 100, 64, 100));
        // 只查不建
        assertEquals(a, t.findVillageId("world", 110, 110));
        assertEquals(-1, t.findVillageId("world", 5000, 5000));
    }

    @Test
    void caughtPenaltiesAndWitness() {
        VillageTrust t = new VillageTrust();
        UUID p = UUID.randomUUID();
        int v = t.villageIdFor("world", 0, 64, 0);
        assertEquals(VillageTrust.DEFAULT_SCORE, t.score(p, v));
        // 没目击者：-12
        assertEquals(38, t.onCaught(p, v, false));
        // 有目击者：-18
        assertEquals(20, t.onCaught(p, v, true));
        assertEquals(2, t.caughtCount(p, v));
        // 扣到底是 0，不穿
        t.onCaught(p, v, true);
        t.onCaught(p, v, true);
        assertEquals(0, t.score(p, v));
        // 别人不受影响
        assertEquals(VillageTrust.DEFAULT_SCORE, t.score(UUID.randomUUID(), v));
    }

    @Test
    void tradeRegainDailyCap() {
        VillageTrust t = new VillageTrust();
        UUID p = UUID.randomUUID();
        int v = t.villageIdFor("world", 0, 64, 0);
        t.onCaught(p, v, false);   // 50 → 38
        for (int i = 0; i < 6; i++) t.onTrade(p, v, 100L);
        assertEquals(41, t.score(p, v));   // 每天最多 +3
        t.onTrade(p, v, 101L);            // 换一天又能涨
        assertEquals(42, t.score(p, v));
    }

    @Test
    void trustJsonRoundTrip() {
        VillageTrust t = new VillageTrust();
        UUID p = UUID.randomUUID();
        int v = t.villageIdFor("world", 10, 64, -20);
        t.onCaught(p, v, true);
        VillageTrust back = VillageTrust.fromJson(t.toJson());
        assertNotNull(back);
        assertEquals(32, back.score(p, v));
        assertEquals(1, back.caughtCount(p, v));
        assertEquals(v, back.findVillageId("world", 12, -18));
        assertNull(VillageTrust.fromJson("{bad json"));
        assertNull(VillageTrust.fromJson(""));
    }

    @Test
    void professionDataParseAndFallback() {
        // 内置默认里铁匠的口头禅“叮当”还在（这是服主问起的那个词）
        assertTrue(VillagerProfession.TOOLSMITH.speechNotes.contains("叮当"));
        assertTrue(VillagerProfession.TOOLSMITH.personalityEn.contains("叮当"));
        // 没加载文件时走枚举默认
        assertEquals(VillagerProfession.FARMER.personalityEn, ProfessionData.persona(VillagerProfession.FARMER));

        var parsed = ProfessionData.parse(
                "{\"TOOLSMITH\":{\"speech\":\"粗嗓门（例如，哐当）\"},\"BAD\":{},\"FARMER\":{\"persona\":\"\"}}");
        assertNotNull(parsed);
        assertEquals("粗嗓门（例如，哐当）", parsed.get(VillagerProfession.TOOLSMITH).speech());
        assertNull(parsed.get(VillagerProfession.TOOLSMITH).persona());
        assertFalse(parsed.containsKey(VillagerProfession.FARMER));   // 空字段条目跳过
        assertNull(ProfessionData.parse("not json"));
        assertNull(ProfessionData.parse(""));
    }
}
