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
 * 按村民抽货回归（Fong 2026-10-21：同职业村民不能再卖得一模一样）：
 * 同一村民永远同一摊（种子固定）、不同村民摊子不同、货只来自职业池+杂货池。
 */
class ShopStockingTest {

    private static List<ShopStocking.StockedItem> sellsOf(List<ShopStocking.StockedItem> stock) {
        return stock.stream().filter(i -> !i.buyBack() && !i.guest()).toList();
    }

    @Test
    void deterministicPerVillager() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(ShopStocking.stockFor(VillagerProfession.FARMER, id),
                ShopStocking.stockFor(VillagerProfession.FARMER, id));
    }

    @Test
    void differentFarmersStockDifferently() {
        Set<Set<Material>> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            UUID id = new UUID(0xFA12L, i * 7919L + 13);
            Set<Material> items = new HashSet<>();
            for (var s : sellsOf(ShopStocking.stockFor(VillagerProfession.FARMER, id))) {
                items.add(s.item());
            }
            seen.add(items);
        }
        assertTrue(seen.size() >= 3, "12 个农夫至少应有 3 种不同的摊，实际 " + seen.size());
    }

    @Test
    void sizesWithinBounds() {
        for (int i = 0; i < 20; i++) {
            var stock = ShopStocking.stockFor(VillagerProfession.FARMER, new UUID(i * 31L + 7, i * 131L));
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
    void everythingComesFromPools() {
        var stock = ShopStocking.stockFor(VillagerProfession.TOOLSMITH,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174001"));
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
            var stock = ShopStocking.stockFor(VillagerProfession.CLERIC, new UUID(i * 17L + 3, i * 57L));
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
                UUID.fromString("123e4567-e89b-12d3-a456-426614174002"));
        assertTrue(stock.size() >= 8);
    }
}
