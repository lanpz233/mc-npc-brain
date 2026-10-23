package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 商品档次回归（Fong 2026-10-23）：低级摊只能出木石原材料，
 * 钻石要 3 档、下界合金要 4 档，别再满摊高级货。
 */
class ShopTierTest {

    @Test
    void rawMaterialsAreTierZero() {
        assertEquals(0, ShopTier.tierOf("OAK_LOG"));
        assertEquals(0, ShopTier.tierOf("WHEAT"));
        assertEquals(0, ShopTier.tierOf("STICK"));
        assertEquals(0, ShopTier.tierOf("BREAD"));
        assertEquals(0, ShopTier.tierOf("COBBLESTONE"));
        assertEquals(0, ShopTier.tierOf(null));
    }

    @Test
    void woodStoneLeatherAreTierOne() {
        assertEquals(1, ShopTier.tierOf("WOODEN_SWORD"));
        assertEquals(1, ShopTier.tierOf("STONE_PICKAXE"));
        assertEquals(1, ShopTier.tierOf("LEATHER_CHESTPLATE"));
        assertEquals(1, ShopTier.tierOf("BOW"));
        assertEquals(1, ShopTier.tierOf("SHIELD"));
        assertEquals(1, ShopTier.tierOf("FISHING_ROD"));
    }

    @Test
    void ironGoldChainAreTierTwo() {
        assertEquals(2, ShopTier.tierOf("IRON_SWORD"));
        assertEquals(2, ShopTier.tierOf("GOLDEN_HELMET"));
        assertEquals(2, ShopTier.tierOf("CHAINMAIL_BOOTS"));
        assertEquals(2, ShopTier.tierOf("ENCHANTED_BOOK"));
        assertEquals(2, ShopTier.tierOf("GOLDEN_APPLE"));
        assertEquals(2, ShopTier.tierOf("ANVIL"));
        assertEquals(2, ShopTier.tierOf("CROSSBOW"));
    }

    @Test
    void diamondAndRareAreTierThree() {
        assertEquals(3, ShopTier.tierOf("DIAMOND_SWORD"));
        assertEquals(3, ShopTier.tierOf("DIAMOND_CHESTPLATE"));
        assertEquals(3, ShopTier.tierOf("TOTEM_OF_UNDYING"));
        assertEquals(3, ShopTier.tierOf("ENCHANTING_TABLE"));
    }

    @Test
    void netheriteIsTierFour() {
        assertEquals(4, ShopTier.tierOf("NETHERITE_SWORD"));
        assertEquals(4, ShopTier.tierOf("NETHERITE_CHESTPLATE"));
        assertEquals(4, ShopTier.tierOf("ENCHANTED_GOLDEN_APPLE"));
    }
}
