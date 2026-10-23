package com.howard.mcai.nav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 代驾接管段回归（Fong 2026-10-23）：自研只接管到特殊段（水/梯子）后的
 * 第一个普通格就交还原版；没特殊段只拨正几个路点，方向盘不抢。
 */
class TakeoverSegmentsTest {

    private static final Byte W = NavGrid.WALK;
    private static final Byte D = NavGrid.DOOR;
    private static final Byte WA = NavGrid.WATER;
    private static final Byte C = NavGrid.CLIMB;

    @Test
    void emptyPathHasNoTakeover() {
        assertEquals(-1, TakeoverSegments.handoverIndex(List.of()));
        assertEquals(-1, TakeoverSegments.handoverIndex(null));
    }

    @Test
    void noSpecialSegmentOnlyUnsticksAFewWaypoints() {
        // 全是普通路：长路只拨正 6 个路点（下标 5），短路开到尽头
        assertEquals(5, TakeoverSegments.handoverIndex(List.of(W, W, W, W, W, W, W, W, W, W)));
        assertEquals(2, TakeoverSegments.handoverIndex(List.of(W, W, W)));
        // 门不算特殊路段：原版村民自己会开木门
        assertEquals(2, TakeoverSegments.handoverIndex(List.of(W, D, W)));
    }

    @Test
    void specialSegmentInMiddleHandsBackAfterFirstNormalCell() {
        // 走、走、爬、爬、走、走：开到下标 4（特殊段后第一个普通格）交还
        assertEquals(4, TakeoverSegments.handoverIndex(List.of(W, W, C, C, W, W)));
        // 水路同理
        assertEquals(3, TakeoverSegments.handoverIndex(List.of(W, WA, WA, W, W)));
        // 水接梯子算同一段特殊路
        assertEquals(3, TakeoverSegments.handoverIndex(List.of(W, WA, C, W)));
    }

    @Test
    void specialSegmentAtEdges() {
        // 一开局就爬梯子：开过梯子口的第一个普通格
        assertEquals(1, TakeoverSegments.handoverIndex(List.of(C, W, W)));
        // 特殊段顶到尽头：开到最后一个路点
        assertEquals(3, TakeoverSegments.handoverIndex(List.of(W, W, WA, WA)));
        // 全程都是水：开到尽头
        assertEquals(2, TakeoverSegments.handoverIndex(List.of(WA, WA, WA)));
        // 只有一个格
        assertEquals(0, TakeoverSegments.handoverIndex(List.of(C)));
    }
}
