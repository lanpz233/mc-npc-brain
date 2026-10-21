package com.howard.mcai.shop;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.4.0：以物抵价的算账（论捆计价、凑价停、补付/找零） */
class BarterPlanTest {

    @Test
    void preferTakesBetterRate() {
        // 离线造不出 ItemStack（Paper 注册表），termsFrom 的取优逻辑抽成纯函数单独验
        assertArrayEquals(new int[]{20, 1}, BarterPlan.prefer(null, 20, 1));
        assertArrayEquals(new int[]{10, 1}, BarterPlan.prefer(new int[]{20, 1}, 10, 1));  // 单位价翻倍 → 换
        assertNull(BarterPlan.prefer(new int[]{10, 1}, 20, 1));                            // 更差 → 不换
        assertNull(BarterPlan.prefer(new int[]{10, 1}, 20, 2));                            // 同价 → 不换
        assertNull(BarterPlan.prefer(null, 0, 1));                                          // 坏条款
    }

    @Test
    void valueCountsWholeBundlesOnly() {
        Map<Material, int[]> terms = Map.of(Material.WHEAT, new int[]{20, 1});
        // 39 个小麦 = 1 整捆 = 1 块，零头 19 个不计价
        assertEquals(1, BarterPlan.valueOf(Map.of(Material.WHEAT, 39), terms));
        assertEquals(2, BarterPlan.valueOf(Map.of(Material.WHEAT, 40), terms));
        assertEquals(0, BarterPlan.valueOf(Map.of(Material.WHEAT, 19), terms));
    }

    @Test
    void planStopsAtPriceAndComputesDueAndChange() {
        Map<Material, int[]> terms = new LinkedHashMap<>();
        terms.put(Material.WHEAT, new int[]{20, 1});
        terms.put(Material.IRON_INGOT, new int[]{4, 1});
        Map<Material, Integer> basket = new LinkedHashMap<>();
        basket.put(Material.WHEAT, 60);        // 3 捆 = 3 块
        basket.put(Material.IRON_INGOT, 40);   // 10 捆 = 10 块

        // 价 5：小麦 3 捆（3 块）+ 铁 2 捆（2 块）刚好，不多消耗
        var p1 = BarterPlan.plan(basket, terms, 5, 100);
        assertEquals(5, p1.goodsValue());
        assertEquals(0, p1.emeraldDue());
        assertEquals(0, p1.change());
        assertEquals(60, p1.consumed().get(Material.WHEAT));
        assertEquals(8, p1.consumed().get(Material.IRON_INGOT));

        // 价 20：全抵上还差 7 块补付
        var p2 = BarterPlan.plan(basket, terms, 20, 100);
        assertEquals(13, p2.goodsValue());
        assertEquals(7, p2.emeraldDue());

        // 价 4：小麦凑 3 块 + 铁 1 捆 = 4 块整
        var p3 = BarterPlan.plan(basket, terms, 4, 100);
        assertEquals(4, p3.goodsValue());
        assertEquals(4, p3.consumed().get(Material.IRON_INGOT));
    }

    @Test
    void planChangeAndUnaffordable() {
        Map<Material, int[]> terms = Map.of(Material.DIAMOND, new int[]{1, 30});
        Map<Material, Integer> basket = Map.of(Material.DIAMOND, 1);
        // 价 10，钻石抵 30 → 找零 20；钱包 50 够找
        var ok = BarterPlan.plan(basket, terms, 10, 50);
        assertEquals(20, ok.change());
        assertFalse(ok.changeUnaffordable());
        // 钱包只有 5 → 找不开，这单抵不了
        var no = BarterPlan.plan(basket, terms, 10, 5);
        assertTrue(no.changeUnaffordable());
    }
}
