package com.howard.mcai.nav;

import java.util.List;

/**
 * 代驾接管段计算（Fong 2026-10-23 定）—— 纯逻辑，可单测。
 * <p>
 * 寻路分工：平时原版导航做主（上台阶、绕障、开门都是它的活）；原版走不通时
 * 才喊自研 A* 规划一条全程路线，但自研<b>只接管一小段</b>：开到第一段
 * 「特殊路段」（水里游、梯子上下 —— 原版腿的死角）结束后的第一个普通格为止，
 * 就把方向盘交还原版。整条路上没有特殊路段时（原版只是被什么绊了一下），
 * 自研只拨正前 {@link #UNSTICK_WAYPOINTS} 个路点再交还，不抢原版的活。
 * 方向盘每一刻只在一双手里 —— 这就是「原版 + 自研不打架」的实现方式。
 */
public final class TakeoverSegments {

    private TakeoverSegments() {
    }

    /** 没有特殊路段时，自研「拨正」最多开几个路点就交还 */
    public static final int UNSTICK_WAYPOINTS = 6;

    /** 特殊路段格：水（要游）与梯子/藤蔓（要爬）—— 原版导航处理不了的两类 */
    public static boolean isSpecial(byte type) {
        return type == NavGrid.WATER || type == NavGrid.CLIMB;
    }

    /**
     * 给定全程路径逐路点的格子类型，算自研接管应开到哪个路点为止（含该点）。
     * <ul>
     *   <li>有特殊路段：第一段特殊路段结束后的第一个普通格；特殊段顶到
     *       路径尽头时，就是最后一个路点。</li>
     *   <li>没有特殊路段：前 {@link #UNSTICK_WAYPOINTS} 个路点（或尽头）。</li>
     *   <li>空路径返回 -1（无从接管）。</li>
     * </ul>
     */
    public static int handoverIndex(List<Byte> types) {
        if (types == null || types.isEmpty()) return -1;
        int last = types.size() - 1;
        int firstSpecial = -1;
        for (int i = 0; i < types.size(); i++) {
            if (isSpecial(types.get(i))) {
                firstSpecial = i;
                break;
            }
        }
        if (firstSpecial < 0) return Math.min(last, UNSTICK_WAYPOINTS - 1);
        int end = firstSpecial;
        while (end + 1 < types.size() && isSpecial(types.get(end + 1))) end++;
        // 交还点含特殊段后的第一个普通格：人要稳稳站回普通地面才算交接完成
        return Math.min(end + 1, last);
    }
}
