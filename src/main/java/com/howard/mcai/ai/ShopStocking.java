package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 按村民抽货 —— 同职业不再千人一面（Fong 2026-10-21 定）。
 * <p>
 * 每个村民用自己的 UUID 做<b>固定随机种子</b>，从本职业货池（{@link ProfessionPools}）里抽：
 * 卖货 6~8 样、收购 2~3 样；约六成村民再混 1 件杂货池的「外行货」，约两成混 2 件。
 * 因为种子固定，同一个村民每次开店、每次重启都是同一摊货（不会今天卖面包明天不卖），
 * 但同村两个农夫的摊子和价格都不同。价格以 {@link PriceBook} 为准，再叠 ±10% 的
 * 个人浮动（这家便宜点、那家贵点），收购价永远压在卖价之下防套利。
 * <p>
 * 纯逻辑，可单测。
 */
public final class ShopStocking {

    private ShopStocking() {
    }

    /**
     * 村民摊位上的一单。
     *
     * @param buyBack true = 收购（玩家交货拿 priceEmeralds 个绿宝石）
     * @param guest   true = 杂货池混入的外行货
     */
    public record StockedItem(Material item, int count, int priceEmeralds,
                              boolean buyBack, boolean guest) {
    }

    /** 为某个村民生成他的摊位（同 UUID + 同职业 → 永远同一份结果） */
    public static List<StockedItem> stockFor(VillagerProfession profession, UUID villagerId) {
        long seed = villagerId.getMostSignificantBits() ^ villagerId.getLeastSignificantBits()
                ^ (profession.ordinal() * 0x9E3779B97F4A7C15L);
        Random rng = new Random(seed);
        List<StockedItem> out = new ArrayList<>();

        // 卖货：池里抽 6~8 样
        List<ProfessionPools.PoolItem> sells = new ArrayList<>(ProfessionPools.sellPool(profession));
        Collections.shuffle(sells, rng);
        int sellCount = Math.min(sells.size(), 6 + rng.nextInt(3));
        java.util.Set<Material> stocked = new java.util.HashSet<>();
        for (int i = 0; i < sellCount; i++) {
            var e = sells.get(i);
            out.add(priced(e, false, false, rng));
            stocked.add(e.item());
        }

        // 外行杂货：60% 混 1 件、其中 1/3 再加 1 件（不与已抽到的重复）
        if (rng.nextDouble() < 0.6) {
            List<ProfessionPools.PoolItem> guests = new ArrayList<>(ProfessionPools.guestPool());
            Collections.shuffle(guests, rng);
            int guestCount = rng.nextDouble() < 0.34 ? 2 : 1;
            for (var g : guests) {
                if (guestCount <= 0) break;
                if (stocked.contains(g.item())) continue;
                out.add(priced(g, false, true, rng));
                stocked.add(g.item());
                guestCount--;
            }
        }

        // 收购：池里抽 2~3 样
        List<ProfessionPools.PoolItem> buys = new ArrayList<>(ProfessionPools.buyPool(profession));
        Collections.shuffle(buys, rng);
        int buyCount = Math.min(buys.size(), 2 + rng.nextInt(2));
        for (int i = 0; i < buyCount; i++) {
            out.add(priced(buys.get(i), true, false, rng));
        }
        return out;
    }

    /** 定价：PriceBook 基准价 ± 10% 个人浮动；收购价再压到不超过同堆卖价 */
    private static StockedItem priced(ProfessionPools.PoolItem e, boolean buyBack,
                                      boolean guest, Random rng) {
        double jitter = 0.9 + rng.nextDouble() * 0.2;
        if (buyBack) {
            int base = PriceBook.buybackPrice(e.item(), e.count());
            int pay = Math.max(1, (int) Math.floor(base * jitter));
            pay = Math.min(pay, PriceBook.sellPrice(e.item(), e.count()));
            return new StockedItem(e.item(), e.count(), pay, true, guest);
        }
        int base = PriceBook.sellPrice(e.item(), e.count());
        int price = Math.max(1, (int) Math.round(base * jitter));
        return new StockedItem(e.item(), e.count(), price, false, guest);
    }
}
