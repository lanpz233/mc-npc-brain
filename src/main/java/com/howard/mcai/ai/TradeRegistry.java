package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 村民专属交易配方注册表 —— 按职业硬编码，注入原版交易 GUI。
 * <p>
 * 为什么不用原版村民自带的配方？
 * 原版村民的配方是按 level 随机刷的（5 级村民才卖钻石头盔），
 * 跟 AI NPC 的人设没关系。铁匠不该卖面包，农夫不该卖钻石剑。
 * 这里按职业定制，NPC 开的店 100% 符合人设。
 *
 * <p>世界观铁律：
 * 货币只用 绿宝石 / 铁锭 / 金锭 / 钻石（及方块）—— 绝不出现铜币/银币/金币。
 *
 * <p>Paper 1.21.1 MerchantRecipe API（从 javap 确认）：
 * <pre>
 *   new MerchantRecipe(ItemStack result, int maxUses)  ← 构造器只收 result + maxUses
 *   recipe.addIngredient(ItemStack)                     ← 单独加原料（可加多个 = 双槽）
 *   recipe.setIngredients(List&lt;ItemStack&gt;)
 * </pre>
 * 构造器 **不** 收 ingredient！result 是要卖的物品。
 *
 * <p>maxUses 统一设 999 —— AI NPC 不像原版村民会涨价/缺货。
 */
public final class TradeRegistry {

    private static final Map<VillagerProfession, List<MerchantRecipe>> CACHE = new HashMap<>();

    // ===== 便捷方法 =====

    /**
     * 设置配方的绿宝价格。
     * <p>
     * 交易 GUI 只有 2 个原料槽、单格上限 64，所以最高只能收 128 绿宝——
     * 超过 64 拆成 64+余数两槽，超过 128 截断到 128（否则这笔交易物理上无法成交）。
     * 只替换 EMERALD 原料，不影响加工类配方的第二原料（如燧石）。
     */
    public static void setEmeraldPrice(MerchantRecipe recipe, int price) {
        recipe.getIngredients().removeIf(s -> s.getType() == Material.EMERALD);
        int p = Math.max(1, Math.min(price, 128));
        if (p > 64) {
            recipe.addIngredient(new ItemStack(Material.EMERALD, 64));
            recipe.addIngredient(new ItemStack(Material.EMERALD, p - 64));
        } else {
            recipe.addIngredient(new ItemStack(Material.EMERALD, p));
        }
    }

    /**
     * 深拷贝一条配方。
     * <p>
     * TradeRegistry 内部缓存是全局共享的，任何对配方的修改（砍价改价、清原料）
     * 都必须先拷贝，否则会污染所有同职业村民的默认价。
     */
    public static MerchantRecipe copyRecipe(MerchantRecipe r) {
        MerchantRecipe copy = new MerchantRecipe(r.getResult().clone(), r.getMaxUses());
        copy.setUses(r.getUses());
        for (ItemStack ing : r.getIngredients()) {
            copy.addIngredient(ing.clone());
        }
        return copy;
    }

    // ===== 职业→配方 =====

    static {
        for (VillagerProfession p : VillagerProfession.values()) {
            CACHE.put(p, buildRecipes(p));
        }
    }

    public static List<MerchantRecipe> get(VillagerProfession profession) {
        return CACHE.getOrDefault(profession, List.of());
    }

    /**
     * 商品中文名 → Material 查找。
     * LLM 用中文 item 名（"铁剑"、"钻石镐"），需要翻成 Material 才能造 MerchantRecipe。
     * 映射不到返回 null（上层 fallback 不生成动态 recipe）。
     */
    public static Material zhToMaterial(String zhName) {
        if (zhName == null || zhName.isBlank()) return null;
        Material m = ZH_TO_MATERIAL.get(zhName.trim());
        // 手写词典没有 → 查官方全表（红砖块、石头台阶这类正式名也能听懂）
        return m != null ? m : ItemZhTable.materialOf(zhName);
    }

