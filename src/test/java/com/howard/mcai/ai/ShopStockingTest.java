package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按村民抽货回归（Fong 2026-10-23 改版）：每天刷新换货、招牌货不变、
 * 货的档次受买卖等级限制（0 级只卖原材料）、2 级起才有附魔货。
 */
class ShopStockingTest {

    private static List<ShopStocking.StockedItem> sellsOf(List<ShopStocking.StockedItem> stock) {
        return stock.stream().filter(i -> !i.buyBack() && !i.guest()).toList();
    }

    @Test
    void deterministicSameDay() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(ShopStocking.stockFor(VillagerProfession.FARMER, id, 2, 100),
                ShopStocking.stockFor(VillagerProfession.FARMER, id, 2, 100));
    }

    @Test
    void stockChangesAcrossDays() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174010");
        Set<Set<Material>> seen = new HashSet<>();
        for (long day = 100; day < 114; day++) {
            Set<Material> items = new HashSet<>();
            for (var s : sellsOf(ShopStocking.stockFor(VillagerProfession.FARMER, id, 2, day))) {
                items.add(s.item());
            }
            seen.add(items);
        }
        assertTrue(seen.size() >= 3, "两周里至少应有 3 种不同的摊，实际 " + seen.size());
    }

    @Test
    void differentFarmersStockDifferently() {
        Set<Set<Material>> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            UUID id = new UUID(0xFA12L, i * 7919L + 13);
            Set<Material> items = new HashSet<>();
            for (var s : sellsOf(ShopStocking.stockFor(VillagerProfession.FARMER, id, 1, 7))) {
                items.add(s.item());
            }
            seen.add(items);
        }
        assertTrue(seen.size() >= 3, "12 个农夫至少应有 3 种不同的摊，实际 " + seen.size());
    }

    @Test
    void sizesWithinBounds() {
        for (int i = 0; i < 20; i++) {
            var stock = ShopStocking.stockFor(VillagerProfession.FARMER,
                    new UUID(i * 31L + 7, i * 131L), 2, 50 + i);
            long sells = stock.stream().filter(x -> !x.buyBack() && !x.guest()).count();
            long guests = stock.stream().filter(ShopStocking.StockedItem::guest).count();
            long buys = stock.stream().filter(ShopStocking.StockedItem::buyBack).count();
            assertTrue(sells >= 6 && sells <= 8, "卖货应 6~8 样，实际 " + sells);
            assertTrue(guests <= 2, "外行杂货至多 2 件，实际 " + guests);
            assertTrue(buys >= 2 && buys <= 3, "收购应 2~3 样，实际 " + buys);
            for (var s : stock) assertTrue(s.priceEmeralds() >= 1, "价格必须为正");
        }
    }

    @Test
    void levelZeroSellsOnlyRawMaterials() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174020");
        for (long day = 0; day < 10; day++) {
            for (var s : ShopStocking.stockFor(VillagerProfession.WEAPONSMITH, id, 0, day)) {
                if (s.buyBack()) continue;
                assertEquals(0, ShopTier.tierOf(s.item().name()),
                        "0 级摊不许出现 0 档以上的货: " + s.item());
                assertTrue(s.enchantZh() == null, "0 级不许有附魔货");
            }
        }
    }

    @Test
    void levelFourCanStockDiamond() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174021");
        boolean sawDiamond = false;
        for (long day = 0; day < 40 && !sawDiamond; day++) {
            for (var s : sellsOf(ShopStocking.stockFor(VillagerProfession.WEAPONSMITH, id, 4, day))) {
                if (s.item() == Material.DIAMOND_SWORD || s.item() == Material.DIAMOND_AXE) {
                    sawDiamond = true;
                }
            }
        }
        assertTrue(sawDiamond, "4 级武器匠 40 天里总该刷出一件钻石货");
    }

    @Test
    void enchantedOnlyFromLevelTwo() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174022");
        for (long day = 0; day < 30; day++) {
            for (var s : ShopStocking.stockFor(VillagerProfession.WEAPONSMITH, id, 1, day)) {
                assertTrue(s.enchantZh() == null, "1 级不许有附魔货，day=" + day);
            }
        }
        boolean sawEnchanted = false;
        for (long day = 0; day < 60 && !sawEnchanted; day++) {
            for (var s : ShopStocking.stockFor(VillagerProfession.WEAPONSMITH, id, 4, day)) {
                if (s.enchantZh() != null) sawEnchanted = true;
            }
        }
        assertTrue(sawEnchanted, "4 级 60 天里总该刷出一件附魔货");
    }

    @Test
    void signatureItemsStableAcrossDays() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174023");
        Set<Material> reference = null;
        for (long day = 200; day < 210; day++) {
            Set<Material> sigs = new HashSet<>();
            for (var s : ShopStocking.stockFor(VillagerProfession.FARMER, id, 2, day)) {
                if (s.signature()) {
                    sigs.add(s.item());
                    assertTrue(ShopTier.tierOf(s.item().name()) <= 1,
                            "招牌货档次要压低: " + s.item());
                }
            }
            assertTrue(!sigs.isEmpty() && sigs.size() <= 2, "招牌货应有 1~2 件，实际 " + sigs);
            if (reference == null) reference = sigs;
            assertEquals(reference, sigs, "招牌货不许天天变，day=" + day);
        }
    }

    @Test
    void everythingComesFromPools() {
        var stock = ShopStocking.stockFor(VillagerProfession.TOOLSMITH,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), 3, 42);
        Set<Material> sellable = new HashSet<>();
        ProfessionPools.sellPool(VillagerProfession.TOOLSMITH).forEach(e -> sellable.add(e.item()));
        ProfessionPools.guestPool().forEach(e -> sellable.add(e.item()));
        Set<Material> buyable = new HashSet<>();
        ProfessionPools.buyPool(VillagerProfession.TOOLSMITH).forEach(e -> buyable.add(e.item()));
        for (var s : stock) {
            if (s.buyBack()) {
                assertTrue(buyable.contains(s.item()), "收购物必须来自收购池: " + s.item());
            } else {
                assertTrue(sellable.contains(s.item()), "卖货必须来自职业池或杂货池: " + s.item());
            }
        }
    }

    @Test
    void buybackNeverAboveSell() {
        for (int i = 0; i < 20; i++) {
            var stock = ShopStocking.stockFor(VillagerProfession.CLERIC,
                    new UUID(i * 17L + 3, i * 57L), 2, 300 + i);
            for (var s : stock) {
                if (s.buyBack()) {
                    assertTrue(s.priceEmeralds() <= PriceBook.sellPrice(s.item(), s.count()),
                            s.item() + " 收购价不得高于卖价");
                }
            }
        }
    }

    @Test
    void smallPoolsDoNotCrash() {
        // 无业卖池只有 6 样、收购池 2 样：抽不满也不能炸
        var stock = ShopStocking.stockFor(VillagerProfession.NONE,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174002"), 0, 9);
        assertTrue(stock.size() >= 8);
    }
}
