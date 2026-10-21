package com.howard.mcai.security;

/**
 * 交换「值不值」的确定性裁决 —— 替代旧的「模型自己判断值不值得」。
 * <p>
 * 分工原则（设计决策 2026-10-21）：换什么、几个在玩家点击时已由代码钉死；
 * 值不值也归插件算——用全插件共用的价值尺子（{@link PermissionManager#valueOf}）
 * 把双方总价值摆平，再按好感给折让：交情越深，NPC 越肯吃一点亏。
 * <p>
 * 折让下限（玩家出价总价值 ÷ NPC 物品总价值 不得低于）：
 * 好感 &lt;10 → 1.00（陌生人一分不能占便宜）；10–29 → 0.90；30–59 → 0.75；
 * ≥60（亲信） → 0.50。神使全豁免（与送礼同规）。
 * <p>
 * 已知局限：价值尺子比较粗（大多数普通物品单价都是 10），等价交换主要靠
 * 「数量×单价」比较；日后定价体系细化（原版价锚定）落地时本类自动受益。
 */
public final class SwapPolicy {

    private SwapPolicy() {
    }

    /** 该好感下允许的最低出价比例（玩家总价值 / NPC 总价值） */
    public static double minRatio(double favor) {
        if (favor >= 60) return 0.50;
        if (favor >= 30) return 0.75;
        if (favor >= 10) return 0.90;
        return 1.00;
    }

    /**
     * 这笔交换对 NPC 是否公平（可以答应）。
     *
     * @param playerTotalValue 玩家出价总价值 = valueOf(出价物) × 数量
     * @param npcTotalValue    NPC 物品总价值 = valueOf(NPC 物品) × 数量
     */
    public static boolean isFair(long playerTotalValue, long npcTotalValue, double favor, boolean envoy) {
        if (envoy) return true;
        if (npcTotalValue <= 0) return true;
        double need = Math.ceil(npcTotalValue * minRatio(favor));
        return playerTotalValue >= need;
    }

    /** 不公平时给玩家看的人话原因 */
    public static String unfairReason(double favor) {
        return favor >= 10
                ? "这笔交换不划算，你出的东西比不上他要换走的"
                : "这笔交换不划算，而且你们交情还没到能让他吃亏的份上";
    }
}
