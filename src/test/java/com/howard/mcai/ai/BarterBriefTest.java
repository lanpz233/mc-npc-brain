package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** v0.4.1：换物简报的算账口径（价值档位 + 职业挂钩的有利程度） */
class BarterBriefTest {

    @Test
    void ratioTiers() {
        assertTrue(BarterBrief.ratioTier(13, 10).contains("占了便宜"));   // 1.3 倍
        assertTrue(BarterBrief.ratioTier(10, 10).contains("公道"));       // 等值
        assertTrue(BarterBrief.ratioTier(7, 10).contains("有点吃亏"));    // 0.7
        assertTrue(BarterBrief.ratioTier(3, 10).contains("吃大亏"));      // 0.3
        assertTrue(BarterBrief.ratioTier(5, 0).contains("不值钱"));       // 他的东西没价
    }

    @Test
    void usefulnessFollowsProfession() {
        // 绿宝石：硬通货
        assertTrue(BarterBrief.usefulness(VillagerProfession.FARMER, Material.EMERALD, false)
                .contains("硬通货"));
        // 农夫收小麦（收购池）→ 用得上
        assertTrue(BarterBrief.usefulness(VillagerProfession.FARMER, Material.WHEAT, false)
                .contains("用得上"));
        // 农夫卖面包（货池）→ 不缺
        assertTrue(BarterBrief.usefulness(VillagerProfession.FARMER, Material.BREAD, false)
                .contains("不缺"));
        // 不搭边的东西
        assertTrue(BarterBrief.usefulness(VillagerProfession.FARMER, Material.GLOWSTONE, false)
                .contains("用不上"));
        // 饿的时候：吃的优先于“不缺”判断（面包既在他货池、又是吃的）
        assertTrue(BarterBrief.usefulness(VillagerProfession.FARMER, Material.BREAD, true)
                .contains("正是时候"));
    }

    @Test
    void briefContainsBothValuesAndInstruction() {
        String b = BarterBrief.brief("lanpz", VillagerProfession.FARMER,
                Material.BREAD, Material.WHEAT, 20, false);
        assertTrue(b.contains("面包"));
        assertTrue(b.contains("小麦"));
        assertTrue(b.contains("accept_barter"));
        assertTrue(b.contains("约值"));
    }
}