    /**
     * Material → 中文物品名。
     * 商店界面与 PromptBuilder 拼清单时用。优先官方全表（ItemZhTable，1480 条全覆盖），
     * 手写小词典只作兜底 —— 旧版只查小词典，漏网物品直接漏英文（stone slab 事件）。
     */
    public static String materialToZh(Material m) {
        if (m == null) return "?";
        String zh = ItemZhTable.zhOf(m);
        if (zh != null) return zh;
        // 反向遍历 ZH_TO_MATERIAL 找 key
        for (var e : ZH_TO_MATERIAL.entrySet()) {
            if (e.getValue() == m) return e.getKey();
        }
        return m.name().toLowerCase().replace('_', ' ');
    }

    /** 返回所有已知中文物品名（手写词典 + 官方全表；PlayerChatListener 砍价关键词匹配用） */
    public static java.util.Set<String> zhItemKeys() {
        java.util.Set<String> all = new java.util.HashSet<>(ZH_TO_MATERIAL.keySet());
        all.addAll(ItemZhTable.zhKeys());
        return java.util.Collections.unmodifiableSet(all);
    }

    /**
     * 动态造一条 NPC 卖的 recipe（绿宝石换物品）。
     * LLM 说"我铁剑卖你3绿宝"时调用。
     * <p>
     * 为什么先占位 1 个绿宝再调 setEmeraldPrice：价格必须统一走 setEmeraldPrice
     * 做 clamp（price≤0→1、>128→128）和 >64 拆双槽 —— 直接
     * new ItemStack(EMERALD, price) 在 LLM 传 999 时会造出单槽 >64 的非法配方，
     * 交易 GUI 物理上无法成交。setEmeraldPrice 按"替换已有 EMERALD 原料"设计，
     * 所以要先放一条原料进去。
     */
    public static MerchantRecipe createOffer(Material item, int emeraldPrice, int count) {
        MerchantRecipe r = new MerchantRecipe(
                new ItemStack(item, Math.max(1, Math.min(count, 64))), 999);  // 结果槽单格上限 64
        r.addIngredient(new ItemStack(Material.EMERALD, 1));  // 占位原料，随即被 setEmeraldPrice 重置
        setEmeraldPrice(r, emeraldPrice);
        return r;
    }

