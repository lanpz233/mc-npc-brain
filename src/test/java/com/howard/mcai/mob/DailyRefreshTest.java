package com.howard.mcai.mob;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 背包每日刷新回归（Fong 2026-10-23 实测：背包塞满杂物却没吃的、饥饿 100）：
 * 口粮永远保底、钱与私人物品分毫不动、职业货总量封顶不再爆仓。
 */
class DailyRefreshTest {

    private static final Set<String> FOODS = Set.of("BREAD", "APPLE", "CARROT");
    private static final Set<String> GOODS = Set.of("IRON_INGOT", "IRON_PICKAXE", "COAL");
    private static final Set<String> SUNDRIES = Set.of("BONE", "STRING", "FEATHER");

    private static int foodTotal(Map<String, Integer> inv) {
        return inv.entrySet().stream()
                .filter(e -> FOODS.contains(e.getKey())).mapToInt(Map.Entry::getValue).sum();
    }

    @Test
    void emptyBackpackGetsRation() {
        var r = DailyRefresh.refresh(Map.of(), FOODS, GOODS, SUNDRIES, new Random(1));
        assertEquals(DailyRefresh.FOOD_FLOOR, foodTotal(r.inventory()));
        assertEquals(DailyRefresh.FOOD_FLOOR,
                (int) r.inventory().getOrDefault("BREAD", 0));
    }

    @Test
    void starvingVillagerToppedUp() {
        // 复刻 Fong 抓到的现场：背包塞满职业货、一点吃的没有
        Map<String, Integer> inv = new HashMap<>();
        inv.put("IRON_INGOT", 40);
        inv.put("IRON_PICKAXE", 30);
        inv.put("COAL", 26);
        var r = DailyRefresh.refresh(inv, FOODS, GOODS, SUNDRIES, new Random(7));
        assertTrue(foodTotal(r.inventory()) >= DailyRefresh.FOOD_FLOOR,
                "刷新后必须有保底口粮，实际食物总数 " + foodTotal(r.inventory()));
    }

    @Test
    void moneyAndPrivateItemsUntouched() {
        Map<String, Integer> inv = new HashMap<>();
        inv.put("EMERALD", 37);
        inv.put("DIAMOND_CHESTPLATE", 1);   // 不属于任何名单 = 私人物品
        inv.put("WRITTEN_BOOK", 2);
        for (long seed = 0; seed < 50; seed++) {
            var r = DailyRefresh.refresh(inv, FOODS, GOODS, SUNDRIES, new Random(seed));
            assertEquals(37, (int) r.inventory().getOrDefault("EMERALD", 0), "钱包不许动");
            assertEquals(1, (int) r.inventory().getOrDefault("DIAMOND_CHESTPLATE", 0));
            assertEquals(2, (int) r.inventory().getOrDefault("WRITTEN_BOOK", 0));
        }
    }

    @Test
    void goodsCappedNoMoreHoarding() {
        Map<String, Integer> inv = new HashMap<>();
        inv.put("IRON_INGOT", 60);
        inv.put("COAL", 50);
        inv.put("BONE", 30);
        var r = DailyRefresh.refresh(inv, FOODS, GOODS, SUNDRIES, new Random(3));
        int goods = r.inventory().entrySet().stream()
                .filter(e -> GOODS.contains(e.getKey()) || SUNDRIES.contains(e.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        assertTrue(goods <= DailyRefresh.GOODS_CAP,
                "职业货+杂物必须封顶，实际 " + goods);
    }

    @Test
    void foodCappedToo() {
        var r = DailyRefresh.refresh(Map.of("BREAD", 64, "APPLE", 20),
                FOODS, GOODS, SUNDRIES, new Random(5));
        assertTrue(foodTotal(r.inventory()) <= DailyRefresh.FOOD_CAP,
                "口粮也要封顶，实际 " + foodTotal(r.inventory()));
        assertTrue(foodTotal(r.inventory()) >= DailyRefresh.FOOD_FLOOR);
    }

    @Test
    void invariantsHoldAcrossSeeds() {
        Map<String, Integer> inv = new HashMap<>();
        inv.put("BREAD", 5);
        inv.put("IRON_INGOT", 20);
        inv.put("BONE", 8);
        inv.put("EMERALD", 12);
        for (long seed = 0; seed < 200; seed++) {
            var r = DailyRefresh.refresh(inv, FOODS, GOODS, SUNDRIES, new Random(seed));
            assertTrue(foodTotal(r.inventory()) >= DailyRefresh.FOOD_FLOOR,
                    "seed=" + seed + " 口粮跌破保底");
            assertTrue(foodTotal(r.inventory()) <= DailyRefresh.FOOD_CAP,
                    "seed=" + seed + " 口粮超上限");
            assertEquals(12, (int) r.inventory().getOrDefault("EMERALD", 0));
            for (var e : r.inventory().entrySet()) {
                assertTrue(e.getValue() > 0, "seed=" + seed + " 出现非正数量 " + e);
            }
        }
    }
}
