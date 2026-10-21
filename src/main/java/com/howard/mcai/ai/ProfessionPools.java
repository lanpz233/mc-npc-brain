package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 职业货池 —— 每个职业「卖什么、收什么」的范围（Fong 2026-10-21 定）。
 * <p>
 * 以前配方表就是全部：同职业每个村民卖得一模一样、数量也一样。现在配方表退位成
 * <b>货池</b>：池子按 MC 世界观/原版村民交易表设计（农夫卖田货、铁匠卖铁器……），
 * 每个村民由 {@link ShopStocking} 按自己的 UUID 从池里抽一摊（6~8 样卖货 + 2~3 样收购），
 * 所以同村两个农夫的摊子也不同。价格不在这里定 —— 全部由 {@link PriceBook} 按合成表推导。
 * <p>
 * 另有全村共享的<b>杂货池</b>：抽货时小概率混进 1~2 件「不属于本职业」的东西
 * （村民顺手捎卖的杂货），Fong 明确说可以偶尔出现。
 * <p>
 * 纯数据（Material + 每单个数），可单测。
 */
public final class ProfessionPools {

    private ProfessionPools() {
    }

    /** 池中一项：一单卖/收的物品与个数（价格由 PriceBook 按个数推导） */
    public record PoolItem(Material item, int count) {
    }

    private static PoolItem s(Material item, int count) {
        return new PoolItem(item, count);
    }

    private static final Map<VillagerProfession, List<PoolItem>> SELL = new EnumMap<>(VillagerProfession.class);
    private static final Map<VillagerProfession, List<PoolItem>> BUY = new EnumMap<>(VillagerProfession.class);