    /**
     * 动态造一条 NPC 收原料的 recipe（物品换绿宝石）。
     * <p>
     * 结果槽（NPC 付给玩家的绿宝）只有一格、上限 64：LLM 传 999 时必须 clamp，
     * 否则生成无法完成的交易；原料栈同理不能超过单格 64。
     */
    public static MerchantRecipe createBuy(Material item, int count, int emeraldPrice) {
        int pay = Math.max(1, Math.min(emeraldPrice, 64));   // 结果槽无法拆分，硬上限 64
        int ing = Math.max(1, Math.min(count, 64));          // 单原料槽上限 64
        MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.EMERALD, pay), 999);
        r.addIngredient(new ItemStack(item, ing));
        return r;
    }

    // ===== 附魔商品支持 =====
    // 玩家让 NPC"附魔物品后卖给我"时，模型可能传 item="附魔钻石剑" 或额外传 enchant="锋利"。
    // 之前只支持普通 Material，附魔名解析失败 → 上架被静默跳过 → "他说好，面板里没有"。
    // 这里让 NPC 能真正造出带附魔的配方（MerchantRecipe result 挂 ItemMeta 附魔）。

    /** 中文附魔名 → Enchantment（LLM 传 "锋利"/"效率"/"保护" 时用） */
    private static final Map<String, Enchantment> ZH_ENCHANT = Map.ofEntries(
            Map.entry("锋利", Enchantment.SHARPNESS),
            Map.entry("力量", Enchantment.POWER),
            Map.entry("保护", Enchantment.PROTECTION),
            Map.entry("效率", Enchantment.EFFICIENCY),
            Map.entry("耐久", Enchantment.UNBREAKING),
            Map.entry("火焰附加", Enchantment.FIRE_ASPECT),
            Map.entry("击退", Enchantment.KNOCKBACK),
            Map.entry("海之眷顾", Enchantment.LUCK_OF_THE_SEA),
            Map.entry("饵钓", Enchantment.LURE),
            Map.entry("快速装填", Enchantment.QUICK_CHARGE),
            Map.entry("无限", Enchantment.INFINITY)
    );

    /**
     * 动态造一条"附魔物品"配方（绿宝石换附魔物品）。
     * enchantZh 非空 → 按名附魔；为空 → 按物品类型随机挑一个合理的附魔。
     */
    public static MerchantRecipe createEnchantedOffer(Material item, int emeraldPrice, int count, String enchantZh) {
        ItemStack result = new ItemStack(item, Math.max(1, Math.min(count, 64)));  // 结果槽单格上限 64
        Enchantment ench = (enchantZh != null && !enchantZh.isBlank()) ? ZH_ENCHANT.get(enchantZh.trim()) : null;
        if (ench != null) {
            result.addUnsafeEnchantment(ench, 1 + (int) (Math.random() * 3));  // 等级 1~3
        } else {
            applyRandomEnchant(result);
        }
        MerchantRecipe r = new MerchantRecipe(result, 999);
        // 价格统一走 setEmeraldPrice：clamp 异常值 + >64 拆双槽（原因见 createOffer 注释）
        r.addIngredient(new ItemStack(Material.EMERALD, 1));  // 占位原料，随即被 setEmeraldPrice 重置
        setEmeraldPrice(r, emeraldPrice);
        return r;
    }

    /** 按物品类型挑一个合理的附魔（玩家只说"附魔这把剑"没指定附什么时） */
    public static void applyRandomEnchant(ItemStack item) {
        if (item == null) return;
        Enchantment ench = switch (item.getType()) {
            case DIAMOND_SWORD, IRON_SWORD, STONE_SWORD, GOLDEN_SWORD, WOODEN_SWORD -> Enchantment.SHARPNESS;
            case DIAMOND_PICKAXE, IRON_PICKAXE, STONE_PICKAXE, GOLDEN_PICKAXE, WOODEN_PICKAXE,
                 DIAMOND_AXE, IRON_AXE, STONE_AXE, GOLDEN_AXE, WOODEN_AXE,
                 DIAMOND_SHOVEL, IRON_SHOVEL, STONE_SHOVEL, GOLDEN_SHOVEL, WOODEN_SHOVEL,
                 DIAMOND_HOE, IRON_HOE, STONE_HOE, GOLDEN_HOE, WOODEN_HOE -> Enchantment.EFFICIENCY;
            case BOW -> Enchantment.POWER;
            case DIAMOND_HELMET, IRON_HELMET, GOLDEN_HELMET, LEATHER_HELMET,
                 DIAMOND_CHESTPLATE, IRON_CHESTPLATE, GOLDEN_CHESTPLATE, LEATHER_CHESTPLATE,
                 DIAMOND_LEGGINGS, IRON_LEGGINGS, GOLDEN_LEGGINGS, LEATHER_LEGGINGS,
                 DIAMOND_BOOTS, IRON_BOOTS, GOLDEN_BOOTS, LEATHER_BOOTS -> Enchantment.PROTECTION;
            case FISHING_ROD -> Enchantment.LUCK_OF_THE_SEA;
            case CROSSBOW -> Enchantment.QUICK_CHARGE;
            default -> Enchantment.UNBREAKING;
        };
        item.addUnsafeEnchantment(ench, 1 + (int) (Math.random() * 3));
    }

    /** 判断中文商品名是否带"附魔"前缀（如"附魔钻石剑"） */
    public static boolean isEnchantedName(String zhName) {
        return zhName != null && zhName.startsWith("附魔") && zhName.length() > 2;
    }

    /** 去掉"附魔"前缀取基础物品名（"附魔钻石剑" → "钻石剑"，用于查 Material） */
    public static String stripEnchantedPrefix(String zhName) {
        return isEnchantedName(zhName) ? zhName.substring(2) : zhName;
    }

    /**
     * 判断某职业的基础配方里是否包含 sell 某个 Material。
     * CentralPlanner 里 action="offer" 时用——LLM 不能让农夫卖铁剑！
     */
    public static boolean professionCanSell(VillagerProfession profession, Material m) {
        // 售卖范围 = 职业货池（该职业能卖的所有东西；某个村民今天摊上有没有是抽货的事）
        return ProfessionPools.canSell(profession, m);
    }

    /**
     * 判断某职业的基础配方里是否包含 收购 某个 Material。
     * 给 NPC 加 offer(玩家可以卖给我) 时用。
     */
    public static boolean professionCanBuy(VillagerProfession profession, Material m) {
        return ProfessionPools.canBuy(profession, m);
    }

    /** 判断一条 recipe 的 result 是否是某个 Material（砍价时定位要改哪条） */
    public static boolean recipeSells(MerchantRecipe r, Material m) {
        return r != null && r.getResult() != null && r.getResult().getType() == m;
    }

    /**
     * 全池版基础配方（给原版交易注入等老消费者用）：职业货池全量，价格走 PriceBook 按合成表推导。
     * 商店界面不用这份全量表——它走 VillagerState.buildCurrentRecipes 的按村民抽货
     * （ShopStocking），同职业每个村民的摊子不同。
     */
    private static List<MerchantRecipe> buildRecipes(VillagerProfession p) {
        List<MerchantRecipe> r = new ArrayList<>();
        for (ProfessionPools.PoolItem e : ProfessionPools.sellPool(p)) {
            r.add(createOffer(e.item(), PriceBook.sellPrice(e.item(), e.count()), e.count()));
        }
        for (ProfessionPools.PoolItem e : ProfessionPools.buyPool(p)) {
            r.add(createBuy(e.item(), e.count(), PriceBook.buybackPrice(e.item(), e.count())));
        }
        return r;
    }

    // ===== 中文→Material 查找表 =====
    // 与 PromptBuilder.ITEM_ZH 反向对齐 —— NPC 说的中文必须能翻成 Material
    private static final Map<String, Material> ZH_TO_MATERIAL = Map.ofEntries(
            // === 货币 ===
            Map.entry("绿宝石", Material.EMERALD),
            Map.entry("绿宝", Material.EMERALD),
            Map.entry("铁锭", Material.IRON_INGOT),
            Map.entry("铁", Material.IRON_INGOT),
            Map.entry("金锭", Material.GOLD_INGOT),
            Map.entry("钻石", Material.DIAMOND),

            // === 武器 ===
            Map.entry("铁剑", Material.IRON_SWORD),
            Map.entry("铁镐", Material.IRON_PICKAXE),
            Map.entry("铁斧", Material.IRON_AXE),
            Map.entry("铁锹", Material.IRON_SHOVEL),
            Map.entry("铁锄", Material.IRON_HOE),
            Map.entry("钻石剑", Material.DIAMOND_SWORD),
            Map.entry("钻石镐", Material.DIAMOND_PICKAXE),
            Map.entry("钻石斧", Material.DIAMOND_AXE),
            Map.entry("钻石锹", Material.DIAMOND_SHOVEL),
            Map.entry("弓", Material.BOW),
            Map.entry("箭", Material.ARROW),
            Map.entry("盾牌", Material.SHIELD),

            // === 盔甲 ===
            Map.entry("铁头盔", Material.IRON_HELMET),
            Map.entry("铁胸甲", Material.IRON_CHESTPLATE),
            Map.entry("铁护腿", Material.IRON_LEGGINGS),
            Map.entry("铁靴子", Material.IRON_BOOTS),
            Map.entry("钻石头盔", Material.DIAMOND_HELMET),
            Map.entry("钻石胸甲", Material.DIAMOND_CHESTPLATE),
            Map.entry("钻石护腿", Material.DIAMOND_LEGGINGS),
            Map.entry("钻石靴子", Material.DIAMOND_BOOTS),

            // === 功能物品 ===
            Map.entry("铁砧", Material.ANVIL),
            Map.entry("熔炉", Material.FURNACE),
            Map.entry("高炉", Material.BLAST_FURNACE),
            Map.entry("附魔台", Material.ENCHANTING_TABLE),
            Map.entry("工作台", Material.CRAFTING_TABLE),
            Map.entry("书架", Material.BOOKSHELF),
            Map.entry("书", Material.BOOK),
            Map.entry("箱子", Material.CHEST),
            Map.entry("火把", Material.TORCH),

            // === 食物 ===
            Map.entry("面包", Material.BREAD),
            Map.entry("小麦", Material.WHEAT),
            Map.entry("金苹果", Material.GOLDEN_APPLE),
            Map.entry("金胡萝卜", Material.GOLDEN_CARROT),
            Map.entry("牛排", Material.COOKED_BEEF),
            Map.entry("铁桶", Material.BUCKET),

            // === 矿石 ===
            Map.entry("煤炭", Material.COAL),
            Map.entry("红石粉", Material.REDSTONE),
            Map.entry("青金石", Material.LAPIS_LAZULI),

            // === 珍稀 ===
            Map.entry("不死图腾", Material.TOTEM_OF_UNDYING),
            Map.entry("鞘翅", Material.ELYTRA),
            Map.entry("末影珍珠", Material.ENDER_PEARL),
            Map.entry("附魔之瓶", Material.EXPERIENCE_BOTTLE),

            // === 方块 ===
            Map.entry("铁块", Material.IRON_BLOCK),
            Map.entry("金块", Material.GOLD_BLOCK),
            Map.entry("钻石块", Material.DIAMOND_BLOCK),
            Map.entry("绿宝石块", Material.EMERALD_BLOCK),
            Map.entry("圆石", Material.COBBLESTONE),
            Map.entry("石头", Material.STONE),
            Map.entry("橡木原木", Material.OAK_LOG),
            Map.entry("橡木板", Material.OAK_PLANKS),

            // === 原料/杂项 ===
            Map.entry("木棍", Material.STICK),
            Map.entry("线", Material.STRING),
            Map.entry("羽毛", Material.FEATHER),
            Map.entry("骨头", Material.BONE),
            Map.entry("腐肉", Material.ROTTEN_FLESH),
            Map.entry("皮革", Material.LEATHER),
            Map.entry("羊毛", Material.WHITE_WOOL),
            Map.entry("床", Material.WHITE_BED),
            Map.entry("钓鱼竿", Material.FISHING_ROD),
            Map.entry("桶", Material.BUCKET),
            Map.entry("纸", Material.PAPER),
            Map.entry("指南针", Material.COMPASS),
            Map.entry("剪刀", Material.SHEARS),
            Map.entry("鞍", Material.SADDLE),

            // === 食材 / 常见小物件（玩家随手塞给 NPC 的东西，不限于交易清单） ===
            // 为什么需要：玩家聊天说"我手里有西瓜片，你拿去吃"时，代码层要能按中文名
            // 找到玩家背包里的那件东西并真的收下。缺了这些名字，模型给的 item 参数认不出，
            // 收货逻辑就静默失败 → 出现"NPC 嘴上说收下了，东西却还在玩家手里"。
            Map.entry("西瓜片", Material.MELON_SLICE),
            Map.entry("西瓜", Material.MELON),
            Map.entry("苹果", Material.APPLE),
            Map.entry("胡萝卜", Material.CARROT),
            Map.entry("土豆", Material.POTATO),
            Map.entry("烤土豆", Material.BAKED_POTATO),
            Map.entry("甜菜根", Material.BEETROOT),
            Map.entry("南瓜", Material.PUMPKIN),
            Map.entry("南瓜派", Material.PUMPKIN_PIE),
            Map.entry("生牛肉", Material.BEEF),
            Map.entry("生猪排", Material.PORKCHOP),
            Map.entry("熟猪排", Material.COOKED_PORKCHOP),
            Map.entry("生鸡肉", Material.CHICKEN),
            Map.entry("熟鸡肉", Material.COOKED_CHICKEN),
            Map.entry("生羊肉", Material.MUTTON),
            Map.entry("熟羊肉", Material.COOKED_MUTTON),
            Map.entry("生鳕鱼", Material.COD),
            Map.entry("熟鳕鱼", Material.COOKED_COD),
            Map.entry("生鲑鱼", Material.SALMON),
            Map.entry("熟鲑鱼", Material.COOKED_SALMON),
            Map.entry("鸡蛋", Material.EGG),
            Map.entry("糖", Material.SUGAR),
            Map.entry("蜂蜜瓶", Material.HONEY_BOTTLE),
            Map.entry("甜浆果", Material.SWEET_BERRIES),
            Map.entry("甘蔗", Material.SUGAR_CANE),
            Map.entry("蘑菇", Material.BROWN_MUSHROOM),
            Map.entry("红蘑菇", Material.RED_MUSHROOM),
            Map.entry("雪球", Material.SNOWBALL),
            Map.entry("黏土球", Material.CLAY_BALL),
            Map.entry("燧石", Material.FLINT),
            Map.entry("火药", Material.GUNPOWDER),
            Map.entry("蜘蛛眼", Material.SPIDER_EYE),
            Map.entry("玻璃", Material.GLASS),
            Map.entry("沙子", Material.SAND),
            Map.entry("泥土", Material.DIRT),
            Map.entry("萤石", Material.GLOWSTONE)
    );
}
