package com.howard.mcai.ai;

import java.util.Random;

/**
 * 买卖等级（Fong 2026-10-23 定，仿原版村民交易等级）—— 纯计算，可单测。
 * <p>
 * 规矩：
 * <ul>
 *   <li>等级 0~4，决定村民摊上能出多高档的货（分档见 {@link ShopTier}）：
 *       0 级原材料、1 级木石皮、2 级金铁、3 级钻石、4 级下界合金。</li>
 *   <li>跟他成交攒熟练：累计笔数到了门槛就升级，只升不降。</li>
 *   <li>出生等级随机：0 级最多、1 级次之、2 级少量、3 级极少；
 *       <b>4 级不出生，只能靠交易一笔笔养出来</b> —— 全村第一个满级
 *       村民应该是个事件，不该是出生彩票。</li>
 *   <li>有效等级 = max(出生等级, 笔数推算等级)：出生 3 级的老师傅不会
 *       因为没人买东西就掉回 0 级。</li>
 * </ul>
 */
public final class TradeLevels {

    private TradeLevels() {
    }

    public static final int MAX_LEVEL = 4;

    /** 累计成交笔数达到多少升到对应等级（下标 = 等级，0 级无门槛） */
    private static final int[] TRADES_TO_REACH = {0, 6, 16, 32, 56};

    /** 累计成交笔数能推算到的等级 */
    public static int levelForTrades(int totalTrades) {
        int level = 0;
        for (int i = 1; i <= MAX_LEVEL; i++) {
            if (totalTrades >= TRADES_TO_REACH[i]) level = i;
        }
        return level;
    }

    /** 出生等级抽签：0 最多、1 次之、2 少量、3 极少，4 永不出生 */
    public static int initialLevel(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.45) return 0;
        if (r < 0.75) return 1;
        if (r < 0.92) return 2;
        return 3;
    }

    /** 从当前有效等级升到下一级还差几笔成交；已满级返回 -1 */
    public static int tradesToNextLevel(int totalTrades, int effectiveLevel) {
        if (effectiveLevel >= MAX_LEVEL) return -1;
        return Math.max(0, TRADES_TO_REACH[effectiveLevel + 1] - totalTrades);
    }
}
