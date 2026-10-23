package com.howard.mcai.ai;

import java.util.Map;

/**
 * 商品档次（Fong 2026-10-23 定）—— 纯计算（只认物品名），可单测。
 * <p>
 * 配合 {@link TradeLevels}：村民买卖等级是几级，摊上最高就出几档的货。
 * 分档照原版的材质梯子走：
 * <ul>
 *   <li>0 档：基础原材料与日常杂货（木头、木棍、石头、食物、材料……）</li>
 *   <li>1 档：木质 / 石质工具、皮质装备、弓、盾、钓竿</li>
 *   <li>2 档：铁质 / 金质 / 锁链装备与工具，附魔书与稀有小物（图腾除外）</li>
 *   <li>3 档：钻石装备与工具、图腾、附魔台</li>
 *   <li>4 档：下界合金装备与工具、附魔金苹果</li>
 * </ul>
 * 名字前缀能判的走前缀（DIAMOND_ 等），判不了的查例外表，其余一律 0 档：
 * 货池里新增的普通材料不用改代码就自动是 0 档，只有「看着普通其实稀有」
 * 的东西才需要往例外表里加一行。
 */
public final class ShopTier {

    private ShopTier() {
    }

    /** 例外表：名字前缀判不出真实身价的物品 */
    private static final Map<String, Integer> OVERRIDES = Map.ofEntries(
            Map.entry("ENCHANTED_BOOK", 2),
            Map.entry("ENCHANTED_GOLDEN_APPLE", 4),
            Map.entry("TOTEM_OF_UNDYING", 3),
            Map.entry("ENCHANTING_TABLE", 3),
            Map.entry("EXPERIENCE_BOTTLE", 2),
            Map.entry("GOLDEN_APPLE", 2),
            Map.entry("NAME_TAG", 2),
            Map.entry("SADDLE", 2),
            Map.entry("ANVIL", 2),
            Map.entry("BLAZE_ROD", 2),
            Map.entry("NAUTILUS_SHELL", 2),
            Map.entry("CLOCK", 2),
            Map.entry("COMPASS", 2),
            Map.entry("SHEARS", 2),
            Map.entry("BUCKET", 2),
            Map.entry("CROSSBOW", 2),
            Map.entry("SPECTRAL_ARROW", 1),
            Map.entry("BOW", 1),
            Map.entry("FISHING_ROD", 1),
            Map.entry("SHIELD", 1),
            Map.entry("RABBIT_STEW", 1));

    /** 某物品是几档（0~4） */
    public static int tierOf(String materialName) {
        if (materialName == null || materialName.isBlank()) return 0;
        String n = materialName.toUpperCase().replace("MINECRAFT:", "");
        Integer o = OVERRIDES.get(n);
        if (o != null) return o;
        if (n.startsWith("NETHERITE_")) return 4;
        if (n.startsWith("DIAMOND_")) return 3;
        if (n.startsWith("IRON_") || n.startsWith("GOLDEN_") || n.startsWith("CHAINMAIL_")) return 2;
        if (n.startsWith("LEATHER_") || n.startsWith("WOODEN_") || n.startsWith("STONE_")) return 1;
        return 0;
    }
}
