package com.howard.mcai.ai;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 价格账 —— 全村唯一的绿宝石定价权威（Fong 2026-10-21 定：价格按合成表来）。
 * <p>
 * 以前每个职业的配方表手写价格，写出了「金锭 1 绿宝石、铁块 20 绿宝石」
 * （9 个铁锭才 9 块钱，铁块凭什么 20）、「高炉比熔炉便宜」、「收纸 1 张给 2 块、
 * 卖纸 4 张才 1 块（能套利）」这类笑话。现在改成两层：
 * <ol>
 *   <li><b>锚点价</b>：不能合成的基础物品（矿石、作物、掉落物）按<b>原版村民的收购率</b>
 *       定「公道价」（如原版农民收 20 小麦给 1 绿宝石 → 小麦 0.05/个）；</li>
 *   <li><b>推导价</b>：凡能合成/烧制的，一律按配方递归推导：
 *       公道价 = Σ(原料公道价 × 个数) ÷ 产物个数。铁块 = 9 × 铁锭，天然自洽。</li>
 * </ol>
 * 商店卖价 = 公道价 × 1.2 向上取整（小店赚两成加工/辛苦费）；
 * 收购价 = 公道价 × 0.8 向下取整且永不高于同堆卖价 —— 买进卖出套利从结构上不存在。
 * <p>
 * 纯逻辑（只用 Material 枚举），可单测；配方表是原版合成/烧制配方的转写（只收录
 * 货池及其原料闭包用得到的条目）。
 */
public final class PriceBook {

    private PriceBook() {
    }

    /** 查不到锚点又没有配方的物品的兜底公道价（普通杂物档） */
    private static final double DEFAULT_VALUE = 0.25;
    /** 商店卖价系数（对公道价） */
    private static final double SELL_MARKUP = 1.2;
    /** 商店收购价系数（对公道价） */
    private static final double BUYBACK_RATE = 0.8;

    // ===== 锚点价（绿宝石/个）：原版村民收购率 + 世界观稀有度 =====

    private static final Map<Material, Double> ANCHORS = new EnumMap<>(Material.class);

