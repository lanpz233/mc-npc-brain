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
    void prependBadgeKeepsContentFontUntouched() {
        // 2026-10-21 乱码事故：台词被 append 到徽章组件上当子组件，继承了 mcai_shop
        // 字体（无汉字）→ 整句方块。这里锁死平级拼装：根无字体、内容无字体、只有徽章带字体。
        net.kyori.adventure.text.Component line =
                net.kyori.adventure.text.Component.text("[李江>lanpz] 今天天气不错");
        net.kyori.adventure.text.Component root =
                ProfessionBadge.prependBadge(line, VillagerProfession.FARMER);
        org.junit.jupiter.api.Assertions.assertNull(root.font(), "根组件不得带字体");
        org.junit.jupiter.api.Assertions.assertEquals(3, root.children().size());
        var badge = root.children().get(0);
        org.junit.jupiter.api.Assertions.assertEquals(
                net.kyori.adventure.key.Key.key("minecraft", "mcai_shop"), badge.font(),
                "只有徽章子组件带 mcai_shop 字体");
        var content = root.children().get(2);
        org.junit.jupiter.api.Assertions.assertNull(content.font(),
                "内容子组件不得被徽章字体污染");
        org.junit.jupiter.api.Assertions.assertEquals(
                "[李江>lanpz] 今天天气不错",
                ((net.kyori.adventure.text.TextComponent) content).content());
    }

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
