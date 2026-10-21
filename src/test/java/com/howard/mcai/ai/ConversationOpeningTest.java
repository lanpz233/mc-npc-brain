package com.howard.mcai.ai;

import com.howard.mcai.ai.ConversationOpening.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开场说明定档与文案：核心回归点是“刚聊完又回来不能被当成第一次见面”——
 * 定档只看事实（见没见过 + 上次结束时间），文案必须把档位意图说清，
 * 尤其是 RESUME 的禁止重新打招呼、FIRST_MEETING 才允许自我介绍。
 */
class ConversationOpeningTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60L * MIN;
    private static final long DAY = 24L * HOUR;

    // ===== 定档 =====

    @Test
    void neverMetIsFirstMeeting() {
        assertEquals(Tier.FIRST_MEETING, ConversationOpening.tier(false, 0L, NOW));
        // 就算有一条莫名的结束记录，没见过就是没见过
        assertEquals(Tier.FIRST_MEETING, ConversationOpening.tier(false, NOW - MIN, NOW));
    }

    @Test
    void metWithoutEndRecordIsAcquaintanceNotFirstMeeting() {
        // 旧存档 / 只通过非对话方式见过：认识但没结束时间 → 熟人档，绝不自我介绍
        assertEquals(Tier.RETURN_TODAY, ConversationOpening.tier(true, 0L, NOW));
    }

    @Test
    void justEndedIsResume() {
        assertEquals(Tier.RESUME, ConversationOpening.tier(true, NOW - 30_000L, NOW));
        assertEquals(Tier.RESUME, ConversationOpening.tier(true, NOW - 5L * MIN, NOW));
    }

    @Test
    void withinHourIsReturnSoon() {
        assertEquals(Tier.RETURN_SOON, ConversationOpening.tier(true, NOW - 6L * MIN, NOW));
        assertEquals(Tier.RETURN_SOON, ConversationOpening.tier(true, NOW - HOUR, NOW));
    }

    @Test
    void withinDayIsReturnToday() {
        assertEquals(Tier.RETURN_TODAY, ConversationOpening.tier(true, NOW - 3L * HOUR, NOW));
        assertEquals(Tier.RETURN_TODAY, ConversationOpening.tier(true, NOW - DAY, NOW));
    }

    @Test
    void overDayIsLongAbsence() {
        assertEquals(Tier.LONG_ABSENCE, ConversationOpening.tier(true, NOW - DAY - 1L, NOW));
        assertEquals(Tier.LONG_ABSENCE, ConversationOpening.tier(true, NOW - 30L * DAY, NOW));
    }

    @Test
    void clockSkewClampsToResume() {
        // 结束时间比现在还晚（改过系统时间等）→ 按刚结束处理，不能算出负间隔乱档
        assertEquals(Tier.RESUME, ConversationOpening.tier(true, NOW + HOUR, NOW));
    }

    // ===== 时长文案 =====

    @Test
    void formatsElapsed() {
        assertEquals("less than a minute", ConversationOpening.formatElapsed(30_000L));
        assertEquals("1 minute", ConversationOpening.formatElapsed(MIN));
        assertEquals("2 minutes", ConversationOpening.formatElapsed(2L * MIN));
        assertEquals("1 hour", ConversationOpening.formatElapsed(HOUR));
        assertEquals("3 hours", ConversationOpening.formatElapsed(3L * HOUR));
        assertEquals("1 day", ConversationOpening.formatElapsed(DAY));
        assertEquals("2 days", ConversationOpening.formatElapsed(2L * DAY));
    }

    // ===== 开场文案 =====

    @Test
    void firstMeetingNoteIntroduces() {
        String note = ConversationOpening.buildNote("Steve", "abcdef12",
                Tier.FIRST_MEETING, -1L, false);
        assertTrue(note.contains("Steve"));
        assertTrue(note.contains("player_abcdef12"));
        assertTrue(note.contains("FIRST time"));
        assertTrue(note.contains("Introduce yourself"));
    }

    @Test
    void resumeNoteForbidsRegreeting() {
        String note = ConversationOpening.buildNote("Steve", "abcdef12",
                Tier.RESUME, 2L * MIN, true);
        assertTrue(note.contains("SAME conversation"));
        assertTrue(note.contains("Do NOT greet them like a newcomer"));
        assertTrue(note.contains("chat history"));
        assertFalse(note.contains("FIRST time"));
        assertFalse(note.contains("Introduce yourself"));
    }

    @Test
    void resumeNoteWithoutHistoryOmitsHistoryClaim() {
        // 历史已被整理进 summary 时不能声称“历史就是那场对话”
        String note = ConversationOpening.buildNote("Steve", "abcdef12",
                Tier.RESUME, 2L * MIN, false);
        assertFalse(note.contains("chat history in this prompt"));
    }

    @Test
    void longAbsenceNoteIsOldAcquaintanceNotStranger() {
        String note = ConversationOpening.buildNote("Steve", "abcdef12",
                Tier.LONG_ABSENCE, 5L * DAY, true);
        assertTrue(note.contains("long time no see"));
        assertTrue(note.contains("5 days"));
        assertFalse(note.contains("FIRST time"));
    }

    @Test
    void returnTodayWithoutElapsedAvoidsInventedTime() {
        // 无结束记录的熟人档：不能编一个“多久之前”出来
        String note = ConversationOpening.buildNote("Steve", "abcdef12",
                Tier.RETURN_TODAY, -1L, false);
        assertTrue(note.contains("already knows you"));
        assertFalse(note.contains("ago"));
    }
}