    static {
        // 货币本身
        a(Material.EMERALD, 1.0);
        // 作物与食物原料（原版农民收购率：20 小麦→1、22 胡萝卜→1、26 马铃薯→1、15 甜菜→1）
        a(Material.WHEAT, 0.05);
        a(Material.CARROT, 0.045);
        a(Material.POTATO, 0.04);
        a(Material.BEETROOT, 0.067);
        a(Material.PUMPKIN, 0.17);
        a(Material.MELON_SLICE, 0.1);
        a(Material.APPLE, 0.25);
        a(Material.SWEET_BERRIES, 0.08);
        a(Material.EGG, 0.08);
        a(Material.SUGAR_CANE, 0.06);
        a(Material.COCOA_BEANS, 0.15);
        a(Material.KELP, 0.05);
        // 矿物与基础材料（原版：铁匠收 4 铁锭→1、牧师收 3 金锭→1、10 煤→1）
        a(Material.COAL, 0.1);
        a(Material.CHARCOAL, 0.1);
        a(Material.IRON_INGOT, 0.3);
        a(Material.GOLD_INGOT, 0.35);
        a(Material.DIAMOND, 10.0);
        a(Material.LAPIS_LAZULI, 0.15);
        a(Material.REDSTONE, 0.08);
        a(Material.QUARTZ, 0.4);
        a(Material.OBSIDIAN, 0.6);
        a(Material.FLINT, 0.12);
        a(Material.GLOWSTONE_DUST, 0.15);
        a(Material.AMETHYST_SHARD, 0.3);
        // 大地与建材原料
        a(Material.COBBLESTONE, 0.04);
        a(Material.STONE, 0.05);
        a(Material.GRANITE, 0.05);
        a(Material.DIORITE, 0.05);
        a(Material.ANDESITE, 0.05);
        a(Material.CLAY_BALL, 0.05);
        a(Material.SAND, 0.03);
        a(Material.GRAVEL, 0.03);
        a(Material.DIRT, 0.02);
        // 木材（原木统一价，木板/木棍按配方推）
        a(Material.OAK_LOG, 0.15);
        a(Material.SPRUCE_LOG, 0.15);
        a(Material.BIRCH_LOG, 0.15);
        a(Material.JUNGLE_LOG, 0.15);
        a(Material.ACACIA_LOG, 0.15);
        a(Material.DARK_OAK_LOG, 0.15);
        a(Material.MANGROVE_LOG, 0.15);
        a(Material.CHERRY_LOG, 0.15);
        // 生物材料与掉落
        a(Material.STRING, 0.12);
        a(Material.FEATHER, 0.1);
        a(Material.LEATHER, 0.45);
        a(Material.RABBIT_HIDE, 0.12);
        a(Material.BONE, 0.09);
        a(Material.ROTTEN_FLESH, 0.03);
        a(Material.SPIDER_EYE, 0.15);
        a(Material.SLIME_BALL, 0.25);
        a(Material.INK_SAC, 0.15);
        a(Material.GLOW_INK_SAC, 0.3);
        a(Material.BEEF, 0.3);
        a(Material.PORKCHOP, 0.3);
        a(Material.CHICKEN, 0.25);
        a(Material.MUTTON, 0.25);
        a(Material.RABBIT, 0.3);
        a(Material.COD, 0.25);
        a(Material.SALMON, 0.3);
        a(Material.TROPICAL_FISH, 0.5);
        a(Material.PUFFERFISH, 0.6);
        a(Material.WHITE_WOOL, 0.2);
        a(Material.BLACK_DYE, 0.1);
        // 稀有物（不可合成，按世界观稀有度锚定）
        a(Material.ENDER_PEARL, 1.2);
        a(Material.BLAZE_ROD, 0.8);
        a(Material.GHAST_TEAR, 4.0);
        a(Material.NETHER_WART, 0.2);
        a(Material.TOTEM_OF_UNDYING, 40.0);
        a(Material.NAUTILUS_SHELL, 3.0);
        a(Material.NAME_TAG, 3.0);
        a(Material.SADDLE, 5.0);
        a(Material.EXPERIENCE_BOTTLE, 1.5);
        a(Material.TURTLE_SCUTE, 1.5);
        a(Material.HONEYCOMB, 0.3);
        a(Material.MILK_BUCKET, 1.2);
        a(Material.RABBIT_STEW, 1.2);
        // 锁链甲不可合成（原版由火焰合成，已移除）——按铁甲打折锚定
        a(Material.CHAINMAIL_HELMET, 4.0);
        a(Material.CHAINMAIL_CHESTPLATE, 6.0);
        a(Material.CHAINMAIL_LEGGINGS, 5.0);
        a(Material.CHAINMAIL_BOOTS, 3.0);
    }

    private static void a(Material m, double v) {
        ANCHORS.put(m, v);
    }

    // ===== 配方表（原版合成/烧制转写：产物 → (产出个数, 原料×个数)） =====

    private record Recipe(int out, Map<Material, Integer> in) {
    }

    private static final Map<Material, List<Recipe>> RECIPES = new EnumMap<>(Material.class);

    private static void r(Material out, int outCount, Object... inPairs) {
        Map<Material, Integer> in = new EnumMap<>(Material.class);
        for (int i = 0; i < inPairs.length; i += 2) {
            in.put((Material) inPairs[i], (Integer) inPairs[i + 1]);
        }
        RECIPES.computeIfAbsent(out, k -> new java.util.ArrayList<>()).add(new Recipe(outCount, in));
    }

