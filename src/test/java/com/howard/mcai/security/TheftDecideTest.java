package com.howard.mcai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** v0.4.2：偷窃结算判定矩阵（警觉期概率规则） */
class TheftDecideTest {

    @Test
    void exposedChances() {
        // 没跑掉：平时 85% 被抓；警觉时必抓
        assertEquals(TheftService.Outcome.CAUGHT, TheftService.decide(true, false, 0.84));
        assertEquals(TheftService.Outcome.SUCCESS, TheftService.decide(true, false, 0.86));
        assertEquals(TheftService.Outcome.CAUGHT, TheftService.decide(true, true, 0.999));
    }

    @Test
    void escapedChances() {
        // 跑掉了：平时 10% 触发警觉（东西照样到手）；警觉时 50% 被认出
        assertEquals(TheftService.Outcome.ALERTED, TheftService.decide(false, false, 0.05));
        assertEquals(TheftService.Outcome.SUCCESS, TheftService.decide(false, false, 0.5));
        assertEquals(TheftService.Outcome.RECOGNIZED, TheftService.decide(false, true, 0.4));
        assertEquals(TheftService.Outcome.SUCCESS, TheftService.decide(false, true, 0.6));
    }
}
