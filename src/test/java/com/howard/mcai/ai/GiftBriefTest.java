package com.howard.mcai.ai;

import com.howard.mcai.security.GiftPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赠送判定简报（2026-10-26 第 2 档）：结论必须与 give_item 的门控裁决同源，
 * 简报文本只许定性词、不许出现好感数字（防村民把数字念出戏）。
 */
class GiftBriefTest {

    // ===== 典型组合：结论与门控阈值一致（门槛常量以 GiftPolicy 为准） =====

    @Test
    void strangerCanGetNothing() {
        // 生分（< 普通门槛）：普通、贵重都不能白送
        assertFalse(GiftBrief.commonGiftOk(5, false));
        assertFalse(GiftBrief.rareGiftOk(5, false));
        assertFalse(GiftBrief.commonGiftOk(-30, false));
        assertFalse(GiftBrief.rareGiftOk(-30, false));
    }

    @Test
    void familiarCanGetCommonButNotRare() {
        // 面熟／处得不错（普通门槛 ≤ 好感 < 稀有门槛）：普通可送、贵重不可送
        assertTrue(GiftBrief.commonGiftOk(GiftPolicy.COMMON_FAVOR_MIN, false));
        assertFalse(GiftBrief.rareGiftOk(GiftPolicy.COMMON_FAVOR_MIN, false));
        assertTrue(GiftBrief.commonGiftOk(30, false));
        assertFalse(GiftBrief.rareGiftOk(30, false));
        assertTrue(GiftBrief.commonGiftOk(59.9, false));
        assertFalse(GiftBrief.rareGiftOk(59.9, false));
    }

    @Test
    void confidantCanGetBoth() {
        // 交情很深（≥ 稀有门槛）：普通、贵重都能白送
        assertTrue(GiftBrief.commonGiftOk(GiftPolicy.RARE_FAVOR_MIN, false));
        assertTrue(GiftBrief.rareGiftOk(GiftPolicy.RARE_FAVOR_MIN, false));
        assertTrue(GiftBrief.rareGiftOk(120, false));
    }

    @Test
    void envoyExemptFromFavor() {
        // 神使全豁免（与 GiftPolicy.decide 的神使口径一致）
        assertTrue(GiftBrief.commonGiftOk(-100, true));
        assertTrue(GiftBrief.rareGiftOk(-100, true));
    }

    @Test
    void verdictsMatchGiftPolicyAcrossSweep() {
        // 全程对表：任意好感／身份下，简报结论必须等于门控本人的裁决，不许漂移
        double[] favors = {-50, -10, 0, 5, 9.9, 10, 29, 59.9, 60, 90, 150};
        for (double favor : favors) {
            for (boolean envoy : new boolean[]{false, true}) {
                assertEquals(GiftPolicy.willingToGive(favor, 0, envoy, false),
                        GiftBrief.commonGiftOk(favor, envoy), "common favor=" + favor + " envoy=" + envoy);
                assertEquals(GiftPolicy.willingToGive(favor, GiftPolicy.RARE_VALUE_MIN, envoy, false),
                        GiftBrief.rareGiftOk(favor, envoy), "rare favor=" + favor + " envoy=" + envoy);
            }
        }
    }

    // ===== 简报文案：档位词对、结论对、绝不带数字 =====

    @Test
    void briefForStrangerDeniesBothWithTierWord() {
        String brief = GiftBrief.brief(5, false);
        assertTrue(brief.contains("「生分」"), brief);
        assertTrue(brief.contains("普通东西还不能"), brief);
        assertTrue(brief.contains("贵重东西（铁、绿宝石、钻石一类值钱货）还不能"), brief);
        assertTrue(brief.contains("婉拒"), brief);
    }

    @Test
    void briefForFamiliarAllowsCommonOnly() {
        String brief = GiftBrief.brief(20, false);
        assertTrue(brief.contains("「面熟」"), brief);
        assertTrue(brief.contains("普通东西可以无偿送给他"), brief);
        assertTrue(brief.contains("贵重东西（铁、绿宝石、钻石一类值钱货）还不能"), brief);
        assertTrue(brief.contains("讨要贵重东西"), brief);
    }

    @Test
    void briefForConfidantAllowsBothWithoutRefusalHint() {
        String brief = GiftBrief.brief(70, false);
        assertTrue(brief.contains("「交情很深」"), brief);
        assertTrue(brief.contains("普通东西可以无偿送给他"), brief);
        assertTrue(brief.contains("贵重东西（铁、绿宝石、钻石一类值钱货）可以无偿送给他"), brief);
        assertFalse(brief.contains("婉拒"), brief);
    }

    @Test
    void briefNeverContainsFavorNumbers() {
        double[] favors = {-50, 0, 5, 10, 30, 60, 90, 150};
        for (double favor : favors) {
            for (boolean envoy : new boolean[]{false, true}) {
                String brief = GiftBrief.brief(favor, envoy);
                assertFalse(brief.chars().anyMatch(Character::isDigit),
                        "简报不许带数字: favor=" + favor + " -> " + brief);
                assertFalse(brief.contains("≥"), brief);
                assertFalse(brief.contains("好感"), brief);
            }
        }
    }
}
