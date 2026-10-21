package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 职业徽章字形映射回归：与 resourcepack 字体 mcai_shop.json 的徽章字形
 * （\uE120 通用、\uE121 起按职业）一一对应、互不重复。
 */
class ProfessionBadgeTest {

    @Test
    void everyProfessionHasDistinctBadgeInRange() {
        Set<Character> seen = new HashSet<>();
        for (VillagerProfession p : VillagerProfession.values()) {
            String badge = ProfessionBadge.badgeOf(p);
            assertEquals(1, badge.length(), p + " 徽章应为单字符");
            char c = badge.charAt(0);
            assertTrue(c >= (char) 0xE120 && c <= (char) 0xE12E,
                    p + " 徽章字形应在 E120~E12E 段内");
            assertTrue(seen.add(c), p + " 徽章与别的職業重复了");
        }
    }

    @Test
    void nullAndNoneUseGenericBadge() {
        assertEquals((char) 0xE120, ProfessionBadge.badgeOf(null).charAt(0));
        assertEquals((char) 0xE120, ProfessionBadge.badgeOf(VillagerProfession.NONE).charAt(0));
    }

    @Test
    void farmerIsFirstProfessionBadge() {
        assertEquals((char) 0xE121, ProfessionBadge.badgeOf(VillagerProfession.FARMER).charAt(0));
    }
}
