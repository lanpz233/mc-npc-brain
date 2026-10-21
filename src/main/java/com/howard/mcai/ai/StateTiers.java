package com.howard.mcai.ai;

/**
 * 状态档位翻译 —— 把插件内部的定性数值先翻译成活人词汇，再递给模型。
 * <p>
 * 立这条规矩的原因（2026-10-22，服主定）：模型飘的从来不是词，是数。给它
 * “饥饿 73”，它得先在心里换算“73 算多饿”，每个模型算得都不一样，弱模型
 * 直接算错；给它“饿得慌”三个字，任何模型都不会理解错。定性的状态给词、
 * 定量的账目（背包数量、价格）才给数 —— 本类只管定性状态。
 * <p>
 * 需求语义与 {@code NeedsState} 一致：0-100 的迫切度，越高越强烈
 * （energy 在内部是“疲惫”：越高越累，睡觉满足）。
 */
public final class StateTiers {

    private StateTiers() {
    }

    // ===== 需求五项 =====

    /** 饥饿迫切度 → 档位词（服主定的大意：饱了/还好/饿了/饿得慌，界线对齐内部迫切度） */
    public static String hunger(double urge) {
        if (urge <= 10) return "吃撑了";
        if (urge <= 35) return "不饿";
        if (urge <= 60) return "有点饿";
        if (urge <= 85) return "饿了";
        return "饿得慌";
    }

    /** 疲惫迫切度 → 档位词 */
    public static String energy(double urge) {
        if (urge <= 10) return "精神头十足";
        if (urge <= 35) return "还算精神";
        if (urge <= 60) return "有点乏了";
        if (urge <= 85) return "困了";
        return "困得睁不开眼";
    }

    /** 无聊迫切度 → 档位词 */
    public static String boredom(double urge) {
        if (urge <= 20) return "有事可干";
        if (urge <= 50) return "有点闲";
        if (urge <= 75) return "无聊";
        return "闲得发慌";
    }

    /** 好奇迫切度 → 档位词 */
    public static String curiosity(double urge) {
        if (urge <= 20) return "没什么好奇的";
        if (urge <= 50) return "有点好奇";
        if (urge <= 75) return "挺好奇";
        return "特别想去看看";
    }

    /** 社交迫切度 → 档位词 */
    public static String social(double urge) {
        if (urge <= 20) return "想静静";
        if (urge <= 50) return "还好，不太想说话";
        if (urge <= 75) return "想找人说话";
        return "憋得慌，想找人唠嗑";
    }

    /** 需求键 → 中文名（与 NeedsState 的键对应） */
    public static String needLabel(String needKey) {
        return switch (needKey) {
            case "hunger" -> "饥饿";
            case "energy" -> "疲惫";
            case "boredom" -> "无聊";
            case "curiosity" -> "好奇";
            case "social" -> "想找人说话";
            default -> needKey;
        };
    }

    /** 需求键 + 迫切度 → 档位词（认不出的键返回空串） */
    public static String needTier(String needKey, double urge) {
        return switch (needKey) {
            case "hunger" -> hunger(urge);
            case "energy" -> energy(urge);
            case "boredom" -> boredom(urge);
            case "curiosity" -> curiosity(urge);
            case "social" -> social(urge);
            default -> "";
        };
    }

    // ===== 关系与情绪 =====

    /** 好感（可为负）→ 关系档位词 */
    public static String favor(double favor) {
        if (favor <= -40) return "结了仇";
        if (favor <= -10) return "很冷淡";
        if (favor < 10) return "生分";
        if (favor < 30) return "面熟";
        if (favor < 60) return "处得不错";
        if (favor < 90) return "交情很深";
        return "跟亲人一样";
    }

    /** 恐惧（0-100）→ 档位词 */
    public static String fear(double fear) {
        if (fear <= 10) return "心里踏实";
        if (fear <= 35) return "有点发毛";
        if (fear <= 60) return "害怕";
        return "吓坏了";
    }

    /** 生命百分比（0-100）→ 伤情档位词 */
    public static String health(double pct) {
        if (pct >= 90) return "身子骨硬朗";
        if (pct >= 60) return "有点小伤，不碍事";
        if (pct >= 30) return "伤得不轻";
        return "伤得很重";
    }

    /** 玩家生命百分比 → 从村民眼里看的档位词（对方现状卡用） */
    public static String playerHealth(double pct) {
        if (pct >= 90) return "气色很好";
        if (pct >= 60) return "看着还行";
        if (pct >= 35) return "脸色不太好，像是受了伤";
        return "脸色发白，伤得很重的样子";
    }
}