    static {
        // ===== 农夫：田里长的、厨房做的 =====
        SELL.put(VillagerProfession.FARMER, List.of(
                s(Material.BREAD, 6), s(Material.WHEAT, 8), s(Material.CARROT, 10),
                s(Material.POTATO, 10), s(Material.BAKED_POTATO, 6), s(Material.PUMPKIN_PIE, 4),
                s(Material.APPLE, 4), s(Material.GOLDEN_CARROT, 3), s(Material.GOLDEN_APPLE, 1),
                s(Material.COOKIE, 8), s(Material.MELON_SLICE, 8), s(Material.SWEET_BERRIES, 6),
                s(Material.HAY_BLOCK, 2), s(Material.BONE_MEAL, 8), s(Material.EGG, 6),
                s(Material.MILK_BUCKET, 1), s(Material.SUGAR, 8), s(Material.BEETROOT, 10)));
        BUY.put(VillagerProfession.FARMER, List.of(
                s(Material.WHEAT, 20), s(Material.CARROT, 22), s(Material.POTATO, 26),
                s(Material.BEETROOT, 15), s(Material.PUMPKIN, 6), s(Material.EGG, 12),
                s(Material.APPLE, 8)));

        // ===== 铁匠（工具匠）：铁器、工具、工作站 =====
        SELL.put(VillagerProfession.TOOLSMITH, List.of(
                s(Material.IRON_INGOT, 3), s(Material.IRON_BLOCK, 1), s(Material.IRON_PICKAXE, 1),
                s(Material.IRON_AXE, 1), s(Material.IRON_SHOVEL, 1), s(Material.IRON_HOE, 1),
                s(Material.STONE_PICKAXE, 1), s(Material.ANVIL, 1), s(Material.FURNACE, 1),
                s(Material.BLAST_FURNACE, 1), s(Material.SMOKER, 1), s(Material.COAL, 12),
                s(Material.FLINT, 10), s(Material.DIAMOND_PICKAXE, 1), s(Material.BUCKET, 1),
                s(Material.SHEARS, 1)));
        BUY.put(VillagerProfession.TOOLSMITH, List.of(
                s(Material.COAL, 15), s(Material.IRON_INGOT, 4), s(Material.COBBLESTONE, 16),
                s(Material.STONE, 16), s(Material.FLINT, 12)));

        // ===== 武器匠：刀剑弓弩 =====
        SELL.put(VillagerProfession.WEAPONSMITH, List.of(
                s(Material.IRON_SWORD, 1), s(Material.IRON_AXE, 1), s(Material.STONE_SWORD, 1),
                s(Material.DIAMOND_SWORD, 1), s(Material.DIAMOND_AXE, 1), s(Material.BOW, 1),
                s(Material.CROSSBOW, 1), s(Material.ARROW, 16), s(Material.SPECTRAL_ARROW, 6),
                s(Material.SHIELD, 1), s(Material.FLINT, 10)));
        BUY.put(VillagerProfession.WEAPONSMITH, List.of(
                s(Material.IRON_INGOT, 4), s(Material.FLINT, 12), s(Material.STRING, 10),
                s(Material.FEATHER, 10)));

        // ===== 盔甲匠：整套护甲 =====
        SELL.put(VillagerProfession.ARMORER, List.of(
                s(Material.IRON_HELMET, 1), s(Material.IRON_CHESTPLATE, 1),
                s(Material.IRON_LEGGINGS, 1), s(Material.IRON_BOOTS, 1),
                s(Material.CHAINMAIL_HELMET, 1), s(Material.CHAINMAIL_CHESTPLATE, 1),
                s(Material.CHAINMAIL_LEGGINGS, 1), s(Material.CHAINMAIL_BOOTS, 1),
                s(Material.DIAMOND_HELMET, 1), s(Material.DIAMOND_CHESTPLATE, 1),
                s(Material.DIAMOND_LEGGINGS, 1), s(Material.DIAMOND_BOOTS, 1),
                s(Material.SHIELD, 1)));
        BUY.put(VillagerProfession.ARMORER, List.of(
                s(Material.IRON_INGOT, 4), s(Material.COAL, 12), s(Material.LAVA_BUCKET, 1)));

        // ===== 牧师：神秘与药剂 =====
        SELL.put(VillagerProfession.CLERIC, List.of(
                s(Material.EXPERIENCE_BOTTLE, 4), s(Material.LAPIS_LAZULI, 6),
                s(Material.GLOWSTONE, 2), s(Material.ENDER_PEARL, 2), s(Material.GOLDEN_APPLE, 1),
                s(Material.ENCHANTING_TABLE, 1), s(Material.TOTEM_OF_UNDYING, 1),
                s(Material.REDSTONE, 8), s(Material.GLASS_BOTTLE, 6), s(Material.NETHER_WART, 4),
                s(Material.AMETHYST_SHARD, 3), s(Material.BLAZE_ROD, 1)));
        BUY.put(VillagerProfession.CLERIC, List.of(
                s(Material.ROTTEN_FLESH, 32), s(Material.GOLD_INGOT, 3), s(Material.BONE, 12),
                s(Material.SPIDER_EYE, 6), s(Material.GHAST_TEAR, 1)));

        // ===== 图书管理员：书、纸与知识小物 =====
        SELL.put(VillagerProfession.LIBRARIAN, List.of(
                s(Material.BOOK, 2), s(Material.PAPER, 6), s(Material.BOOKSHELF, 2),
                s(Material.LANTERN, 2), s(Material.GLASS, 6), s(Material.INK_SAC, 3),
                s(Material.WRITABLE_BOOK, 1), s(Material.CLOCK, 1), s(Material.COMPASS, 1),
                s(Material.NAME_TAG, 1), s(Material.ENCHANTING_TABLE, 1), s(Material.CANDLE, 4)));
        BUY.put(VillagerProfession.LIBRARIAN, List.of(
                s(Material.PAPER, 24), s(Material.BOOK, 4), s(Material.INK_SAC, 5)));

        // ===== 制图师：地图与探索 =====
        SELL.put(VillagerProfession.CARTOGRAPHER, List.of(
                s(Material.PAPER, 6), s(Material.MAP, 1), s(Material.COMPASS, 1),
                s(Material.ITEM_FRAME, 2), s(Material.GLOW_ITEM_FRAME, 1),
                s(Material.WHITE_BANNER, 1), s(Material.GLASS_PANE, 8), s(Material.LANTERN, 1)));
        BUY.put(VillagerProfession.CARTOGRAPHER, List.of(
                s(Material.PAPER, 24), s(Material.GLASS_PANE, 11), s(Material.COMPASS, 1)));

        // ===== 制箭师：弓箭原料与成品 =====
        SELL.put(VillagerProfession.FLETCHER, List.of(
                s(Material.BOW, 1), s(Material.CROSSBOW, 1), s(Material.ARROW, 16),
                s(Material.SPECTRAL_ARROW, 6), s(Material.FLINT, 12), s(Material.STRING, 8),
                s(Material.FEATHER, 8), s(Material.STICK, 16), s(Material.TRIPWIRE_HOOK, 4)));
        BUY.put(VillagerProfession.FLETCHER, List.of(
                s(Material.STICK, 32), s(Material.FLINT, 26), s(Material.FEATHER, 12),
                s(Material.STRING, 14)));

        // ===== 渔夫：水里的收获 =====
        SELL.put(VillagerProfession.FISHERMAN, List.of(
                s(Material.COD, 6), s(Material.SALMON, 4), s(Material.COOKED_COD, 5),
                s(Material.COOKED_SALMON, 4), s(Material.FISHING_ROD, 1), s(Material.BUCKET, 1),
                s(Material.COD_BUCKET, 1), s(Material.TROPICAL_FISH, 3), s(Material.PUFFERFISH, 2),
                s(Material.NAUTILUS_SHELL, 1), s(Material.KELP, 12), s(Material.STRING, 6),
                s(Material.DRIED_KELP, 8)));
        BUY.put(VillagerProfession.FISHERMAN, List.of(
                s(Material.STRING, 10), s(Material.COAL, 10), s(Material.COD, 8),
                s(Material.SALMON, 6)));

        // ===== 屠夫：肉摊 =====
        SELL.put(VillagerProfession.BUTCHER, List.of(
                s(Material.COOKED_BEEF, 5), s(Material.COOKED_PORKCHOP, 5),
                s(Material.COOKED_CHICKEN, 6), s(Material.COOKED_MUTTON, 5),
                s(Material.COOKED_RABBIT, 4), s(Material.RABBIT_STEW, 1), s(Material.LEATHER, 3),
                s(Material.BEEF, 6), s(Material.PORKCHOP, 6), s(Material.CHICKEN, 6)));
        BUY.put(VillagerProfession.BUTCHER, List.of(
                s(Material.BEEF, 10), s(Material.PORKCHOP, 10), s(Material.CHICKEN, 14),
                s(Material.MUTTON, 10), s(Material.RABBIT, 6), s(Material.COAL, 10)));

        // ===== 皮匠：皮具 =====
        SELL.put(VillagerProfession.LEATHERWORKER, List.of(
                s(Material.LEATHER_HELMET, 1), s(Material.LEATHER_CHESTPLATE, 1),
                s(Material.LEATHER_LEGGINGS, 1), s(Material.LEATHER_BOOTS, 1),
                s(Material.LEATHER, 4), s(Material.SADDLE, 1), s(Material.ITEM_FRAME, 2),
                s(Material.RABBIT_HIDE, 6)));
        BUY.put(VillagerProfession.LEATHERWORKER, List.of(
                s(Material.RABBIT_HIDE, 9), s(Material.FLINT, 10), s(Material.LEATHER, 3)));

        // ===== 石匠：石头与砖 =====
        SELL.put(VillagerProfession.MASON, List.of(
                s(Material.BRICKS, 4), s(Material.STONE_BRICKS, 8), s(Material.STONE, 16),
                s(Material.POLISHED_ANDESITE, 4), s(Material.POLISHED_DIORITE, 4),
                s(Material.POLISHED_GRANITE, 4), s(Material.QUARTZ_BLOCK, 2),
                s(Material.TERRACOTTA, 4), s(Material.CHISELED_STONE_BRICKS, 4),
                s(Material.CLAY, 2), s(Material.GLASS, 6)));
        BUY.put(VillagerProfession.MASON, List.of(
                s(Material.CLAY_BALL, 20), s(Material.STONE, 20), s(Material.COBBLESTONE, 20),
                s(Material.ANDESITE, 12), s(Material.DIORITE, 12), s(Material.GRANITE, 12)));

        // ===== 牧羊人：羊毛制品 =====
        SELL.put(VillagerProfession.SHEPHERD, List.of(
                s(Material.WHITE_WOOL, 6), s(Material.SHEARS, 1), s(Material.WHITE_CARPET, 4),
                s(Material.WHITE_BANNER, 1), s(Material.WHITE_BED, 1), s(Material.PAINTING, 2),
                s(Material.LEAD, 2), s(Material.HAY_BLOCK, 2)));
        BUY.put(VillagerProfession.SHEPHERD, List.of(
                s(Material.WHITE_WOOL, 12), s(Material.BLACK_DYE, 8), s(Material.WHEAT, 12)));

        // ===== 傻子：想到什么卖什么的杂货摊 =====
        SELL.put(VillagerProfession.NITWIT, List.of(
                s(Material.STICK, 8), s(Material.DIRT, 16), s(Material.GRAVEL, 10),
                s(Material.OAK_LOG, 6), s(Material.APPLE, 2), s(Material.CARROT, 6),
                s(Material.POTATO, 6), s(Material.FEATHER, 4), s(Material.STRING, 3),
                s(Material.POPPY, 3), s(Material.EGG, 4), s(Material.FLINT, 5)));
        BUY.put(VillagerProfession.NITWIT, List.of(
                s(Material.EGG, 8), s(Material.WHEAT, 20), s(Material.BREAD, 4)));

        // ===== 无业：勉强糊口的小摊 =====
        SELL.put(VillagerProfession.NONE, List.of(
                s(Material.BREAD, 3), s(Material.APPLE, 2), s(Material.STICK, 8),
                s(Material.TORCH, 8), s(Material.OAK_LOG, 8), s(Material.CARROT, 6)));
        BUY.put(VillagerProfession.NONE, List.of(
                s(Material.WHEAT, 20), s(Material.EGG, 10)));
    }

