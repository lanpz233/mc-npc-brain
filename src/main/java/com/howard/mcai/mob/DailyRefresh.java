package com.howard.mcai.mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 背包每日刷新（Fong 2026-10-23 定）—— 纯计算，不碰 Bukkit，可单测。
 * <p>
 * 取代旧的「每日补货」：旧版只往里塞（面包补到 8 + 职业货 2~3 种），
 * 职业货只进不出，背包总件数堆过 96 后整次补货被跳过，连口粮都停补，
 * 村民背包塞满杂物却没吃的、饥饿顶到 100 —— Fong 实测抓了个正着。
 * <p>
 * 新规矩（每天早上一次，以昨天的家底为基底过日子）：
 * <ul>
 *   <li><b>钱和私人物品不动</b>：绿宝石（钱包）以及不属于吃食/职业货/杂物
 *       任何一类的物品（玩家给的、换来的）原样保留，刷新不许把人家的
 *       家底刷没。</li>
 *   <li><b>职业货与杂物会流动</b>：有的昨天用掉/卖掉了（数量小幅减少），
 *       也可能新进了一两样；职业货 + 杂物总量有上限，超了先减大堆的，
 *       背包永远不再爆仓。</li>
 *   <li><b>口粮保底钉死</b>（最后执行，保证不被流动冲掉）：可吃的东西
 *       总数少于 {@link #FOOD_FLOOR} → 用面包补足；多于 {@link #FOOD_CAP}
 *       → 减到上限。他自己怎么吃，一天都饿不着。</li>
 * </ul>
 * 食物/职业货/杂物的分类由调用方（VillagerState）按运行时数据给出名字集合，
 * 本类只认名字做账，保证离线可测。
 */
public final class DailyRefresh {

    private DailyRefresh() {
    }

    /** 口粮保底：可食物品总数至少这么多（约一天三顿 + 富余，一顿吃 1 个掉 60 点饥饿） */
    public static final int FOOD_FLOOR = 10;
    /** 口粮上限：囤太多吃的没有意义，超出的算他分掉/卖掉了 */
    public static final int FOOD_CAP = 24;
    /** 职业货 + 杂物的总量上限（不含口粮、不含钱）：防爆仓的核心闸门 */
    public static final int GOODS_CAP = 48;

    public static final String EMERALD = "EMERALD";
    public static final String BREAD = "BREAD";

    /** 一次刷新的小结（日志/测试用） */
    public record Summary(int foodBefore, int foodAfter, int goodsTrimmed, int driftedKinds) {
    }

    public record Result(Map<String, Integer> inventory, Summary summary) {
    }

    /**
     * 按规矩刷新一份背包账（不改入参，返回新账）。
     *
     * @param inventory       当前账：物品名 → 数量
     * @param foods           可吃物品名集合（含 BREAD 这类保底物）
     * @param professionGoods 本职业货名集合（卖池 + 收池 + 职业配方）
     * @param sundries        杂物名集合
     * @param rng             随机源（调用方按村民 + 天数做种子，结果可复现）
     */
    public static Result refresh(Map<String, Integer> inventory, Set<String> foods,
                                 Set<String> professionGoods, Set<String> sundries, Random rng) {
        Map<String, Integer> inv = new HashMap<>();
        for (var e : inventory.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                inv.put(e.getKey(), e.getValue());
            }
        }

        int foodBefore = totalOf(inv, foods);
        int drifted = 0;

        // —— 阶段 1：职业货/杂物小幅流动（食物不参与，留给阶段 3 钉死） ——
        // 在手的：三成概率昨天用掉/卖掉了 1~3 个
        for (String key : new ArrayList<>(inv.keySet())) {
            if (foods.contains(key)) continue;
            if (!professionGoods.contains(key) && !sundries.contains(key)) continue;
            if (rng.nextDouble() < 0.30) {
                int cut = 1 + rng.nextInt(3);
                int left = inv.get(key) - cut;
                if (left <= 0) inv.remove(key);
                else inv.put(key, left);
                drifted++;
            }
        }
        // 没在手的职业货：一成半概率新进 1~2 个（至多 2 种）—— 摊主也会进货
        List<String> absentGoods = new ArrayList<>();
        for (String key : professionGoods) {
            if (!foods.contains(key) && !inv.containsKey(key)) absentGoods.add(key);
        }
        java.util.Collections.shuffle(absentGoods, rng);
        int restocked = 0;
        for (String key : absentGoods) {
            if (restocked >= 2) break;
            if (rng.nextDouble() < 0.15) {
                inv.put(key, 1 + rng.nextInt(2));
                restocked++;
                drifted++;
            }
        }

        // —— 阶段 2：职业货 + 杂物总量封顶（超了从大堆往下减，食物不算在内） ——
        int trimmed = 0;
        int goodsTotal = 0;
        for (var e : inv.entrySet()) {
            if (foods.contains(e.getKey())) continue;
            if (professionGoods.contains(e.getKey()) || sundries.contains(e.getKey())) {
                goodsTotal += e.getValue();
            }
        }
        if (goodsTotal > GOODS_CAP) {
            List<Map.Entry<String, Integer>> stacks = new ArrayList<>();
            for (var e : inv.entrySet()) {
                if (foods.contains(e.getKey())) continue;
                if (professionGoods.contains(e.getKey()) || sundries.contains(e.getKey())) {
                    stacks.add(e);
                }
            }
            stacks.sort((a, b) -> b.getValue() - a.getValue());
            int excess = goodsTotal - GOODS_CAP;
            for (var e : stacks) {
                if (excess <= 0) break;
                int cut = Math.min(excess, e.getValue());
                int left = e.getValue() - cut;
                if (left <= 0) inv.remove(e.getKey());
                else inv.put(e.getKey(), left);
                excess -= cut;
                trimmed += cut;
            }
        }

        // —— 阶段 3：口粮保底钉死（最后执行，谁也不许冲掉） ——
        int food = totalOf(inv, foods);
        if (food < FOOD_FLOOR) {
            inv.merge(BREAD, FOOD_FLOOR - food, Integer::sum);
            food = FOOD_FLOOR;
        } else if (food > FOOD_CAP) {
            int excess = food - FOOD_CAP;
            // 先减面包（主食囤货），再按数量从大到小减其他食物，每样至少留 1 个
            List<String> keys = new ArrayList<>();
            for (String k : inv.keySet()) {
                if (foods.contains(k)) keys.add(k);
            }
            keys.sort((a, b) -> {
                if (a.equals(BREAD)) return -1;
                if (b.equals(BREAD)) return 1;
                return inv.get(b) - inv.get(a);
            });
            for (String k : keys) {
                if (excess <= 0) break;
                int have = inv.get(k);
                int cut = Math.min(excess, k.equals(BREAD) ? have : have - 1);
                if (cut <= 0) continue;
                int left = have - cut;
                if (left <= 0) inv.remove(k);
                else inv.put(k, left);
                excess -= cut;
                food -= cut;
            }
        }

        return new Result(inv, new Summary(foodBefore, totalOf(inv, foods), trimmed, drifted));
    }

    private static int totalOf(Map<String, Integer> inv, Set<String> keys) {
        int total = 0;
        for (var e : inv.entrySet()) {
            if (keys.contains(e.getKey())) total += e.getValue();
        }
        return total;
    }
}
