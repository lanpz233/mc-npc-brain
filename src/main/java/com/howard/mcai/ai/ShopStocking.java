package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 按村民抽货 —— 每天刷新 + 买卖等级（Fong 2026-10-23 定，取代旧「同人同摊卖到老」）。
 * <p>
 * 旧版用村民 UUID 做固定种子：同一个人永远同一摊，玩久了毫无新鲜感，
 * 而且货池里有啥卖啥 —— 钻石剑跟铁剑满摊都是，等级感全无。现在：
 * <ul>
 *   <li><b>每天刷新</b>：抽货种子 = 村民 UUID + 当天，同一个摊每天不一样；
 *       价格的 ±10% 个人浮动保留（还是按 UUID 固定，"这家便宜那家贵"不变）。</li>
 *   <li><b>招牌货</b>：另用固定种子抽至多 2 件本行招牌（档次压得低），
 *       每天必在 —— 换大半、留招牌，摊主还是那个摊主。</li>
 *   <li><b>等级说了算</b>：卖货只能从「档次 ≤ 他的买卖等级」（{@link ShopTier} /
 *       {@link TradeLevels}）的池子里抽，档次越低权重越高：低档是日常主角，
 *       高档是偶尔露脸的稀罕物；等级越高，能露脸的稀罕物越多。</li>
 *   <li><b>附魔货</b>：2 级起有概率（30% 起、每升一级 +10%）把当天一件
 *       已上摊的装备换成附魔版（价格翻倍，附魔种类按装备类别挑）。</li>
 *   <li><b>数量小幅抖动</b>：每单个数在池子标称值上 ±1（至少 1），
 *       价格按抖动后的个数算 —— 天天来逛，数量也不完全一样。</li>
 *   <li>收购不看等级（收原材料谁都收），同样每天轮换 2~3 样；
 *       杂货池混入规则照旧（各档都可掺杂物）。</li>
 * </ul>
 * 纯逻辑（同村民 + 同等级 + 同一天 → 同一份结果），可单测。
 */
public final class ShopStocking {

    private ShopStocking() {
    }

    /**
     * 村民摊位上的一单。
     *
     * @param buyBack   true = 收购（玩家交货拿 priceEmeralds 个绿宝石）
     * @param guest     true = 杂货池混入的外行货
     * @param enchantZh 非 null = 附魔货，值是附魔中文名（锋利/效率/保护……），
     *                  建配方时走附魔通道，附魔等级由 TradeRegistry 随机 1~3
     * @param signature true = 招牌货（固定种子抽的，每天必在）
     */
    public record StockedItem(Material item, int count, int priceEmeralds,
                              boolean buyBack, boolean guest,
                              String enchantZh, boolean signature) {
    }

    /** 各档被抽中的权重：低档是日常主角，高档偶尔露脸 */
    private static double tierWeight(int tier) {
        return switch (tier) {
            case 0 -> 5.0;
            case 1 -> 4.0;
            case 2 -> 2.2;
            case 3 -> 1.1;
            default -> 0.55;
        };
    }