    /** 杂货池：任何职业的村民都可能顺手捎卖 1~2 件（Fong 许可的外行货） */
    private static final List<PoolItem> GUEST = List.of(
            s(Material.CANDLE, 4), s(Material.FLOWER_POT, 2), s(Material.LANTERN, 1),
            s(Material.HONEY_BOTTLE, 1), s(Material.COOKIE, 6), s(Material.BOOK, 1),
            s(Material.FEATHER, 6), s(Material.LEATHER, 2), s(Material.BONE, 6),
            s(Material.STRING, 6), s(Material.EGG, 6), s(Material.TORCH, 10),
            s(Material.CHEST, 1), s(Material.CRAFTING_TABLE, 1), s(Material.APPLE, 3),
            s(Material.GLASS_BOTTLE, 4));

    // ===== 货池外置（profession-pools.json）：服主可在配置面板改每职业卖/收什么 =====

    private static volatile Map<VillagerProfession, List<PoolItem>> activeSell;
    private static volatile Map<VillagerProfession, List<PoolItem>> activeBuy;
    private static volatile List<PoolItem> activeGuest;

    /**
     * 从数据文件夹加载 profession-pools.json（没有就把内置版拷过去再读）。
     * 文件里没写的职业回退内置默认池；坏数据只记 -1，绝不让插件起不来。
     */
    public static synchronized int load(java.nio.file.Path dataFolder) {
        java.nio.file.Path file = dataFolder.resolve("profession-pools.json");
        try {
            if (!java.nio.file.Files.exists(file)) {
                try (java.io.InputStream is = ProfessionPools.class.getResourceAsStream("/profession-pools.json")) {
                    if (is != null) {
                        java.nio.file.Files.createDirectories(dataFolder);
                        java.nio.file.Files.copy(is, file);
                    }
                }
            }
            if (!java.nio.file.Files.exists(file)) return -1;
            String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            Map<VillagerProfession, List<PoolItem>> sell = new EnumMap<>(VillagerProfession.class);
            Map<VillagerProfession, List<PoolItem>> buy = new EnumMap<>(VillagerProfession.class);
            if (root.has("professions") && root.get("professions").isJsonObject()) {
                for (var e : root.getAsJsonObject("professions").entrySet()) {
                    VillagerProfession prof;
                    try {
                        prof = VillagerProfession.valueOf(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (!e.getValue().isJsonObject()) continue;
                    com.google.gson.JsonObject o = e.getValue().getAsJsonObject();
                    List<PoolItem> sl = parsePool(o, "sell");
                    if (sl != null) sell.put(prof, sl);
                    List<PoolItem> by = parsePool(o, "buy");
                    if (by != null) buy.put(prof, by);
                }
            }
            List<PoolItem> guest = root.has("guest") ? parsePoolArray(root.get("guest")) : null;
            if (sell.isEmpty() && buy.isEmpty() && guest == null) return -1;
            activeSell = sell;
            activeBuy = buy;
            // 杂货池清空 = 回退内置默认（与面板上的说明一致）
            if (guest != null && !guest.isEmpty()) activeGuest = guest;
            return sell.size() + buy.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<PoolItem> parsePool(com.google.gson.JsonObject o, String key) {
        if (!o.has(key)) return null;
        return parsePoolArray(o.get(key));
    }

    /** [[物品ID, 个数], ...] → PoolItem 列表；认不出的物品跳过 */
    private static List<PoolItem> parsePoolArray(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        List<PoolItem> out = new ArrayList<>();
        for (com.google.gson.JsonElement pair : el.getAsJsonArray()) {
            if (!pair.isJsonArray() || pair.getAsJsonArray().size() != 2) continue;
            Material m = ItemIds.materialOf(pair.getAsJsonArray().get(0).getAsString());
            int count = pair.getAsJsonArray().get(1).getAsInt();
            if (m != null && count > 0) out.add(new PoolItem(m, count));
        }
        return List.copyOf(out);
    }

    public static List<PoolItem> sellPool(VillagerProfession p) {
        Map<VillagerProfession, List<PoolItem>> active = activeSell;
        if (active != null && active.containsKey(p)) return active.get(p);
        return SELL.getOrDefault(p, List.of());
    }

    public static List<PoolItem> buyPool(VillagerProfession p) {
        Map<VillagerProfession, List<PoolItem>> active = activeBuy;
        if (active != null && active.containsKey(p)) return active.get(p);
        return BUY.getOrDefault(p, List.of());
    }

    public static List<PoolItem> guestPool() {
        List<PoolItem> active = activeGuest;
        return active != null ? active : GUEST;
    }

    /** 该职业的售卖范围是否包含某物（给 LLM 提案把关用：农夫不能卖铁剑） */
    public static boolean canSell(VillagerProfession p, Material m) {
        return m != null && sellPool(p).stream().anyMatch(e -> e.item() == m);
    }

    /** 该职业的收购范围是否包含某物 */
    public static boolean canBuy(VillagerProfession p, Material m) {
        return m != null && buyPool(p).stream().anyMatch(e -> e.item() == m);
    }
}