    static {
        // 方块与锭互转
        r(Material.IRON_BLOCK, 1, Material.IRON_INGOT, 9);
        r(Material.IRON_INGOT, 9, Material.IRON_BLOCK, 1);
        r(Material.GOLD_BLOCK, 1, Material.GOLD_INGOT, 9);
        r(Material.GOLD_INGOT, 9, Material.GOLD_BLOCK, 1);
        r(Material.DIAMOND_BLOCK, 1, Material.DIAMOND, 9);
        r(Material.DIAMOND, 9, Material.DIAMOND_BLOCK, 1);
        r(Material.EMERALD_BLOCK, 1, Material.EMERALD, 9);
        r(Material.EMERALD, 9, Material.EMERALD_BLOCK, 1);
        r(Material.GOLD_NUGGET, 9, Material.GOLD_INGOT, 1);
        r(Material.IRON_NUGGET, 9, Material.IRON_INGOT, 1);
        r(Material.HAY_BLOCK, 1, Material.WHEAT, 9);
        r(Material.WHEAT, 9, Material.HAY_BLOCK, 1);
        r(Material.CLAY, 1, Material.CLAY_BALL, 4);
        r(Material.GLOWSTONE, 1, Material.GLOWSTONE_DUST, 4);
        r(Material.QUARTZ_BLOCK, 1, Material.QUARTZ, 4);
        r(Material.MELON, 1, Material.MELON_SLICE, 9);
        r(Material.MELON_SLICE, 9, Material.MELON, 1);
        // 木材链
        for (Material log : List.of(Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
                Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
                Material.MANGROVE_LOG, Material.CHERRY_LOG)) {
            String planks = log.name().replace("_LOG", "_PLANKS");
            Material p = Material.matchMaterial(planks);
            if (p != null) r(p, 4, log, 1);
        }
        r(Material.STICK, 4, Material.OAK_PLANKS, 2);
        r(Material.TORCH, 4, Material.COAL, 1, Material.STICK, 1);
        r(Material.CHEST, 1, Material.OAK_PLANKS, 8);
        r(Material.CRAFTING_TABLE, 1, Material.OAK_PLANKS, 4);
        r(Material.BOWL, 4, Material.OAK_PLANKS, 3);
        r(Material.FLOWER_POT, 1, Material.BRICK, 3);
        // 食物链
        r(Material.BREAD, 1, Material.WHEAT, 3);
        r(Material.COOKIE, 8, Material.WHEAT, 2, Material.COCOA_BEANS, 1);
        r(Material.PUMPKIN_PIE, 1, Material.PUMPKIN, 1, Material.SUGAR, 1, Material.EGG, 1);
        r(Material.SUGAR, 1, Material.SUGAR_CANE, 1);
        r(Material.GOLDEN_CARROT, 1, Material.GOLD_NUGGET, 8, Material.CARROT, 1);
        r(Material.GOLDEN_APPLE, 1, Material.GOLD_INGOT, 8, Material.APPLE, 1);
        r(Material.BONE_MEAL, 3, Material.BONE, 1);
        // 烧制（熟食 = 生食价，燃料不计）
        r(Material.BAKED_POTATO, 1, Material.POTATO, 1);
        r(Material.COOKED_BEEF, 1, Material.BEEF, 1);
        r(Material.COOKED_PORKCHOP, 1, Material.PORKCHOP, 1);
        r(Material.COOKED_CHICKEN, 1, Material.CHICKEN, 1);
        r(Material.COOKED_MUTTON, 1, Material.MUTTON, 1);
        r(Material.COOKED_RABBIT, 1, Material.RABBIT, 1);
        r(Material.COOKED_COD, 1, Material.COD, 1);
        r(Material.COOKED_SALMON, 1, Material.SALMON, 1);
        r(Material.DRIED_KELP, 1, Material.KELP, 1);
        r(Material.GLASS, 1, Material.SAND, 1);
        r(Material.BRICK, 1, Material.CLAY_BALL, 1);
        r(Material.SMOOTH_STONE, 1, Material.STONE, 1);
        r(Material.TERRACOTTA, 1, Material.CLAY, 1);
        r(Material.CHARCOAL, 1, Material.OAK_LOG, 1);
        // 铁器与钻石器（盔甲/工具的标准配方）
        r(Material.IRON_SWORD, 1, Material.IRON_INGOT, 2, Material.STICK, 1);
        r(Material.IRON_PICKAXE, 1, Material.IRON_INGOT, 3, Material.STICK, 2);
        r(Material.IRON_AXE, 1, Material.IRON_INGOT, 3, Material.STICK, 2);
        r(Material.IRON_SHOVEL, 1, Material.IRON_INGOT, 1, Material.STICK, 2);
        r(Material.IRON_HOE, 1, Material.IRON_INGOT, 2, Material.STICK, 2);
        r(Material.IRON_HELMET, 1, Material.IRON_INGOT, 5);
        r(Material.IRON_CHESTPLATE, 1, Material.IRON_INGOT, 8);
        r(Material.IRON_LEGGINGS, 1, Material.IRON_INGOT, 7);
        r(Material.IRON_BOOTS, 1, Material.IRON_INGOT, 4);
        r(Material.DIAMOND_SWORD, 1, Material.DIAMOND, 2, Material.STICK, 1);
        r(Material.DIAMOND_PICKAXE, 1, Material.DIAMOND, 3, Material.STICK, 2);
        r(Material.DIAMOND_AXE, 1, Material.DIAMOND, 3, Material.STICK, 2);
        r(Material.DIAMOND_SHOVEL, 1, Material.DIAMOND, 1, Material.STICK, 2);
        r(Material.DIAMOND_HOE, 1, Material.DIAMOND, 2, Material.STICK, 2);
        r(Material.DIAMOND_HELMET, 1, Material.DIAMOND, 5);
        r(Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND, 8);
        r(Material.DIAMOND_LEGGINGS, 1, Material.DIAMOND, 7);
        r(Material.DIAMOND_BOOTS, 1, Material.DIAMOND, 4);
        r(Material.STONE_SWORD, 1, Material.COBBLESTONE, 2, Material.STICK, 1);
        r(Material.STONE_PICKAXE, 1, Material.COBBLESTONE, 3, Material.STICK, 2);
        r(Material.STONE_AXE, 1, Material.COBBLESTONE, 3, Material.STICK, 2);
        // 皮甲
        r(Material.LEATHER_HELMET, 1, Material.LEATHER, 5);
        r(Material.LEATHER_CHESTPLATE, 1, Material.LEATHER, 8);
        r(Material.LEATHER_LEGGINGS, 1, Material.LEATHER, 7);
        r(Material.LEATHER_BOOTS, 1, Material.LEATHER, 4);
        r(Material.LEATHER, 1, Material.RABBIT_HIDE, 4);
        // 工作站与功能物
        r(Material.ANVIL, 1, Material.IRON_BLOCK, 3, Material.IRON_INGOT, 4);
        r(Material.FURNACE, 1, Material.COBBLESTONE, 8);
        r(Material.BLAST_FURNACE, 1, Material.IRON_INGOT, 5, Material.FURNACE, 1, Material.SMOOTH_STONE, 3);
        r(Material.SMOKER, 1, Material.FURNACE, 1, Material.OAK_LOG, 4);
        r(Material.BUCKET, 1, Material.IRON_INGOT, 3);
        r(Material.COD_BUCKET, 1, Material.BUCKET, 1, Material.COD, 1);
        r(Material.SHIELD, 1, Material.OAK_PLANKS, 6, Material.IRON_INGOT, 1);
        r(Material.SHEARS, 1, Material.IRON_INGOT, 2);
        r(Material.FISHING_ROD, 1, Material.STICK, 3, Material.STRING, 2);
        r(Material.BOW, 1, Material.STICK, 3, Material.STRING, 3);
        r(Material.CROSSBOW, 1, Material.STICK, 3, Material.STRING, 2, Material.IRON_INGOT, 1, Material.TRIPWIRE_HOOK, 1);
        r(Material.TRIPWIRE_HOOK, 2, Material.IRON_INGOT, 1, Material.STICK, 1, Material.OAK_PLANKS, 1);
        r(Material.ARROW, 4, Material.FLINT, 1, Material.STICK, 1, Material.FEATHER, 1);
        r(Material.SPECTRAL_ARROW, 4, Material.GLOWSTONE_DUST, 4, Material.ARROW, 1);
        r(Material.LEAD, 2, Material.STRING, 4, Material.SLIME_BALL, 1);
        // 书与知识
        r(Material.PAPER, 3, Material.SUGAR_CANE, 3);
        r(Material.BOOK, 1, Material.PAPER, 3, Material.LEATHER, 1);
        r(Material.BOOKSHELF, 1, Material.OAK_PLANKS, 6, Material.BOOK, 3);
        r(Material.ENCHANTING_TABLE, 1, Material.BOOK, 1, Material.DIAMOND, 2, Material.OBSIDIAN, 4);
        r(Material.WRITABLE_BOOK, 1, Material.BOOK, 1, Material.INK_SAC, 1, Material.FEATHER, 1);
        // 导航与装饰
        r(Material.COMPASS, 1, Material.IRON_INGOT, 4, Material.REDSTONE, 1);
        r(Material.CLOCK, 1, Material.GOLD_INGOT, 4, Material.REDSTONE, 1);
        r(Material.MAP, 1, Material.PAPER, 8, Material.COMPASS, 1);
        r(Material.ITEM_FRAME, 1, Material.STICK, 8, Material.LEATHER, 1);
        r(Material.GLOW_ITEM_FRAME, 1, Material.ITEM_FRAME, 1, Material.GLOW_INK_SAC, 1);
        r(Material.LANTERN, 1, Material.IRON_NUGGET, 8, Material.TORCH, 1);
        r(Material.CANDLE, 1, Material.HONEYCOMB, 1, Material.STRING, 1);
        r(Material.PAINTING, 1, Material.STICK, 8, Material.WHITE_WOOL, 1);
        r(Material.GLASS_PANE, 16, Material.GLASS, 6);
        r(Material.GLASS_BOTTLE, 3, Material.GLASS, 3);
        r(Material.WHITE_WOOL, 1, Material.STRING, 4);
        r(Material.WHITE_CARPET, 3, Material.WHITE_WOOL, 2);
        r(Material.WHITE_BED, 1, Material.WHITE_WOOL, 3, Material.OAK_PLANKS, 3);
        r(Material.WHITE_BANNER, 1, Material.WHITE_WOOL, 6, Material.STICK, 1);
        // 石材加工
        r(Material.BRICKS, 1, Material.BRICK, 4);
        r(Material.STONE_BRICKS, 4, Material.STONE, 4);
        r(Material.CHISELED_STONE_BRICKS, 1, Material.STONE_BRICKS, 2);
        r(Material.POLISHED_ANDESITE, 4, Material.ANDESITE, 4);
        r(Material.POLISHED_DIORITE, 4, Material.DIORITE, 4);
        r(Material.POLISHED_GRANITE, 4, Material.GRANITE, 4);
    }

