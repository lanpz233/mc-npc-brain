package com.howard.mcai.ai;

import java.util.Random;

/**
 * 打劫意志 —— “被威胁时撑不撑得住”的纯计算（Fong 2026-10-23 定稿）。
 * <p>
 * 规矩（服主亲自定）：
 * <ul>
 *   <li><b>胆量</b>（courage 0~100）出生抽定、终身不变：胆小的低恐惧就破防，
 *       倔的恐惧拉满也可能死扛——“给不给”跟这个人的胆量走。</li>
 *   <li><b>破防概率</b>只看恐惧与胆量：恐惧越高概率越大。骰子由插件掷，
 *       结果写进局势简报告诉模型，模型只按结果演（认怂后讨价还价 / 没破防就求饶死扛）；
 *       几率让模型自己演不准，必须插件掷才作数。</li>
 *   <li>绿宝石<b>不封顶</b>：破防后交多少由对话里的讨价还价决定，本类不管数量，
 *       只管“这一刻他撑不撑得住”。</li>
 * </ul>
 * 纯计算、不碰 Bukkit，离线可测。
 */
public final class RobberyWill {

    private RobberyWill() {
    }

    /**
     * 出生抽胆量：胆小 25%（15~35）、普通 50%（40~65）、大胆 20%（66~85）、
     * 倔种 5%（86~98）。分布偏普通人，极端性子是少数——跟真人一样。
     */
    public static int rollCourage(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.25) return 15 + rnd.nextInt(21);
        if (r < 0.75) return 40 + rnd.nextInt(26);
        if (r < 0.95) return 66 + rnd.nextInt(20);
        return 86 + rnd.nextInt(13);
    }

    /**
     * 这一轮被吓破防的概率：恐惧没起来（≤5）时恒为 0——好好说话不可能抢到东西；
     * 之后随（恐惧 − 胆量）线性升，封顶 0.95（再倔的人也留一线被吓崩的可能，
     * 反过来胆量 98 的人在恐惧 100 时也只有五成会崩，“宁死不屈”真实存在）。
     */
    public static double breakChance(int fear, int courage) {
        if (fear <= 5) return 0.0;
        double p = (fear - courage + 30) / 80.0;
        return Math.max(0.0, Math.min(0.95, p));
    }

    /** 掷一次：这一轮他撑不住了吗 */
    public static boolean breaks(int fear, int courage, Random rnd) {
        return rnd.nextDouble() < breakChance(fear, courage);
    }

    /** 胆量 → 给模型的定性说法（定性给词，不给数） */
    public static String courageTierZh(int courage) {
        if (courage <= 35) return "胆子很小，一吓就腿软";
        if (courage <= 55) return "胆子一般，吓唬几句就发慌";
        if (courage <= 75) return "胆子不小，轻易吓不倒";
        return "胆子极大，刀架在脖子上也敢硬扛";
    }

    /** 恐惧 → 给模型的定性说法（定性给词，不给数） */
    public static String fearTierZh(int fear) {
        if (fear < 25) return "有点发毛";
        if (fear < 50) return "心里直打鼓";
        if (fear < 75) return "吓得腿软";
        return "吓得魂不附体";
    }
}
