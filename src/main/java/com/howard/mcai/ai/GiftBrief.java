package com.howard.mcai.ai;

import com.howard.mcai.security.GiftPolicy;

/**
 * 赠送判定的“插件翻译”—— 2026-10-26 第 2 档提示词优化定的新口径：
 * 好感数字门槛（普通 ≥10／稀有 ≥60）撤出提示词，改由插件先把“能不能白送”
 * 判成结论，再把定性结论写进对话上下文，模型只按结论演送还是婉拒。
 * <p>
 * 与 {@link StateTiers} 同一条铁律：定性给词、不给数——简报里只有交情档位词
 * 与能／不能，绝不出现好感数字，免得村民把“好感42”念出戏。
 * <p>
 * 判定口径与 give_item 执行时的裁决必须同源：这里直接调
 * {@link GiftPolicy#willingToGive}（门槛常量只此一处），不许另写一份数字；
 * 库存、单次上限、稀有日账、口粮保留那些细账仍由 GiftPolicy.decide 在
 * 执行时把关，不进简报。
 */
public final class GiftBrief {

    private GiftBrief() {
    }

    /** 普通物品（价值未达稀有线）现在能不能无偿送给这个玩家——与 GiftPolicy 同口径 */
    public static boolean commonGiftOk(double favor, boolean envoy) {
        // unitValue 传 0 = 未达稀有线，走普通门槛
        return GiftPolicy.willingToGive(favor, 0, envoy, false);
    }

    /** 稀有物品（价值达到稀有线）现在能不能无偿送给这个玩家——与 GiftPolicy 同口径 */
    public static boolean rareGiftOk(double favor, boolean envoy) {
        return GiftPolicy.willingToGive(favor, GiftPolicy.RARE_VALUE_MIN, envoy, false);
    }

    /**
     * 拼给模型的赠送判定结论（一行定性词、无数字）。
     * 视角：说给村民本人听，第二人称"你"。
     */
    public static String brief(double favor, boolean envoy) {
        boolean common = commonGiftOk(favor, envoy);
        boolean rare = rareGiftOk(favor, envoy);
        StringBuilder sb = new StringBuilder();
        sb.append("你跟这位旅者的交情是「").append(StateTiers.favor(favor)).append("」。");
        sb.append("送东西这件事，系统已经替你判好了，照这个结论演（台词里别提任何数字）：");
        sb.append("普通东西").append(common ? "可以" : "还不能").append("无偿送给他；");
        sb.append("贵重东西（铁、绿宝石、钻石一类值钱货）").append(rare ? "可以" : "还不能").append("无偿送给他。");
        if (!rare) {
            sb.append(common
                    ? "他要是讨要贵重东西，你就按自己的性格婉拒，或者提议拿东西跟你换。"
                    : "他要是开口讨要，你就按自己的性格婉拒，或者提议拿东西跟你换。");
        }
        return sb.toString();
    }
}