    // ===== 计价 =====

    private static final Map<Material, Double> CACHE = new EnumMap<>(Material.class);

    /** 某物品的公道价（绿宝石/个）：锚点优先，其次配方递归（多配方取最便宜），再兜底 */
    public static synchronized double fairValue(Material m) {
        if (m == null) return DEFAULT_VALUE;
        Double cached = CACHE.get(m);
        if (cached != null) return cached;
        double v = compute(m, new HashSet<>());
        CACHE.put(m, v);
        return v;
    }

    private static double compute(Material m, Set<Material> visiting) {
        Double anchor = anchors().get(m);
        if (anchor != null) return anchor;
        List<Recipe> recipes = RECIPES.get(m);
        if (recipes == null || recipes.isEmpty() || !visiting.add(m)) {
            return DEFAULT_VALUE;
        }
        double best = Double.POSITIVE_INFINITY;
        for (Recipe rec : recipes) {
            double sum = 0;
            for (var e : rec.in().entrySet()) {
                sum += compute(e.getKey(), visiting) * e.getValue();
            }
            best = Math.min(best, sum / rec.out());
        }
        visiting.remove(m);
        return best == Double.POSITIVE_INFINITY ? DEFAULT_VALUE : best;
    }


    // ===== 锚点外置（prices.json）：服主可在配置面板改基础价 =====

