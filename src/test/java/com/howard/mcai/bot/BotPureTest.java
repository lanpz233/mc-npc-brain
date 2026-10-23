package com.howard.mcai.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BotPureTest {

    // ===== 小屋图纸 =====

    @Test
    void hutPlanksCount() {
        // 地板 25 + 墙（周长 16×3 − 门洞 2 = 46）+ 屋顶 25 = 96
        assertEquals(96, HutBlueprint.planksNeeded());
    }

    @Test
    void hutInteriorClearAndDoorGap() {
        assertTrue(HutBlueprint.interiorClear());
        List<HutBlueprint.Cell> cells = HutBlueprint.cells();
        // 门洞（2,1,4）与（2,2,4）不应有木板，但有 DOOR 部件在下格
        assertTrue(cells.stream().noneMatch(c -> c.part() == HutBlueprint.Part.PLANK
                && c.dx() == 2 && c.dz() == 4 && (c.dy() == 1 || c.dy() == 2)));
        assertTrue(cells.stream().anyMatch(c -> c.part() == HutBlueprint.Part.DOOR));
        assertTrue(cells.stream().anyMatch(c -> c.part() == HutBlueprint.Part.BED_FOOT));
        assertTrue(cells.stream().anyMatch(c -> c.part() == HutBlueprint.Part.CHEST));
        // 施工顺序：地板先于墙先于顶（首块是地板、屋顶 dy=4 在门之前）
        assertEquals(0, cells.get(0).dy());
    }

    // ===== 存箱取舍 =====

    @Test
    void depositKeepsFoodAndBestTool() {
        Map<String, Integer> inv = Map.of(
                "BREAD", 20,               // 留 16，存 4
                "IRON_PICKAXE", 1,         // 留（最好的镐）
                "STONE_PICKAXE", 1,        // 存（有更好的了）
                "COBBLESTONE", 64,         // 全存
                "TORCH", 20,               // 留 8，存 12
                "WOODEN_SWORD", 2);        // 留 1，存 1
        Map<String, Integer> plan = DepositPlan.toDeposit(inv);
        assertEquals(4, plan.get("BREAD"));
        assertNull(plan.get("IRON_PICKAXE"));
        assertEquals(1, plan.get("STONE_PICKAXE"));
        assertEquals(64, plan.get("COBBLESTONE"));
        assertEquals(12, plan.get("TORCH"));
        assertEquals(1, plan.get("WOODEN_SWORD"));
    }

    @Test
    void depositEmptyAndAllKept() {
        assertTrue(DepositPlan.toDeposit(Map.of()).isEmpty());
        assertTrue(DepositPlan.toDeposit(Map.of("BREAD", 5, "DIAMOND_PICKAXE", 1)).isEmpty());
    }

    @Test
    void depositFoodCapAcrossKinds() {
        Map<String, Integer> inv = Map.of("BREAD", 10, "COOKED_BEEF", 10, "DIRT", 3);
        Map<String, Integer> plan = DepositPlan.toDeposit(inv);
        // 口粮总共留 16：面包 10 全留，牛肉留 6 存 4
        assertNull(plan.get("BREAD"));
        assertEquals(4, plan.get("COOKED_BEEF"));
        assertEquals(3, plan.get("DIRT"));
    }

    // ===== 档案 =====

    @Test
    void stopOrderWords() {
        // v0.7.0 实测事故：玩家说"别跟了"停不下来（词表没有、还被跟随语义包围）
        org.junit.jupiter.api.Assertions.assertTrue(BotOrders.isStopOrder("别跟了"));
        org.junit.jupiter.api.Assertions.assertTrue(BotOrders.isStopOrder("别跟着我了"));
        org.junit.jupiter.api.Assertions.assertTrue(BotOrders.isStopOrder("停"));
        org.junit.jupiter.api.Assertions.assertTrue(BotOrders.isStopOrder("停下吧"));
        org.junit.jupiter.api.Assertions.assertTrue(BotOrders.isStopOrder("原地待着吧"));
        org.junit.jupiter.api.Assertions.assertTrue(BotOrders.isStopOrder("不用跟了"));
        // 不是命令的话不能误判
        org.junit.jupiter.api.Assertions.assertFalse(BotOrders.isStopOrder("跟我来"));
        org.junit.jupiter.api.Assertions.assertFalse(BotOrders.isStopOrder("今天天气不错"));
        org.junit.jupiter.api.Assertions.assertFalse(BotOrders.isStopOrder("不停地夸我一下吧")); // 有"停"字但不是命令
    }

    @Test
    void botStateFavorAndFacts() {
        BotState s = new BotState("Alex", "李小明", "male", "Steve");
        UUID p = UUID.randomUUID();
        assertEquals(0.0, s.favor(p));
        s.addFavor(p, 30);
        s.addFavor(p, 90); // 钳到 100
        assertEquals(100.0, s.favor(p));
        for (int i = 0; i < 15; i++) s.addFact("事" + i);
        assertEquals(12, s.facts.size());
        assertEquals("事3", s.facts.get(0));
        assertFalse(s.hasBase());
        s.setBase("world", 1, 2, 3);
        assertTrue(s.hasBase());
    }
}
