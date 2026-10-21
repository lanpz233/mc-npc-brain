package com.howard.mcai.mob;

import java.util.Random;

/**
 * 村民收购钱包的金额规则（纯计算，方便单测）。
 *
 * <p>v0.1.3 按 Fong 的要求把最终金额提高到 v0.1.2 的 3 倍：
 * 有手艺的村民从 12–30 块绿宝石提到 36–90 块；没职业/傻子从
 * 3–10 块提到 9–30 块。这里的“翻两倍”按金额增加 200%、
 * 最终变成原来的 3 倍执行。</p>
 */
public final class WalletAmounts {

    private WalletAmounts() {
    }

    public static boolean hasTradeProfession(VillagerProfession profession) {
        return profession != null
                && profession != VillagerProfession.NONE
                && profession != VillagerProfession.NITWIT;
    }

    public static int minimum(VillagerProfession profession) {
        return hasTradeProfession(profession) ? 36 : 9;
    }

    public static int maximum(VillagerProfession profession) {
        return hasTradeProfession(profession) ? 90 : 30;
    }

    /** 新村民的启动钱，或老村民升级补足时使用的目标余额。 */
    public static int seedAmount(VillagerProfession profession, Random rng) {
        int min = minimum(profession);
        int max = maximum(profession);
        return min + rng.nextInt(max - min + 1);
    }

    /**
     * 老村民升级时应补多少：先按新标准抽一个目标余额，
     * 当前余额已经高于目标时不扣钱、也不额外再发。
     */
    public static int topUpAmount(VillagerProfession profession, int currentBalance, Random rng) {
        int target = seedAmount(profession, rng);
        return Math.max(0, target - Math.max(0, currentBalance));
    }
}