    /**
     * 为某个村民生成他今天的摊位。
     *
     * @param level 他的买卖等级（0~4），决定最高能出几档货
     * @param day   游戏天数（fullTime / 24000），决定今天抽到什么
     */
    public static List<StockedItem> stockFor(VillagerProfession profession, UUID villagerId,
                                             int level, long day) {
        long baseSeed = villagerId.getMostSignificantBits() ^ villagerId.getLeastSignificantBits()
                ^ (profession.ordinal() * 0x9E3779B97F4A7C15L);
        Random daily = new Random(baseSeed ^ (day * 0xD1B54A32D192ED03L) ^ (level * 0x2545F4914F6CDD1DL));
        Random stable = new Random(baseSeed);
        // 个人价格浮动保持「按人固定」：用稳定种子先抽一个属于他本人的浮动系数，
        // 排在所有日常随机之前消费，保证换天不换价位性格
        double personalJitterSeed = stable.nextDouble();

        List<StockedItem> out = new ArrayList<>();
        Set<Material> stocked = new HashSet<>();
        List<ProfessionPools.PoolItem> sellPool = ProfessionPools.sellPool(profession);

        // —— 招牌货：至多 2 件，档次压低（0 级的人招牌只能是 0 档，1 级以上至多 1 档） ——
        int signatureCap = level <= 0 ? 0 : 1;
        List<ProfessionPools.PoolItem> sigPool = eligible(sellPool, signatureCap);
        int sigCount = Math.min(sigPool.size(), 2);
        for (int i = 0; i < sigCount; i++) {
            ProfessionPools.PoolItem e = drawWeighted(sigPool, stocked, stable);
            if (e == null) break;
            stocked.add(e.item());
            out.add(priced(e, false, false, daily, personalJitterSeed, null, true));
        }

        // —— 每日卖货：从「档次 ≤ 等级」的池里按权重抽，凑够 6~8 样（含招牌） ——
        List<ProfessionPools.PoolItem> dayPool = eligible(sellPool, level);
        int sellTarget = Math.min(dayPool.size(), 6 + daily.nextInt(3));
        int sellsNow = (int) out.stream().filter(x -> !x.buyBack()).count();
        while (sellsNow < sellTarget) {
            ProfessionPools.PoolItem e = drawWeighted(dayPool, stocked, daily);
            if (e == null) break;
            stocked.add(e.item());
            out.add(priced(e, false, false, daily, personalJitterSeed, null, false));
            sellsNow++;
        }

        // —— 外行杂货：60% 混 1 件、其中约 1/3 再加 1 件（不与已抽到的重复） ——
        if (daily.nextDouble() < 0.6) {
            List<ProfessionPools.PoolItem> guests = new ArrayList<>(ProfessionPools.guestPool());
            java.util.Collections.shuffle(guests, daily);
            int guestCount = daily.nextDouble() < 0.34 ? 2 : 1;
            for (var g : guests) {
                if (guestCount <= 0) break;
                if (stocked.contains(g.item())) continue;
                out.add(priced(g, false, true, daily, personalJitterSeed, null, false));
                stocked.add(g.item());
                guestCount--;
            }
        }

        // —— 附魔货：2 级起有概率把一件已上摊的装备换成附魔版 ——
        if (level >= 2 && daily.nextDouble() < 0.30 + 0.10 * (level - 2)) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) {
                StockedItem s = out.get(i);
                if (!s.buyBack() && equippable(s.item().name())) candidates.add(i);
            }
            if (!candidates.isEmpty()) {
                int idx = candidates.get(daily.nextInt(candidates.size()));
                StockedItem s = out.get(idx);
                String zh = enchantZhFor(s.item().name());
                int base = PriceBook.sellPrice(s.item(), s.count());
                int price = Math.max(1, (int) Math.round(base * (0.9 + personalJitterSeed * 0.2) * 2.0));
                out.set(idx, new StockedItem(s.item(), s.count(), price,
                        false, s.guest(), zh, s.signature()));
            }
        }

        // —— 收购：池里抽 2~3 样，每天轮换，不看等级 ——
        List<ProfessionPools.PoolItem> buys = new ArrayList<>(ProfessionPools.buyPool(profession));
        java.util.Collections.shuffle(buys, daily);
        int buyCount = Math.min(buys.size(), 2 + daily.nextInt(2));
        for (int i = 0; i < buyCount; i++) {
            out.add(priced(buys.get(i), true, false, daily, personalJitterSeed, null, false));
        }
        return out;
    }

    /** 池子里档次 ≤ cap 的部分 */
    private static List<ProfessionPools.PoolItem> eligible(List<ProfessionPools.PoolItem> pool, int cap) {
        List<ProfessionPools.PoolItem> out = new ArrayList<>();
        for (var e : pool) {
            if (ShopTier.tierOf(e.item().name()) <= cap) out.add(e);
        }
        return out;
    }

    /** 按档次权重抽一件没抽过的；池抽干返回 null */
    private static ProfessionPools.PoolItem drawWeighted(List<ProfessionPools.PoolItem> pool,
                                                         Set<Material> used, Random rng) {
        double total = 0;
        for (var e : pool) {
            if (!used.contains(e.item())) total += tierWeight(ShopTier.tierOf(e.item().name()));
        }
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        ProfessionPools.PoolItem last = null;
        for (var e : pool) {
            if (used.contains(e.item())) continue;
            last = e;
            r -= tierWeight(ShopTier.tierOf(e.item().name()));
            if (r <= 0) return e;
        }
        return last;  // 浮点尾巴兜底
    }

    /** 定价：PriceBook 基准价 × 个人浮动（按人固定）；个数 ±1 抖动后按新个数算价；收购价压在卖价之下防套利 */
    private static StockedItem priced(ProfessionPools.PoolItem e, boolean buyBack, boolean guest,
                                      Random rng, double personalJitterSeed,
                                      String enchantZh, boolean signature) {
        int count = Math.max(1, Math.min(64, e.count() + rng.nextInt(3) - 1));
        double jitter = 0.9 + personalJitterSeed * 0.2;
        if (buyBack) {
            int base = PriceBook.buybackPrice(e.item(), count);
            int pay = Math.max(1, (int) Math.floor(base * jitter));
            pay = Math.min(pay, PriceBook.sellPrice(e.item(), count));
            return new StockedItem(e.item(), count, pay, true, guest, null, false);
        }
        int base = PriceBook.sellPrice(e.item(), count);
        int price = Math.max(1, (int) Math.round(base * jitter));
        return new StockedItem(e.item(), count, price, false, guest, enchantZh, signature);
    }

    /** 能附魔上架的装备类（工具/武器/防具/弓弩/盾/钓竿） */
    static boolean equippable(String name) {
        return name.endsWith("_SWORD") || name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.equals("BOW") || name.equals("CROSSBOW")
                || name.equals("FISHING_ROD") || name.equals("SHIELD");
    }

    /** 按装备类别挑附魔中文名（认不出的名字 TradeRegistry 会自动退回按类随机，不怕写错） */
    static String enchantZhFor(String name) {
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) return "效率";
        if (name.endsWith("_SWORD") || name.endsWith("_AXE")) return "锋利";
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) return "保护";
        if (name.equals("BOW")) return "力量";
        return "耐久";
    }
}