    /** 当前生效的锚点表：load 过数据文件用文件的，没 load 用代码内置默认 */
    private static volatile Map<Material, Double> activeAnchors;

    public static Map<Material, Double> anchors() {
        Map<Material, Double> a = activeAnchors;
        return a != null ? a : ANCHORS;
    }

    /** 当前锚点（物品 ID → 公道价），给定价表编辑/外部接口用 */
    public static Map<String, Double> anchorEntries() {
        Map<String, Double> out = new java.util.TreeMap<>();
        for (var e : anchors().entrySet()) {
            out.put(ItemIds.idOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 从数据文件夹加载 prices.json（没有就把内置版拷过去再读）。
     * 坏数据/认不出的物品 ID 只跳过并记日志，绝不让插件起不来；失败时保留原锚点。
     */
    public static synchronized int load(java.nio.file.Path dataFolder) {
        java.nio.file.Path file = dataFolder.resolve("prices.json");
        try {
            if (!java.nio.file.Files.exists(file)) {
                try (java.io.InputStream is = PriceBook.class.getResourceAsStream("/prices.json")) {
                    if (is != null) {
                        java.nio.file.Files.createDirectories(dataFolder);
                        java.nio.file.Files.copy(is, file);
                    }
                }
            }
            if (!java.nio.file.Files.exists(file)) return -1;
            String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("anchors") || !root.get("anchors").isJsonObject()) return -1;
            Map<Material, Double> parsed = new EnumMap<>(Material.class);
            for (var e : root.getAsJsonObject("anchors").entrySet()) {
                Material m = ItemIds.materialOf(e.getKey());
                if (m == null || !e.getValue().isJsonPrimitive()) continue;
                double v = e.getValue().getAsDouble();
                if (v > 0 && Double.isFinite(v)) parsed.put(m, v);
            }
            if (parsed.isEmpty()) return -1;
            activeAnchors = parsed;
            synchronized (PriceBook.class) {
                CACHE.clear();
            }
            return parsed.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 商店卖价（一堆 count 个）：公道价 × 1.2 向上取整，至少 1 */
    public static int sellPrice(Material m, int count) {
        double exact = fairValue(m) * Math.max(1, count) * SELL_MARKUP;
        return Math.max(1, (int) Math.ceil(exact));
    }

    /**
     * 外部报价过账 —— 模型谈出来的价、旧存档里留下的动态价，都不能直接进店：
     * 夹在 [1, 本账卖价 × 3] 内（插件定规矩，模型只有表达权）。
     * 2026-10-21 事故：旧价目表时代存进村民存档的动态价（铁块 20）会盖住本账新价，
     * 价格改革对老村民全部落空——构建商店时所有外部价一律先过这道账。
     */
    public static int clampNegotiatedPrice(Material m, int count, int proposed) {
        int cap = Math.max(1, sellPrice(m, count) * 3);
        return Math.max(1, Math.min(proposed, cap));
    }

    /**
     * 价格 → 绿宝石原料各格数量（纯计算，TradeRegistry.setEmeraldPrice 用它拼原料表）。
     * 规则：价格夹在 [1,128]；无第二原料时 &gt;64 拆 [64, 余数] 两格；
     * 有第二原料时它已占 1 格，价格封顶 64 只占一格。
     * 不变量：返回格数 + (hasSecondIngredient ? 1 : 0) ≤ 2 —— 2026-10-21 插件加载
     * 崩溃事故就是这条被打破（占位绿宝石残留 + 拆格 = 3 格，
     * "MerchantRecipe can only have maximum 2 ingredients"）。
     * 放在 PriceBook 而非 TradeRegistry：本类初始化只算数字、不构造 ItemStack，
     * 单测能真正跑到（TradeRegistry 的静态初始化会造配方，离线环境跑不了）。
     */
    public static java.util.List<Integer> emeraldStacks(int price, boolean hasSecondIngredient) {
        int p = Math.max(1, Math.min(price, 128));
        if (hasSecondIngredient) p = Math.min(p, 64);
        if (p > 64) return java.util.List.of(64, p - 64);
        return java.util.List.of(p);
    }

    /**
     * 商店收购价（一堆 count 个）：公道价 × 0.8 向下取整，至少 1，
     * 且永不高于同堆卖价（防套利）。
     */
    public static int buybackPrice(Material m, int count) {
        int pay = (int) Math.floor(fairValue(m) * Math.max(1, count) * BUYBACK_RATE);
        pay = Math.max(1, pay);
        return Math.min(pay, sellPrice(m, count));
    }
}
