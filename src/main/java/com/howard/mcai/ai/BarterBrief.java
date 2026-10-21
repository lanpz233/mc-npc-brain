package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;

/**
 * 换物提议的“插件翻译”—— Fong 2026-10-22 定的新流程：
 * 玩家在村民背包里选中想要的东西，再点自己背包里的东西出价；
 * 插件把两边的<b>价值</b>和<b>对这个村民的有利程度（职业挂钩）</b>算成
 * 人话简报给模型，模型据此（按性格与交情）决定同意还是拒绝，
 * 同意就调 accept_barter，插件复核双方物品还在后一次性交换。
 * <p>
 * 这里只算账和翻译，不做裁决——裁决权按服主新规矩交给模型，
 * 但它看到的数字全部由插件算死，编不了价。
 */
public final class BarterBrief {

    private BarterBrief() {
    }

    /** 价值对比档位（offerValue / hisValue）→ 人话 */
    public static String ratioTier(double offerValue, double hisValue) {
        if (hisValue <= 0) return "他那件东西在价目表上几乎不值钱";
        double r = offerValue / hisValue;
        if (r >= 1.25) return "光按价钱算，他占了便宜";
        if (r >= 0.85) return "价钱上基本公道，两边差不多";
        if (r >= 0.55) return "价钱上他有点吃亏";
        return "价钱上他吃大亏";
    }

    /**
     * 出价物品对这个村民的有利程度（职业挂钩）→ 人话。
     * 判断顺序：钱 > 他正收的货 > 饿时吃的 > 他卖的货（不缺） > 不搭边。
     */
    public static String usefulness(VillagerProfession prof, Material offerMat, boolean hungryUrgent) {
        if (offerMat == Material.EMERALD) return "绿宝石是硬通货，谁都喜欢";
        if (ProfessionPools.canBuy(prof, offerMat)) return "这正是他收购的东西，对他很用得上";
        if (hungryUrgent && isFood(offerMat)) return "他正饿着，这口吃的来得正是时候";
        if (ProfessionPools.canSell(prof, offerMat)) return "这东西他自己店里就卖，他不缺";
        return "这东西跟他的营生不搭边，他多半用不上";
    }

    /**
     * 是不是吃的 —— 按材料名判（不用 Material.isEdible：它要碰 Paper 注册表，
     * 离线单测环境造不出来；名单覆盖村民日常能接触到的食物足够了）。
     */
    static boolean isFood(Material m) {
        return FOODS.contains(m.name());
    }

    private static final java.util.Set<String> FOODS = java.util.Set.of(
            "BREAD", "APPLE", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "CARROT", "GOLDEN_CARROT",
            "POTATO", "BAKED_POTATO", "BEETROOT", "BEETROOT_SOUP", "MELON_SLICE", "SWEET_BERRIES",
            "GLOW_BERRIES", "COOKED_BEEF", "COOKED_PORKCHOP", "COOKED_CHICKEN", "COOKED_MUTTON",
            "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON", "BEEF", "PORKCHOP", "CHICKEN", "MUTTON",
            "RABBIT", "COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "COOKIE", "PUMPKIN_PIE",
            "MUSHROOM_STEW", "RABBIT_STEW", "SUSPICIOUS_STEW", "DRIED_KELP", "ROTTEN_FLESH",
            "SPIDER_EYE", "POISONOUS_POTATO", "HONEY_BOTTLE", "MILK_BUCKET", "CHORUS_FRUIT");

    /** 拼给模型的完整提议简报（systemNote 形式，模型据此表态） */
    public static String brief(String playerName, VillagerProfession prof,
                                 Material npcMat, Material offerMat, int offerCount,
                                 boolean hungryUrgent) {
        String npcZh = zh(npcMat);
        String offerZh = zh(offerMat);
        double hisValue = PriceBook.fairValue(npcMat);
        double offerValue = PriceBook.fairValue(offerMat) * Math.max(1, offerCount);
        return "（玩家 " + playerName + " 在你的背包里看中了「" + npcZh + "」，想用他背包里的「"
                + offerZh + "」×" + offerCount + " 跟你换这 1 个「" + npcZh + "」。\n"
                + "系统已经替你算好账：你的「" + npcZh + "」约值 " + fmt(hisValue) + " 块绿宝石，"
                + "他出的「" + offerZh + "」×" + offerCount + " 约值 " + fmt(offerValue) + " 块绿宝石——"
                + ratioTier(offerValue, hisValue) + "。\n"
                + "对你来说：" + usefulness(prof, offerMat, hungryUrgent) + "。\n"
                + "换不换由你按自己的性格和跟他的交情定：愿意换，就调用 accept_barter，"
                + "并在 message 里说一句中文台词，系统会当场把两边的东西换过来；"
                + "不愿意，就直接用一句台词拒绝，不要调用工具，也不要口头答应。）";
    }

    /** 中文名走官方全表（ItemZhTable 按材料名查，不碰注册表，离线可测） */
    private static String zh(Material m) {
        String z = ItemZhTable.zhOf(m);
        return z != null ? z : m.name().toLowerCase().replace('_', ' ');
    }

    private static String fmt(double v) {
        if (v >= 10) return String.format("%.0f", v);
        if (v >= 1) return String.format("%.1f", v);
        return String.format("%.2f", v);
    }
}
