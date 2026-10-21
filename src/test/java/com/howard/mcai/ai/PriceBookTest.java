package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 价格账回归（Fong 2026-10-21：金锭 1 块、铁块 20 块的笑话不能再有）：
 * 凡能合成的，价格必须严格按配方由原料推导；卖价与收购价永不构成套利。
 */
class PriceBookTest {

    @Test
    void ironBlockIsNineIngots() {
        assertEquals(9 * PriceBook.fairValue(Material.IRON_INGOT),
                PriceBook.fairValue(Material.IRON_BLOCK), 1e-9);
    }

    @Test
    void goldBlockIsNineIngots() {
        assertEquals(9 * PriceBook.fairValue(Material.GOLD_INGOT),
                PriceBook.fairValue(Material.GOLD_BLOCK), 1e-9);
    }

    @Test
    void anvilFromBlocksAndIngots() {
        double expect = 3 * PriceBook.fairValue(Material.IRON_BLOCK)
                + 4 * PriceBook.fairValue(Material.IRON_INGOT);
        assertEquals(expect, PriceBook.fairValue(Material.ANVIL), 1e-9);
    }

    @Test
    void breadFromWheat() {
        assertEquals(3 * PriceBook.fairValue(Material.WHEAT),
                PriceBook.fairValue(Material.BREAD), 1e-9);
    }

    @Test
    void goldenAppleFromGold() {
        double expect = 8 * PriceBook.fairValue(Material.GOLD_INGOT)
                + PriceBook.fairValue(Material.APPLE);
        assertEquals(expect, PriceBook.fairValue(Material.GOLDEN_APPLE), 1e-9);
    }

    @Test
    void enchantingTableNoLongerAbsurd() {
        // 旧价 128 绿宝石（拍脑袋）——按配方（书 + 2 钻 + 4 黑曜石）推导后应远低于此
        assertTrue(PriceBook.sellPrice(Material.ENCHANTING_TABLE, 1) < 60,
                "附魔台卖价应由配方推导，不再是 128");
        assertTrue(PriceBook.sellPrice(Material.ENCHANTING_TABLE, 1) >= 10,
                "附魔台也不该贱卖");
    }

    @Test
    void ironBlockSellPriceSane() {
        // 旧价：铁块 20、金锭 1。现在铁块 ≈ 9 铁锭推导，个位数绿宝石
        int block = PriceBook.sellPrice(Material.IRON_BLOCK, 1);
        assertTrue(block >= 3 && block <= 6, "铁块卖价应在 3~6 之间，实际 " + block);
    }

    @Test
    void negotiatedPricesAreClampedToTheBook() {
        // 旧存档动态价「铁块 20」必须被夹回合理带（本账卖价 4 → 上限 12）
        int clamped = PriceBook.clampNegotiatedPrice(Material.IRON_BLOCK, 1, 20);
        assertTrue(clamped <= PriceBook.sellPrice(Material.IRON_BLOCK, 1) * 3,
                "外部报价不得超过本账卖价 3 倍，实际 " + clamped);
        // 合理报价原样放行
        assertEquals(2, PriceBook.clampNegotiatedPrice(Material.BREAD, 6, 2));
        // 非法报价（0/负数）抬回 1
        assertEquals(1, PriceBook.clampNegotiatedPrice(Material.BREAD, 6, 0));
        // 附魔台的 128 遗毒也进不了店
        assertTrue(PriceBook.clampNegotiatedPrice(Material.ENCHANTING_TABLE, 1, 128)
                <= PriceBook.sellPrice(Material.ENCHANTING_TABLE, 1) * 3);
    }

    @Test
    void emeraldStacksUnder64Single() {
        assertEquals(java.util.List.of(4), PriceBook.emeraldStacks(4, false));
        assertEquals(java.util.List.of(64), PriceBook.emeraldStacks(64, false));
        assertEquals(java.util.List.of(1), PriceBook.emeraldStacks(0, false), "非法价格抬回 1");
    }

    @Test
    void emeraldStacksOver64Split() {
        // 钻石胸甲卖 96 块 —— 2026-10-21 引爆插件加载崩溃的配方形态
        assertEquals(java.util.List.of(64, 32), PriceBook.emeraldStacks(96, false));
        assertEquals(java.util.List.of(64, 64), PriceBook.emeraldStacks(999, false), "超过 128 截断");
    }

    @Test
    void emeraldStacksWithSecondIngredientCapped() {
        assertEquals(java.util.List.of(64), PriceBook.emeraldStacks(96, true),
                "有第二原料时价格封顶 64、只占一格，总格数才不超 2");
        assertEquals(java.util.List.of(30), PriceBook.emeraldStacks(30, true));
    }

    @Test
    void emeraldStacksInvariantAcrossAllPools() {
        // 全池真实价格 × 有/无第二原料：总格数永不超 2，金额与成交价一致，每格 1..64
        for (com.howard.mcai.mob.VillagerProfession p
                : com.howard.mcai.mob.VillagerProfession.values()) {
            for (ProfessionPools.PoolItem e : ProfessionPools.sellPool(p)) {
                int price = PriceBook.sellPrice(e.item(), e.count());
                for (boolean second : new boolean[]{false, true}) {
                    var stacks = PriceBook.emeraldStacks(price, second);
                    assertTrue(stacks.size() + (second ? 1 : 0) <= 2,
                            p + " 的 " + e.item() + " 原料总格数会超 2（事故不变量）");
                    int expected = second ? Math.min(price, 64) : Math.min(price, 128);
                    assertEquals(expected, stacks.stream().mapToInt(Integer::intValue).sum());
                    assertTrue(stacks.stream().allMatch(a -> a >= 1 && a <= 64));
                }
            }
        }
    }

    @Test
    void noArbitrageAcrossAllPools() {
        for (VillagerProfession p : VillagerProfession.values()) {
            for (var e : ProfessionPools.sellPool(p)) assertNoArbitrage(e);
            for (var e : ProfessionPools.buyPool(p)) assertNoArbitrage(e);
        }
        for (var e : ProfessionPools.guestPool()) assertNoArbitrage(e);
    }

    private static void assertNoArbitrage(ProfessionPools.PoolItem e) {
        int sell = PriceBook.sellPrice(e.item(), e.count());
        int buy = PriceBook.buybackPrice(e.item(), e.count());
        assertTrue(sell >= 1 && buy >= 1, e.item() + " 价格必须为正");
        assertTrue(buy <= sell, e.item() + " 收购价 " + buy + " 不得高于卖价 " + sell + "（套利）");
    }
}
