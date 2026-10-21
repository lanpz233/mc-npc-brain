package com.howard.mcai.shop;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以物抵价的算账（纯计算，可单测）—— Fong 2026-10-22 定：以物换物不删，
 * 从聊天搬进商店，买东西时可以拿东西顶账。
 * <p>
 * 计价口径：与村民自己的收购牌价**完全一致、论捆算**——他 20 个小麦收 1 块
 * 绿宝石，抵价时也是 20 个小麦抵 1 块；不足一捆的零头不计价、不消耗。
 * 这样“拿东西抵”和“先卖给他再买”一分钱不差，钻不出套利空子。
 * <p>
 * 条款（terms）由调用方从该村民的收购目录里提取：材料 → [一捆几个, 一捆给几块]。
 */
public final class BarterPlan {

    private BarterPlan() {
    }

    /**
     * 一次抵价的结算计划。
     *
     * @param consumed           实际要消耗的物品（材料 → 个数，只含整捆部分）
     * @param goodsValue         这些货抵掉的绿宝石
     * @param emeraldDue         还要玩家补付的绿宝石（货不够抵时）
     * @param change             村民要找给玩家的绿宝石（货抵超了时）
     * @param changeUnaffordable 货抵超了但村民钱包不够找零 → 这单抵不了（少抵一件再试）
     */
    public record Plan(Map<Material, Integer> consumed, int goodsValue,
                       int emeraldDue, int change, boolean changeUnaffordable) {
    }

    /** 从收购目录提取抵价条款：同材料有多个收购捆时取单位价最高的那捆 */
    public static Map<Material, int[]> termsFrom(java.util.List<ShopCatalog.Offer> offers) {
        Map<Material, int[]> terms = new LinkedHashMap<>();
        for (ShopCatalog.Offer o : offers) {
            if (!o.buyBack() || o.cost().size() != 1) continue;
            var cost = o.cost().get(0);
            if (cost.getType() == Material.EMERALD || cost.getAmount() <= 0) continue;
            int[] better = prefer(terms.get(cost.getType()), cost.getAmount(), o.emeraldPrice());
            if (better != null) terms.put(cost.getType(), better);
        }
        return terms;
    }

    /**
     * 两捆条款取单位价更高的（纯函数，供单测）：返回胜出的 [一捆几个, 一捆几块]；
     * 与现有一致或更差时返回 null（不替换）。
     */
    static int[] prefer(int[] cur, int bundleQty, int emeralds) {
        if (bundleQty <= 0 || emeralds <= 0) return null;
        if (cur == null) return new int[]{bundleQty, emeralds};
        double rate = (double) emeralds / bundleQty;
        return rate > (double) cur[1] / cur[0] ? new int[]{bundleQty, emeralds} : null;
    }

    /** 篮子按整捆估值（界面预览用）：零头不算钱 */
    public static int valueOf(Map<Material, Integer> basket, Map<Material, int[]> terms) {
        int total = 0;
        for (Map.Entry<Material, Integer> e : basket.entrySet()) {
            int[] t = terms.get(e.getKey());
            if (t == null) continue;
            total += (e.getValue() / t[0]) * t[1];
        }
        return total;
    }

    /**
     * 按目标价算消耗计划：按篮子顺序逐样凑整捆，凑够价就停（多选的货不消耗）。
     * 货抵超目标价时要村民找零；他钱包不够找零 → changeUnaffordable。
     */
    public static Plan plan(Map<Material, Integer> basket, Map<Material, int[]> terms,
                            int price, int villagerEmeralds) {
        Map<Material, Integer> consumed = new LinkedHashMap<>();
        int goods = 0;
        for (Map.Entry<Material, Integer> e : basket.entrySet()) {
            if (goods >= price) break;
            int[] t = terms.get(e.getKey());
            if (t == null) continue;
            int bundles = e.getValue() / t[0];
            if (bundles <= 0) continue;
            int need = (int) Math.ceil((price - goods) / (double) t[1]);
            int take = Math.min(bundles, Math.max(1, need));
            consumed.put(e.getKey(), take * t[0]);
            goods += take * t[1];
        }
        int due = Math.max(0, price - goods);
        int change = Math.max(0, goods - price);
        boolean unaffordable = change > villagerEmeralds;
        return new Plan(consumed, goods, due, change, unaffordable);
    }
}
