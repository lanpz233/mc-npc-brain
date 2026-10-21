package com.howard.mcai.security;

/**
 * 送东西的确定性裁决 —— 替代旧的「掷骰子看给不给」（EmotionState.giveChance 概率制）。
 * <p>
 * 分工原则（设计决策 2026-10-21）：给不给、给几个，由插件按规矩算死；AI 只有表态权。
 * 本类是纯函数：输入全部是数字与标记，不碰 Bukkit、不掷随机数，同样的输入永远
 * 同样的裁决，因此可完整单测。价值尺子用 {@link PermissionManager#valueOf}。
 * <p>
 * 规矩一览（非神使）：
 * <ul>
 *   <li>库存不够 → 不给（不凭空变、不给一半吊胃口）；</li>
 *   <li>好感门槛：普通物品（价值 &lt;100）好感 ≥10；稀有物品（价值 ≥100）好感 ≥60（亲信线）。
 *       已由代码层登记并批准的索要（pinned）跳过此门槛——那次裁决在登记时已做过；</li>
 *   <li>单次上限：普通物品最多 {@value #COMMON_SINGLE_MAX} 个，稀有物品最多 1 个
 *       （pinned 跳过——数量是玩家在界面/对话里点名且插件已批的）；</li>
 *   <li>稀有日账：同一玩家一天最多从同一 NPC 拿到 1 件稀有物品（pinned 也受此限，
 *       防的是「反复讨要刷稀有」）；</li>
 *   <li>口粮保留：送食物后，NPC 库存里可食物品总数不得低于 {@value #FOOD_RESERVE} 个
 *       （会把数量压到保留线为止；压到 0 则不给）。pinned 同样受此限——村民不能把自己饿死；</li>
 *   <li>神使：全豁免（可凭空补足，凭空部分由执行方记审计，与旧规一致）。</li>
 * </ul>
 */
public final class GiftPolicy {

    /** 普通物品的好感门槛 */
    public static final double COMMON_FAVOR_MIN = 10.0;

    /** 稀有物品的好感门槛（= 亲信线） */
    public static final double RARE_FAVOR_MIN = 60.0;

    /** 价值达到此线算稀有物品（与 PermissionManager.isRarePlus 同口径） */
    public static final int RARE_VALUE_MIN = 100;

    /** 普通物品单次最多送几个（模型自发赠送时；设计决策暂定值） */
    public static final int COMMON_SINGLE_MAX = 3;

    /** 送食物后必须保留的口粮总数（设计决策暂定值） */
    public static final int FOOD_RESERVE = 4;

    private GiftPolicy() {
    }

    /** 裁决结果：allowed 时 giveCount 为实际交付数（可能被上限/口粮线压低）；reason 是给玩家看的人话原因（allowed 时为 null） */
    public record Verdict(boolean allowed, int giveCount, String reason) {
        static Verdict allow(int count) {
            return new Verdict(true, count, null);
        }

        static Verdict deny(String reason) {
            return new Verdict(false, 0, reason);
        }
    }

    /**
     * 索要场景的「愿不愿意给」——聊天讨要与背包界面索要共用，
     * 在登记索要请求的那一刻调用，结果钉进请求里，之后不许再改口。
     *
     * @param reclaim 这件东西本来就是该玩家刚送给 NPC 的（3 分钟回收窗）→ 无条件归还
     */
    public static boolean willingToGive(double favor, int unitValue, boolean envoy, boolean reclaim) {
        if (reclaim || envoy) return true;
        return favor >= (unitValue >= RARE_VALUE_MIN ? RARE_FAVOR_MIN : COMMON_FAVOR_MIN);
    }

    /**
     * 完整裁决（give_item 执行时调用）。
     *
     * @param envoy          NPC 是否神使
     * @param pinnedCount    代码层已登记并批准的索要数量；&gt;0 表示本次是「兑现已批的索要」，0 表示模型自发赠送
     * @param pinnedApproved pinned 时插件当时的裁决（pinnedCount&gt;0 才有意义）
     * @param requestedCount 模型请求的数量
     * @param stock          NPC 当前库存
     * @param favor          该玩家对 NPC 的好感
     * @param unitValue      物品单位价值（PermissionManager.valueOf）
     * @param foodItem       是否可食物品
     * @param totalFoodStock NPC 当前全部可食物品总数（foodItem 时用于算口粮线）
     * @param rareGivenToday 该玩家今天是否已从本 NPC 拿到过稀有物品
     */
    public static Verdict decide(boolean envoy, int pinnedCount, boolean pinnedApproved,
                                 int requestedCount, int stock, double favor, int unitValue,
                                 boolean foodItem, int totalFoodStock, boolean rareGivenToday) {
        int want = pinnedCount > 0 ? pinnedCount : Math.max(1, Math.min(64, requestedCount));

        // 神使：全豁免（凭空补足在执行方，须审计）
        if (envoy) return Verdict.allow(want);

        // 已登记的索要若当时裁决就是不给，模型这一轮改口答应也不算数
        if (pinnedCount > 0 && !pinnedApproved) {
            return Verdict.deny("跟你还没熟到白给这个的程度");
        }

        boolean rare = unitValue >= RARE_VALUE_MIN;

        // 稀有日账：一天一件，防反复讨要刷稀有（对 pinned 同样生效）
        if (rare && rareGivenToday) {
            return Verdict.deny("今天已经给过你一件稀罕东西了，不能天天给");
        }

        // 好感门槛（pinned 已在登记时裁决过，跳过）
        if (pinnedCount <= 0) {
            double need = rare ? RARE_FAVOR_MIN : COMMON_FAVOR_MIN;
            if (favor < need) {
                return Verdict.deny(rare
                        ? "这么稀罕的东西，得交情很深才舍得给"
                        : "跟你还没那么熟，不好白给你东西");
            }
        }

        // 库存：不够整单拒绝（与旧口径一致，不给一半）
        if (stock < want) {
            return Verdict.deny("库存里没有那么多了");
        }

        int give = want;
        // 单次上限（仅模型自发赠送；pinned 的数量是插件已批的）
        if (pinnedCount <= 0) {
            give = Math.min(give, rare ? 1 : COMMON_SINGLE_MAX);
        }
        // 口粮保留线：送食物后剩下的可食总数不得低于 FOOD_RESERVE
        if (foodItem) {
            int maxByReserve = totalFoodStock - FOOD_RESERVE;
            if (maxByReserve <= 0) {
                return Verdict.deny("自己也要留口粮，不能给你了");
            }
            give = Math.min(give, maxByReserve);
        }
        if (give <= 0) {
            return Verdict.deny("现在给不了你这个");
        }
        return Verdict.allow(give);
    }
}
