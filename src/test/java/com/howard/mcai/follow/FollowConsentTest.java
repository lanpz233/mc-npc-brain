package com.howard.mcai.follow;

import org.junit.jupiter.api.Test;

import static com.howard.mcai.follow.FollowConsent.Decision.AGREE;
import static com.howard.mcai.follow.FollowConsent.Decision.AGREE_GRUDGING;
import static com.howard.mcai.follow.FollowConsent.Decision.REFUSE_BUSY;
import static com.howard.mcai.follow.FollowConsent.Decision.REFUSE_FOLLOWING_OTHER;
import static com.howard.mcai.follow.FollowConsent.Decision.REFUSE_NEED;
import static com.howard.mcai.follow.FollowConsent.Decision.REFUSE_SCARED;
import static com.howard.mcai.follow.FollowConsent.Decision.REFUSE_SLEEPING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跟随裁定的离线回归（服主定的规矩）：闲着就答应；忙/睡/怕/迫切需求拒绝；
 * 被磨够次数不情愿答应；正跟着别人走谁也磨不动。
 */
class FollowConsentTest {

    @Test
    void idleVillagerAgrees() {
        assertEquals(AGREE, FollowConsent.decide(false, false, false, 0, 10, 0));
    }

    @Test
    void busySleepsScaredNeedyRefuse() {
        assertEquals(REFUSE_BUSY, FollowConsent.decide(false, true, false, 0, 0, 0));
        assertEquals(REFUSE_SLEEPING, FollowConsent.decide(true, false, false, 0, 0, 0));
        assertEquals(REFUSE_SCARED, FollowConsent.decide(false, false, false, 60, 0, 0));
        assertEquals(REFUSE_NEED, FollowConsent.decide(false, false, false, 0, 85, 0));
    }

    @Test
    void pesteringEventuallyWins() {
        // 忙着也架不住磨：被拒攒到阈值就改口（不情愿版）
        assertEquals(AGREE_GRUDGING, FollowConsent.decide(false, true, false, 0, 0, 3));
        assertEquals(AGREE_GRUDGING, FollowConsent.decide(true, false, false, 0, 0, 5));
        // 还没攒够时照常拒绝
        assertEquals(REFUSE_BUSY, FollowConsent.decide(false, true, false, 0, 0, 2));
    }

    @Test
    void followingSomeoneElseIsUnpesterable() {
        assertEquals(REFUSE_FOLLOWING_OTHER, FollowConsent.decide(false, false, true, 0, 0, 99));
        assertFalse(FollowConsent.overridable(REFUSE_FOLLOWING_OTHER));
        assertTrue(FollowConsent.overridable(REFUSE_BUSY));
        assertTrue(FollowConsent.overridable(REFUSE_SLEEPING));
    }

    @Test
    void sleepingBeatsBusyInReasonPriority() {
        // 又睡又忙时，先报“睡着了”（界面提示要对得上玩家眼睛看到的）
        assertEquals(REFUSE_SLEEPING, FollowConsent.decide(true, true, false, 0, 0, 0));
    }
}
