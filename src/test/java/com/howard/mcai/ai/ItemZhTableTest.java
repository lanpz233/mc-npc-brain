package com.howard.mcai.ai;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 物品官方中文名全表 —— 治商店漏英文（stone slab / stone bricks / bricks 实测截图） */
class ItemZhTableTest {

    @Test
    void screenshotItemsAreChinese() {
        assertEquals("石头台阶", ItemZhTable.zhOf(Material.STONE_SLAB));
        assertEquals("石砖", ItemZhTable.zhOf(Material.STONE_BRICKS));
        assertEquals("红砖块", ItemZhTable.zhOf(Material.BRICKS));
    }

    @Test
    void commonItems() {
        assertEquals("绿宝石", ItemZhTable.zhOf(Material.EMERALD));
        assertEquals("面包", ItemZhTable.zhOf(Material.BREAD));
        assertEquals("钻石镐", ItemZhTable.zhOf(Material.DIAMOND_PICKAXE));
        assertEquals("铁剑", ItemZhTable.zhOf(Material.IRON_SWORD));
    }

    @Test
    void reverseLookupUnderstandsOfficialNames() {
        assertEquals(Material.BRICKS, ItemZhTable.materialOf("红砖块"));
        assertEquals(Material.STONE_SLAB, ItemZhTable.materialOf("石头台阶"));
        assertEquals(Material.EMERALD, ItemZhTable.materialOf("绿宝石"));
    }

    @Test
    void tableIsComplete() {
        assertTrue(ItemZhTable.size() >= 1400, "全表条数异常: " + ItemZhTable.size());
        // 抽查一批商店/日常会出现的物品都不许漏
        for (Material m : new Material[]{
                Material.WHEAT, Material.OAK_LOG, Material.COBBLESTONE, Material.GLASS,
                Material.ENCHANTING_TABLE, Material.TOTEM_OF_UNDYING, Material.COOKED_BEEF,
                Material.FISHING_ROD, Material.SHEARS, Material.BUCKET, Material.TORCH}) {
            assertNotNull(ItemZhTable.zhOf(m), "漏汉化: " + m);
        }
    }
}
