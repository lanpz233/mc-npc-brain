package com.howard.mcai.security;

/**
 * 砍价确定性裁决 —— 交易界面（路线二）的价格底线由插件守，模型与玩家都改不了。
 * <p>
 * 玩家在商店界面里**自己选砍几块**（整数绿宝石，不用百分比折扣 —— 货币是整数，
 * 百分比会砍出小数，Fong 2026-10-21 定）。插件只回答两个问题：
 * <ol>
 *   <li>以这段关系，这个村民最多能让几块（{@link #maxCut}）；</li>
 *   <li>玩家想砍的数在不在让价额度内（{@link #decide}）—— 在内按玩家要的价成交；
 *       超了不直接拒绝，而是还价到底线价，玩家接受才成交。</li>
 * </ol>
 * 全部整数运算、纯函数、无副作用：同一局面永远同一结果，可单测。
 */
public final class BargainPolicy {

    private BargainPolicy() {
    }

    /**
     * 砍价裁决结果。
     *
     * @param accepted    玩家要的砍价是否在让价额度内（true = 按玩家要的价成交）
     * @param agreedPrice accepted 时的成交价；不 accepted 时这是村民的还价（= 底线价），
     *                    玩家在界面点「接受」后以此价成交
     * @param floorPrice  底线价（标价 − 最大让价）
     */
    public record Outcome(boolean accepted, int agreedPrice, int floorPrice) {
    }

    /**
     * 最大让价（整数绿宝石）—— 关系越好让得越多：
     * <ul>
     *   <li>生人（好感 &lt;10）：最多让 1 块（象征性让步）</li>
     *   <li>熟人（10–29）：1 + 标价的 1/5</li>
     *   <li>交情（30–59）：2 + 标价的 1/4</li>
     *   <li>亲信（≥60）：3 + 标价的 1/3</li>
     * </ul>
     * 底线：成交价不得低于 1 块；标价 ≤1 时不让价。
     * （档位是插件规矩，写死在此一处，要调只调这里。）
     */
    public static int maxCut(int listPrice, double favor) {
        if (listPrice <= 1) return 0;
        int cut;
        if (favor < 10) {
            cut = 1;
        } else if (favor < 30) {
            cut = 1 + listPrice / 5;
        } else if (favor < 60) {
            cut = 2 + listPrice / 4;
        } else {
            cut = 3 + listPrice / 3;
        }
        // 成交价至少 1 块：再亲也不能把价格砍穿
        return Math.min(cut, listPrice - 1);
    }

    /** 底线价：这个村民对这个玩家最少要收多少 */
    public static int floorPrice(int listPrice, double favor) {
        return listPrice - maxCut(listPrice, favor);
    }

    /**
     * 裁决一次砍价。
     *
     * @param listPrice   标价（整数绿宝石，>0）
     * @param favor       该玩家对该 NPC 的好感
     * @param requestedCut 玩家想砍掉的块数（≤0 视为不砍价，按标价成交）
     */
    public static Outcome decide(int listPrice, double favor, int requestedCut) {
        int floor = floorPrice(listPrice, favor);
        if (requestedCut <= 0) {
            return new Outcome(true, listPrice, floor);
        }
        int wanted = listPrice - requestedCut;
        if (wanted >= floor) {
            return new Outcome(true, Math.max(wanted, 1), floor);
        }
        // 砍超了：不直接拒绝，还价到底线价，玩家接受才成交
        return new Outcome(false, floor, floor);
    }

    // ===== 卖货方向（玩家卖、村民收）：抬价裁决，与砍价镜像（Fong 2026-10-21 要求卖东西也能谈价） =====

    /**
     * 抬价裁决结果。
     *
     * @param accepted  玩家要的抬幅是否在村民肯多付的额度内（true = 按玩家要的价收）
     * @param agreedPay accepted 时的收购价；不 accepted 时这是村民的还价（= 他肯出的最高价），
     *                  玩家在界面点「接受」后以此价成交
     * @param maxPay    他肯出的最高价（标价 + 最大抬幅；未计钱包余额，余额由界面/结算再夹一道）
     */
    public record SellOutcome(boolean accepted, int agreedPay, int maxPay) {
    }

    /**
     * 最大抬幅（整数绿宝石）—— 与 {@link #maxCut} 同档位，关系越好村民越肯多付：
     * 生人 1、熟人 1 + 标价/5、交情 2 + 标价/4、亲信 3 + 标价/3（标价 = 他原肯出的数）。
     */
    public static int maxRaise(int listPay, double favor) {
        if (listPay <= 0) return 0;
        if (favor < 10) return 1;
        if (favor < 30) return 1 + listPay / 5;
        if (favor < 60) return 2 + listPay / 4;
        return 3 + listPay / 3;
    }

    /** 他肯出的最高价（不考虑钱包余额） */
    public static int maxPay(int listPay, double favor) {
        return listPay + maxRaise(listPay, favor);
    }

    /**
     * 裁决一次抬价。
     *
     * @param listPay        标价（他原肯出的绿宝石，>0）
     * @param favor          好感
     * @param requestedRaise 玩家想多要的块数（≤0 视为不抬价，按标价收）
     */
    public static SellOutcome decideSell(int listPay, double favor, int requestedRaise) {
        int cap = maxPay(listPay, favor);
        if (requestedRaise <= 0) {
            return new SellOutcome(true, listPay, cap);
        }
        int wanted = listPay + requestedRaise;
        if (wanted <= cap) {
            return new SellOutcome(true, wanted, cap);
        }
        // 抬超了：不直接拒绝，还价到他肯出的最高价，玩家接受才成交
        return new SellOutcome(false, cap, cap);
    }
}
