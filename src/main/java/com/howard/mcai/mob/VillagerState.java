package com.howard.mcai.mob;

import com.howard.mcai.security.NpcRank;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 村民运行时状态 —— Paper Entity 引用 + AI 状态绑定。
 *
 * <p>三层记忆架构（SPEC 3.4）：
 * <pre>
 * summary     ← 滚动摘要（旧对话压缩成 3-5 句话）
 * recent(10)  ← 最近 10 轮原文（原样拼 prompt）
 * facts(20)   ← 关键事实 notes（如"玩家给过我钻石"，7 天未提及优先丢弃、大嘴巴传播的豁免）
 * </pre>
 * prompt 里记忆预算 = summary + recent + facts ≤ ~1500 tokens。
 * 压缩触发：chatHistory 超 10 轮 **或** 估算 token 超配置上限 → 最老一轮并进 summary，
 * 并在同一次触发里执行 facts 的 7 天遗忘。
 */
public final class VillagerState {

    /** Paper 世界里对应的 Villager 实体 */
    private final Villager entity;

    /**
     * AI 侧的职业枚举（映射 Paper Villager.Profession）。
     * <p>
     * <b>volatile 且可变</b>：原版村民刚加载时 {@code getProfession()} 常常还是 {@code none} ——
     * 要等所在区块开始 tick、认领附近的工作站（POI）之后才真正拿到职业。
     * 这里必须允许运行期刷新（见 {@link #refreshProfession()}），否则"后来才上工的村民"
     * 在插件里会永远显示无业（GUI 状态卡、人设、交易配方全按无业走）。
     */
    private volatile VillagerProfession profession;

    /** 情绪状态（fear, favor） */
    private final EmotionState emotion;

    /**
     * 当前正在对话的玩家 UUID。null 表示空闲状态。
     * <p>
     * volatile：AsyncPlayerChatEvent 在异步线程读它来路由消息（PlayerChatListener），
     * 主线程写（startConversation/endConversation）—— 不加 volatile 异步线程可能读到过期值。
     */
    private volatile UUID interactingPlayer;

    /**
     * 三层记忆 —— recent 是核心，summary/facts 是辅助。
     * 压缩触发：chatHistory 超过 MAX_RECENT（10 轮 = 20 条）或估算 token 超限时，
     * 把最老的一轮（user+assistant 成对）并进 summary，再从 chatHistory 里移除。
     * 必须按轮删：只删一条会让历史以 assistant 开头，与 PromptBuilder 拼接后错位。
     */
    private static final int MAX_RECENT = 10;
    private final List<ChatEntry> chatHistory = new ArrayList<>();  // = recent
    private StringBuilder summary = new StringBuilder();            // = summary（滚动压缩）
    /** = facts（关键事实，带时间戳 + 大嘴巴豁免标记，见 {@link Fact}） */
    private final List<Fact> facts = new ArrayList<>();

    /**
     * chatHistory 压缩的 token 触发阈值（config memory.max-chat-history-tokens 的静态镜像）。
     * VillagerState 没有 plugin 引用拿不到配置，用静态镜像 + onEnable/ai reload 时同步更新
     * —— 与 PromptBuilder.setRecipeLookup 是同一模式。
     */
    private static volatile int maxChatHistoryTokens = 1500;

    /** /ai reload 后同步最新阈值（McaiPlugin 调用） */
    public static void setMaxChatHistoryTokens(int tokens) {
        if (tokens > 0) maxChatHistoryTokens = tokens;
    }

    /** 玩家最后一条消息后累计经过的游戏 tick（planner 每 interval tick 才跑一次，必须累计真实 tick） */
    private int idleElapsedTicks;

    /** 对话空闲超时：600 游戏 tick = 30 秒没说话自动结束对话 */
    private static final int CONVERSATION_TIMEOUT_TICKS = 600;

    /** LLM 请求是否在飞行中 —— 防并发。acquire/release 全在主线程，天然串行 */
    private final AtomicBoolean llmInFlight = new AtomicBoolean(false);

    /**
     * 积压消息队列：LLM 忙时玩家又发消息 → 消息先进队列，**不直接进 chatHistory**。
     * 等当前响应的 assistant 入历史后再统一入队并续调，
     * 保证历史严格 user↔assistant 交替（Jinja 铁律），也避免 assistant 插在两条 user 中间。
     */
    private final List<String> pendingMessages = new ArrayList<>();

    // ===== 近期社交上下文（群聊/闲聊刚说过的话） =====

    /**
     * NPC 在群体聊天 / 自动闲聊中刚说过的台词（最多 2 条，60 秒过期）。
     * <p>
     * 群聊/闲聊是一次性回复、不写 chatHistory（保 Jinja 交替），所以正式对话时
     * 这个 NPC 的 chatHistory 是空的，LLM 会把它当"初次见面"重新寒暄推销——很突兀。
     * 这里缓存最近说过的台词，正式对话时注入 prompt 让模型自然衔接。
     */
    private final List<SocialLine> recentSocialLines = new ArrayList<>();
    private static final int MAX_SOCIAL_LINES = 2;
    private static final long SOCIAL_CONTEXT_WINDOW_MS = 60_000L;

    /** 一条近期社交台词 + 说出的时刻（毫秒） */
    private record SocialLine(String text, long atMs) {}

    /** 记录一条群聊/闲聊台词（回主线程冒气泡时调用；自动裁剪到最近 2 条） */
    public void addRecentSocialLine(String line) {
        if (line == null || line.isBlank()) return;
        recentSocialLines.add(new SocialLine(line, System.currentTimeMillis()));
        while (recentSocialLines.size() > MAX_SOCIAL_LINES) {
            recentSocialLines.remove(0);
        }
        // 需求满足：开口说了话 = 社交得到满足、也不那么闷了（闲聊/群聊/自主行动共用入口）
        needs.satisfy(NeedsState.SOCIAL, 12.0);
        needs.satisfy(NeedsState.BOREDOM, 8.0);
    }

    /**
     * 60 秒内是否在群聊/闲聊中说过话；说过 → 返回台词摘要（供 prompt 注入），没有 → null。
     * 调用时顺带清掉过期的条目，防止缓存无限膨胀。
     */
    public String recentSocialContext() {
        long now = System.currentTimeMillis();
        recentSocialLines.removeIf(sl -> now - sl.atMs() > SOCIAL_CONTEXT_WINDOW_MS);
        if (recentSocialLines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (SocialLine sl : recentSocialLines) {
            if (sb.length() > 0) sb.append("、");
            sb.append("“").append(sl.text()).append("”");
        }
        return sb.toString();
    }

    // ===== 受击记录（场景上下文 was_hit_recently 用） =====

    /** 最后一次被打的时间戳（毫秒）。Entity.getLastDamage() 是伤害数值不是时间，不能用它 */
    private volatile long lastHitAtMs = 0L;

    public void markHit() {
        lastHitAtMs = System.currentTimeMillis();
    }

    /** 30 秒内被打过 → 场景上下文让 NPC 提"刚才谁打我" */
    public boolean wasHitRecently() {
        return lastHitAtMs > 0 && System.currentTimeMillis() - lastHitAtMs < 30_000L;
    }

    /** 距上次被打的秒数，从没被打过返回 -1 */
    public long lastHitAgeSeconds() {
        return lastHitAtMs == 0L ? -1L : (System.currentTimeMillis() - lastHitAtMs) / 1000L;
    }

    /**
     * 动态价格表 —— 砍价/上架后永久生效（跨对话保留）。
     * key = Material 英文名（IRON_SWORD），value = 当前绿宝价格。
     * 每次 buildCurrentRecipes 时用这个覆盖基础配方的价格。
     * 空 map 表示用 TradeRegistry 基础配方的默认价。
     */
    private final Map<String, Integer> dynamicPrices = new java.util.HashMap<>();

    /** 上架商品：价格 + 数量 + 可选附魔（enchant 为 null = 普通物品），跨对话持久化 */
    public record OfferedItem(int price, int count, String enchant) {
        /** 无附魔的普通上架（旧调用兼容） */
        public OfferedItem(int price, int count) {
            this(price, count, null);
        }
    }

    private final Map<String, OfferedItem> offeredItems = new java.util.HashMap<>();

    /** 砍价或上架后调这个 —— 改当前会话的动态价格（内存态） */
    public synchronized void setItemPrice(String materialName, int emeralds) {
        if (emeralds <= 0) return;
        dynamicPrices.put(materialName, emeralds);
        // 注：dynamicPrices 会经 MemoryStore 持久化到 JSON，跨 reload 不再丢失
    }

    /** 上架新商品（价格同时进 dynamicPrices，GUI 重建时统一覆盖）—— 无附魔 */
    public synchronized void addOfferedItem(String materialName, int price, int count) {
        addOfferedItem(materialName, price, count, null);
    }

    /** 上架新商品（含可选附魔，enchant 非空 = 附魔商品，GUI 重建时按附魔名重造配方） */
    public synchronized void addOfferedItem(String materialName, int price, int count, String enchant) {
        offeredItems.put(materialName, new OfferedItem(Math.max(1, price), Math.max(1, count), enchant));
        dynamicPrices.put(materialName, Math.max(1, price));
    }

    /** 下架：移除上架条目和动态价（基础配方里的物品下次开 GUI 仍会回到店里） */
    public synchronized void removeOffered(String materialName) {
        offeredItems.remove(materialName);
        dynamicPrices.remove(materialName);
    }

    /** 上架表快照（MemoryStore 持久化用） */
    public synchronized Map<String, OfferedItem> offeredItems() {
        return new java.util.HashMap<>(offeredItems);
    }

    // 砍价锁已删除（2026-10-21）：聊天砍价与模型改价双双退役后，价格只在商店界面
    // 里由 BargainPolicy + ShopSession 决定，不再有「锁价防覆盖」的需求。

    /** 动态价格表（PromptBuilder/MemoryStore 用） */
    public java.util.Map<String, Integer> dynamicPrices() {
        return dynamicPrices;
    }

    /**
     * 构建当前生效的配方列表 —— 深拷贝基础配方 + 上架商品 + 动态价格覆盖。
     * <p>
     * 关键：必须对 TradeRegistry 返回的全局缓存做**深拷贝**，
     * 否则砍价 setAmount 会原地污染缓存，同职业所有村民的默认价都被改掉。
     */
    public java.util.List<org.bukkit.inventory.MerchantRecipe> buildCurrentRecipes() {
        java.util.List<org.bukkit.inventory.MerchantRecipe> recipes = new java.util.ArrayList<>();
        // 基础摊位：按村民抽货（ShopStocking 用村民 UUID 做固定种子）——同职业每个村民
        // 卖的东西、数量、价格都不同，不再千人一面；价格由 PriceBook 按合成表推导。
        java.util.Set<org.bukkit.Material> stockedSell = new java.util.HashSet<>();
        for (com.howard.mcai.ai.ShopStocking.StockedItem s
                : com.howard.mcai.ai.ShopStocking.stockFor(profession, getUniqueId())) {
            recipes.add(s.buyBack()
                    ? com.howard.mcai.ai.TradeRegistry.createBuy(s.item(), s.count(), s.priceEmeralds())
                    : com.howard.mcai.ai.TradeRegistry.createOffer(s.item(), s.priceEmeralds(), s.count()));
            if (!s.buyBack()) stockedSell.add(s.item());
        }
        // 附加 LLM 上架的额外商品（基础清单里没有的）—— 附魔商品用 createEnchantedOffer 重造。
        // 上架价不能照单全收：先过 PriceBook 的账夹紧（旧存档里的离谱价会被拉回合理带）。
        for (var e : offeredItems.entrySet()) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(e.getKey());
            if (m == null) continue;
            boolean exists = recipes.stream().anyMatch(r -> r.getResult() != null && r.getResult().getType() == m);
            if (!exists) {
                String enchant = e.getValue().enchant();
                int price = com.howard.mcai.ai.PriceBook.clampNegotiatedPrice(
                        m, e.getValue().count(), e.getValue().price());
                if (enchant != null) price = Math.max(1, price * 3 / 2); // 附魔溢价五成
                recipes.add((enchant != null)
                        ? com.howard.mcai.ai.TradeRegistry.createEnchantedOffer(
                                m, price, e.getValue().count(), enchant)
                        : com.howard.mcai.ai.TradeRegistry.createOffer(
                                m, price, e.getValue().count()));
            }
        }
        if (dynamicPrices.isEmpty()) return recipes;
        // 动态价格覆盖"卖"配方的价格，但两条铁规（2026-10-21 价格改革落空事故）：
        // ① 本摊商品（职业货池抽出来的）只认 PriceBook，动态价一律不许盖——旧时代
        //    存进存档的动态价（铁块 20 之类）就是这样把新价格全顶掉的；
        // ② 非本摊商品（模型谈出来的）也要先过账夹紧才准用。
        for (var r : recipes) {
            if (r.getResult() == null || r.getResult().getType() == org.bukkit.Material.EMERALD) continue;
            if (stockedSell.contains(r.getResult().getType())) continue;
            Integer newPrice = dynamicPrices.get(r.getResult().getType().name());
            if (newPrice != null) {
                int clamped = com.howard.mcai.ai.PriceBook.clampNegotiatedPrice(
                        r.getResult().getType(), r.getResult().getAmount(), newPrice);
                com.howard.mcai.ai.TradeRegistry.setEmeraldPrice(r, clamped);
            }
        }
        return recipes;
    }

    // ===== 性格：开放度（学习接受度） =====

    /**
     * 开放度 0-100 —— 这个 NPC 有多"好学/轻信"。
     * 构造时随机生成（固执的铁匠可能 25，好奇的图书管理员可能 90），
     * 决定接受别人教学的门槛：threshold = 100 - openness - 熟悉度加成。
     * 玩家反复聊天教学（熟悉度↑）会逐渐降低门槛 —— "慢热但会熟络"。
     * MemoryStore 持久化，重启后性格不变。
     */
    private int openness;

    /** 教学熟悉度：sourceName（玩家名）→ 被教学次数。反复教学降低接受门槛（会话级） */
    private final Map<String, Integer> teachFamiliarity = new java.util.HashMap<>();

    /**
     * 关键事实硬上限 20 条（硬约束——旧版 30 超标）：事件记忆 + 学来的知识共享这个池子。
     * 超限时优先丢 7 天未提及的，大嘴巴传播来的最后才动。
     */
    private static final int MAX_FACTS = 20;

    /**
     * 人格标签 id 列表（personality_tags.json 抽取）—— 决定"这个 NPC 是哪种人"。
     * 构造时抽一次并持久化，重启不变（每个 NPC 的独特性格跨会话保留）。
     */
    private final List<String> personalityTagIds;

    /**
     * 性别 "M"/"F" —— <b>跟着名字走</b>（名字用字分男女），由 {@link #gender()} 从实体名反推；
     * 这个字段只在名字判不出性别时（玩家自定义名等）作为持久化兜底。
     * 仅影响称呼与家庭关系表述，不改人格（用户确认的边界）。
     */
    private String gender;

    /** 家庭关系列表（spouse=配偶/夫妻、sibling=兄弟姐妹），职业/地缘配对生成，持久化 */
    private final List<Relationship> relationships = new ArrayList<>();

    // ===== 需求系统（SPEC 2.14）=====

    /**
     * 五项需求（hunger/energy/boredom/curiosity/social，0-100）—— 主动性的来源。
     * 初始值：hunger 30 / energy 70 / boredom 20 / curiosity 20 / social 30，
     * NeedsLayer 每 30 秒按情境 + 性格标签修正增长；持久化到记忆文件（重启需求连续）。
     */
    private final NeedsState needs = new NeedsState();

    // ===== 权限等级（SPEC 2.14 三档权限）=====

    /**
     * 神使标记（NpcRank.name()）。平民/亲信是派生身份不落字段：
     * 亲信由 favor ≥ 阈值动态判定（PermissionManager.rankOf），这里只持久化 ENVOY/CIVILIAN。
     * 玩家可用 /ai envoy grant/revoke 授予/撤销，撤销后回到派生判定（多数情况下=平民）。
     */
    private volatile String rank = NpcRank.CIVILIAN.name();

    // ===== NPC 库存（世界交互 give/place/equip 的物资来源）=====

    /**
     * 简化库存：Material.name() → 数量。村民原版没有背包，AI 侧维护一张账面库存
     * 支撑 give_item（送礼看库存）/ place_block（放方块扣货）/ equip（穿戴消耗）。
     * 新村民初始带口粮 + 职业物品 + 随机战利品（见 {@link #seedProfessionInventory}）。
     */
    private final Map<String, Integer> inventory = new java.util.HashMap<>();

    /**
     * 背包是否已按职业投放（一次性迁移标记）。
     * 旧存档没有这个字段 → 读档时判为 false → 补发一次职业物品（只增不删，保留面包）；
     * 补发后置 true 落盘，防止每次重启都重复往背包里塞东西。
     */
    private boolean inventorySeeded;

    /**
     * 启动钱是否已发（一次性标记，守卫逻辑同 inventorySeeded）。
     * 旧存档没有这个字段 → 读档时补发一次；花光不补，之后靠卖货回血。
     */
    private boolean walletSeeded;

    /**
     * v0.1.3 收购钱包扩容是否已执行（一次性迁移标记）。
     * 新村民出生时就按新标准带钱，所以构造后直接视为已扩容；旧存档没有
     * 这个字段 → 读档时补足一次后置 true 落盘，避免每次重启重复送钱。
     */
    private boolean walletV013Boosted;

    /** 无关战利品池 —— 村民各处捡来的杂物，随机投放 1-3 种（与职业无关） */
    private static final String[] LOOT_POOL = {
            "ROTTEN_FLESH", "BONE", "SPIDER_EYE", "STRING", "GUNPOWDER", "ENDER_PEARL",
            "FLINT", "COAL", "APPLE", "FEATHER", "STICK", "SLIME_BALL"
    };

    public VillagerState(Villager entity) {
        this.entity = entity;
        this.profession = VillagerProfession.fromMinecraftProfession(entity.getProfession());
        this.emotion = new EmotionState();
        this.openness = 25 + (int) (Math.random() * 71);  // 25~95，人人不同
        // v2：傻子村民走 nitwit 隔离池（不抽精明/浪漫类标签），普通村民走全量池
        this.personalityTagIds = com.howard.mcai.ai.PersonalityTagLibrary.drawTagIds(
                this.profession == VillagerProfession.NITWIT);
        this.gender = inferGenderOrDefault(entity);  // 优先按名字定性别，判不出才随机
        seedProfessionInventory();
        this.inventorySeeded = true;
        seedWallet();
        this.walletSeeded = true;
        this.walletV013Boosted = true;
    }

    public VillagerState(Villager entity, VillagerProfession profession) {
        this.entity = entity;
        this.profession = profession != null ? profession : VillagerProfession.fromMinecraftProfession(entity.getProfession());
        this.emotion = new EmotionState();
        this.openness = 25 + (int) (Math.random() * 71);
        // v2：同上，傻子村民走 nitwit 隔离池
        this.personalityTagIds = com.howard.mcai.ai.PersonalityTagLibrary.drawTagIds(
                this.profession == VillagerProfession.NITWIT);
        this.gender = inferGenderOrDefault(entity);
        seedProfessionInventory();
        this.inventorySeeded = true;
        seedWallet();
        this.walletSeeded = true;
        this.walletV013Boosted = true;
    }

    /** 背包是否已按职业投放（MemoryStore 迁移守卫用） */
    public boolean isInventorySeeded() {
        return inventorySeeded;
    }

    /** 标记背包已投放（迁移补发后由 MemoryStore 调用） */
    public void setInventorySeeded(boolean seeded) {
        this.inventorySeeded = seeded;
    }

    /** 启动钱是否已发（MemoryStore 迁移守卫用） */
    public boolean isWalletSeeded() {
        return walletSeeded;
    }

    /** 标记启动钱已发（迁移补发后由 MemoryStore 调用） */
    public void setWalletSeeded(boolean seeded) {
        this.walletSeeded = seeded;
    }

    /** v0.1.3 收购钱包扩容是否已执行（MemoryStore 迁移守卫用） */
    public boolean isWalletV013Boosted() {
        return walletV013Boosted;
    }

    /** 标记 v0.1.3 收购钱包扩容已执行（迁移完成后由 MemoryStore 调用） */
    public void setWalletV013Boosted(boolean boosted) {
        this.walletV013Boosted = boosted;
    }

    /**
     * 启动钱 —— 村民随身带的绿宝石（Fong 2026-10-21）。
     * 收购要从村民兜里真出钱：没有这笔种子钱，村民在卖出第一件货之前永远收不了
     * 玩家的东西，村子里的买卖根本转不起来。v0.1.3 按 Fong 的要求把最终金额
     * 提高到原来的 3 倍：有手艺的带 36–90 块，没职业/傻子带 9–30 块。
     * 只发一次（walletSeeded 落盘守卫），花光不补。
     */
    public synchronized void seedWallet() {
        int amount = WalletAmounts.seedAmount(profession, new java.util.Random());
        inventory.merge(org.bukkit.Material.EMERALD.name(), amount, Integer::sum);
    }

    /**
     * v0.1.3 老村民钱包补足：按新标准抽一个目标余额，只补差额。
     * 已经比目标更有钱的村民不扣钱；余额为 0 的村民也能重新有钱收购。
     * 返回实际补入的绿宝石数量。
     */
    public synchronized int topUpWalletForV013() {
        int current = inventory.getOrDefault(org.bukkit.Material.EMERALD.name(), 0);
        int added = WalletAmounts.topUpAmount(profession, current, new java.util.Random());
        if (added > 0) {
            inventory.merge(org.bukkit.Material.EMERALD.name(), added, Integer::sum);
        }
        return added;
    }

    /**
     * 按职业投放背包 —— <b>只增不删</b>（merge，保留已有面包），构造新村民与老存档迁移补发都走这里。
     * <ul>
     *   <li>口粮：8 个面包（农夫 16 个）—— 饿了能自己吃（需求规则满足）</li>
     *   <li>职业物品：优先用 {@code profession.allowedRecipes}，空清单职业用 {@link #fallbackProfessionItems}
     *       兜底；随机 2-3 种、每种 1-8 个</li>
     *   <li>无关战利品：{@link #LOOT_POOL} 随机 1-3 种、每种 1-5 个</li>
     * </ul>
     */
    public synchronized void seedProfessionInventory() {
        java.util.Random rng = new java.util.Random();
        // 口粮 —— 农夫多带一份
        inventory.put(org.bukkit.Material.BREAD.name(), profession == VillagerProfession.FARMER ? 16 : 8);

        // 职业物品 —— 抽成独立方法，供"职业刷新"复用（只补职业物品，不重发口粮/战利品）
        seedProfessionPool();

        // 无关战利品 —— 独立池，随机 1-3 种
        seedRandomItems(java.util.Arrays.asList(LOOT_POOL), 1 + rng.nextInt(3), 1, 5, rng);
    }

    /**
     * 只补"当前职业的招牌物品"（merge 只增不删，跳过无效 Material）。
     * <p>
     * 与 {@link #seedProfessionInventory()} 的区别：这里<b>不含口粮与无关战利品</b>。
     * 用于职业刷新（无业 → 有业）——只把新职业的物品补进背包，不重复塞面包/战利品。
     */
    public synchronized void seedProfessionPool() {
        java.util.Random rng = new java.util.Random();
        java.util.List<String> pool = profession.allowedRecipes.isEmpty()
                ? fallbackProfessionItems(profession)
                : profession.allowedRecipes.stream()
                        .map(s -> s.replace("minecraft:", "").toUpperCase()).toList();
        seedRandomItems(pool, 2 + rng.nextInt(2), 1, 8, rng);  // 2-3 种
    }

    /** 空 allowedRecipes 职业的兜底物品表（Material.name()）—— 按职业写死，贴合人设 */
    private static java.util.List<String> fallbackProfessionItems(VillagerProfession p) {
        return switch (p) {
            case CARTOGRAPHER -> java.util.List.of("PAPER", "COMPASS");
            case BUTCHER -> java.util.List.of("BEEF", "PORKCHOP");
            case LEATHERWORKER -> java.util.List.of("LEATHER", "RABBIT_HIDE");
            case SHEPHERD -> java.util.List.of("WHITE_WOOL", "STRING", "SHEARS");
            case NITWIT -> java.util.List.of("WHEAT_SEEDS", "BREAD");
            case NONE -> java.util.List.of("WHEAT_SEEDS", "STICK");
            default -> java.util.List.of();
        };
    }

    /** 从候选池随机挑 n 种物品入库（每种数量 [min,max]）；merge 只增不删，跳过无效 Material */
    private void seedRandomItems(java.util.List<String> pool, int n, int min, int max, java.util.Random rng) {
        if (pool.isEmpty()) return;
        java.util.List<String> copy = new java.util.ArrayList<>(pool);
        java.util.Collections.shuffle(copy, rng);
        int added = 0;
        for (String key : copy) {
            if (added >= n) break;
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(key);
            if (m == null || m.isAir()) continue;
            inventory.merge(m.name(), min + rng.nextInt(max - min + 1), Integer::sum);
            added++;
        }
    }

    /** 构造时定性别：优先从实体名反推（名字用字分男女），推不出才随机（兜底持久化） */
    private static String inferGenderOrDefault(Villager entity) {
        String n = entity.getCustomName() != null ? entity.getCustomName().replaceAll("§.", "") : null;
        String g = com.howard.mcai.ai.NameLibrary.inferGender(n);
        return g != null ? g : (Math.random() < 0.5 ? "F" : "M");
    }

    // ===== 需求系统 / 权限 / 库存 访问器（SPEC 2.14）=====

    /** 五项需求（NeedsLayer 增长，PromptBuilder 注入 scene，规则/LLM 行动满足） */
    public NeedsState needs() {
        return needs;
    }

    /** 持久化的等级标记（仅 ENVOY/CIVILIAN；亲信是 favor 派生，见 PermissionManager.rankOf） */
    public NpcRank rank() {
        return NpcRank.fromString(rank);
    }

    /** 授予/撤销神使（/ai envoy 命令调用；MemoryStore 持久化） */
    public void setRank(NpcRank newRank) {
        this.rank = newRank.name();
    }

    /** 某物资库存量 */
    public synchronized int stockOf(org.bukkit.Material mat) {
        return inventory.getOrDefault(mat.name(), 0);
    }

    /** 库存是否有该物资（>0） */
    public synchronized boolean hasInInventory(org.bukkit.Material mat) {
        return inventory.getOrDefault(mat.name(), 0) > 0;
    }

    /** 入库（give/take/装备换下回收入库） */
    public synchronized void addToInventory(org.bukkit.Material mat, int count) {
        if (mat == null || mat.isAir() || count <= 0) return;
        inventory.merge(mat.name(), count, Integer::sum);
    }

    /** 出库（给/放/穿/吃）：库存不足返回 false 不扣 */
    public synchronized boolean removeFromInventory(org.bukkit.Material mat, int count) {
        int have = inventory.getOrDefault(mat.name(), 0);
        if (have < count) return false;
        if (have == count) inventory.remove(mat.name());
        else inventory.put(mat.name(), have - count);
        return true;
    }

    /** 库存快照（PromptBuilder scene 注入 / MemoryStore 序列化用） */
    public synchronized Map<String, Integer> inventorySnapshot() {
        return new java.util.HashMap<>(inventory);
    }

    /** MemoryStore 从 JSON 恢复库存（覆盖开家底；旧文件无该字段 → 保留初始家底） */
    public synchronized void restoreInventory(Map<String, Integer> restored) {
        inventory.clear();
        if (restored != null) inventory.putAll(restored);
    }

    /** MemoryStore 从 JSON 恢复需求（持久化键固定顺序 ALL） */
    public synchronized void restoreNeeds(Map<String, Double> restored) {
        if (restored == null) return;
        for (var e : restored.entrySet()) {
            needs.add(e.getKey(), e.getValue() - needs.get(e.getKey()));
        }
    }

    public int openness() {
        return openness;
    }

    /** 直接设开放度（MemoryStore 从 JSON 恢复用， clamp 到 0-100） */
    public void setOpenness(int openness) {
        this.openness = Math.max(0, Math.min(100, openness));
    }

    /** 记录一次"这个来源又来教我东西了" —— 熟悉度 +1，接受门槛随之降低 */
    public synchronized void recordTeachAttempt(String sourceName) {
        teachFamiliarity.merge(sourceName, 1, Integer::sum);
    }

    /** 某来源的熟悉度（教过我几次） */
    public synchronized int teachFamiliarity(String sourceName) {
        return teachFamiliarity.getOrDefault(sourceName, 0);
    }

    /**
     * 我现在信不信这个来源教的东西？
     * 规则：favor >= threshold → 接受。
     * threshold = clamp((100 - openness - familiar*8) * (1.5 - openMindedness), 10, 110)
     * —— 基础逻辑：开放度高的人一听就信；固执的人要么处熟了（favor↑）要么被教多了（familiar↑）才信。
     * 人格标签修正：openMindedness 高（如 0.7）→ 系数 0.8 门槛更低更好信；低（如 0.2）→ 系数 1.3 更固执。
     */
    public synchronized boolean wouldAcceptTeaching(String sourceName, java.util.UUID playerUuid) {
        double favor = emotion().favor(playerUuid);
        int familiar = teachFamiliarity.getOrDefault(sourceName, 0);
        double base = (100 - openness - familiar * 8) * (1.5 - openMindedness());
        int threshold = Math.max(10, Math.min(110, (int) Math.round(base)));
        return favor >= threshold;
    }

    // ===== 人格标签（personality_tags.json） =====

    /** 已抽取的人格标签 id 列表（PromptBuilder 注入 / MemoryStore 持久化用） */
    public List<String> personalityTagIds() {
        return personalityTagIds;
    }

    /** MemoryStore 从 JSON 恢复标签（重启后性格不变） */
    public synchronized void restorePersonalityTags(List<String> ids) {
        personalityTagIds.clear();
        if (ids != null) personalityTagIds.addAll(ids);
    }

    /**
     * 派生：开放心智 openMindedness [0,1] —— 基础 0.5 + 人格标签 open_mindedness 修正值，钳制。
     * 用于 wouldAcceptTeaching 门槛修正（高=更容易相信别人教的东西）。
     * 没抽到相关标签的 NPC 恒为 0.5 → 系数 1.0，行为与旧版完全一致。
     */
    public double openMindedness() {
        double v = 0.5;
        for (String id : personalityTagIds) {
            var t = com.howard.mcai.ai.PersonalityTagLibrary.tagById(id);
            if (t != null) v += t.trait("open_mindedness");
        }
        return Math.max(0, Math.min(1, v));
    }

    /**
     * 派生：大嘴巴程度 gossipProne [0,1] —— 基础 0.1 + 人格标签 gossip_prone 修正值，钳制。
     * 用于 SocialLayer 八卦概率（大嘴巴村民更爱把见闻传出去）。
     */
    public double gossipProne() {
        double v = 0.1;
        for (String id : personalityTagIds) {
            var t = com.howard.mcai.ai.PersonalityTagLibrary.tagById(id);
            if (t != null) v += t.trait("gossip_prone");
        }
        return Math.max(0, Math.min(1, v));
    }

    // ===== 性别 & 家庭关系（社会关系系统） =====

    /** 一条家庭关系：type=spouse（配偶/夫妻）或 sibling（兄弟姐妹），存对方 uuid + 名字供 prompt 引用 */
    public record Relationship(String type, String targetUuid, String targetName) {}

    /**
     * 当前性别 —— 名字用字能判出就<b>以名字为准</b>（覆盖持久化值，兼容旧数据与随姓改名），
     * 判不出才回退构造/持久化的 gender 字段。
     */
    public String gender() {
        if (entity != null && entity.getCustomName() != null) {
            String inferred = com.howard.mcai.ai.NameLibrary.inferGender(entity.getCustomName());
            if (inferred != null) return inferred;
        }
        return gender;
    }

    /** MemoryStore 从 JSON 恢复性别（仅作为名字判不出时的兜底，非法值忽略） */
    public synchronized void setGender(String g) {
        if ("M".equals(g) || "F".equals(g)) {
            this.gender = g;
        }
    }

    public boolean isMale() {
        return "M".equals(gender());
    }

    public boolean isFemale() {
        return "F".equals(gender());
    }

    /** 中文性别标签（prompt 拼 Family & Social 段用） */
    public String genderZh() {
        return isFemale() ? "女" : "男";
    }

    /** 只读视角的关系列表（MemoryStore 序列化 / PromptBuilder 注入用） */
    public synchronized List<Relationship> relationships() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(relationships));
    }

    /** 添加一条关系（SocialRelationshipGenerator 双向写入用；同 type + 同目标去重） */
    public synchronized void addRelationship(String type, String targetUuid, String targetName) {
        for (Relationship r : relationships) {
            if (r.type().equals(type) && r.targetUuid().equals(targetUuid)) return;
        }
        relationships.add(new Relationship(type, targetUuid, targetName));
    }

    /** MemoryStore 从 JSON 恢复关系列表（重启后已配对的不被重新覆盖） */
    public synchronized void restoreRelationships(List<Relationship> rels) {
        relationships.clear();
        if (rels != null) relationships.addAll(rels);
    }

    /** 有没有配偶（配对幂等守卫 + prompt 分支用） */
    public synchronized boolean hasSpouse() {
        for (Relationship r : relationships) {
            if ("spouse".equals(r.type())) return true;
        }
        return false;
    }

    /** 第一个配偶关系（无则 null） */
    public synchronized Relationship spouse() {
        for (Relationship r : relationships) {
            if ("spouse".equals(r.type())) return r;
        }
        return null;
    }

    /** 与指定目标是否已有某类型关系（配对器防重复用） */
    public synchronized boolean hasRelationshipWith(String targetUuid, String type) {
        for (Relationship r : relationships) {
            if (r.type().equals(type) && r.targetUuid().equals(targetUuid)) return true;
        }
        return false;
    }

    /**
     * 全量替换指向某目标的关系显示名（兄弟随姓改名后调用）。
     * 目标实体被改名时，所有关系记录里的 targetName 必须同步，否则 prompt 会显示旧名字。
     */
    public synchronized void renameRelationshipTarget(String targetUuid, String newName) {
        for (int i = 0; i < relationships.size(); i++) {
            Relationship r = relationships.get(i);
            if (r.targetUuid().equals(targetUuid)) {
                relationships.set(i, new Relationship(r.type(), r.targetUuid(), newName));
            }
        }
    }

    /** 兄弟姐妹列表（sibling 类型；最多 2 个由配对算法保证） */
    public synchronized List<Relationship> siblings() {
        java.util.List<Relationship> out = new ArrayList<>();
        for (Relationship r : relationships) {
            if ("sibling".equals(r.type())) out.add(r);
        }
        return out;
    }

    // ===== Entity / Profession =====

    public Villager entity() {
        return entity;
    }

    public UUID getUniqueId() {
        return entity.getUniqueId();
    }

    public VillagerProfession profession() {
        return profession;
    }

    /**
     * 重读实体职业并同步到 AI 侧（<b>必须在 Paper 主线程调用</b>）。
     * <p>
     * 场景：村民刚加载时职业常为 {@code none}，要等区块 tick + 认领工作站后才真的上工；
     * 玩家第一次交互、社交层轮询时再读一次，就能把"有职业却显示无业"纠正过来。
     *
     * @return true = 职业确实发生了变化（调用方需要补发职业物品池 + 重注入交易配方）
     */
    public synchronized boolean refreshProfession() {
        if (entity == null || !entity.isValid()) return false;
        VillagerProfession now = VillagerProfession.fromMinecraftProfession(entity.getProfession());
        if (now == profession) return false;
        this.profession = now;
        return true;
    }

    // ===== Emotion =====

    public EmotionState emotion() {
        return emotion;
    }

    // ===== Three-Layer Memory =====

    public List<ChatEntry> chatHistory() {
        return chatHistory;
    }

    public String summary() {
        return summary.toString().trim();
    }

    /**
     * facts 纯文本视图（PromptBuilder 注入 prompt / SocialLayer 挑八卦素材用）。
     * 返回副本：外部拿到的是 List<String>，改它不会破坏内部带时间戳的 Fact 结构。
     */
    public synchronized List<String> facts() {
        List<String> out = new ArrayList<>(facts.size());
        for (Fact f : facts) out.add(f.text());
        return out;
    }

    /** facts 完整视图（含时间戳/豁免标记）—— MemoryStore 序列化用 */
    public synchronized List<Fact> factEntries() {
        return new ArrayList<>(facts);
    }

    /**
     * 一条关键事实 + 元数据。
     *
     * @param text         事实文本
     * @param ts           最近一次写入/被提及的时刻（毫秒）——7 天遗忘从它起算
     * @param gossipExempt 大嘴巴传播来的信息不参与 7 天遗忘（硬约束：大嘴巴信息不参与遗忘）
     * @param ownerUuid    归属玩家 UUID 字符串（null = 公共知识，所有玩家可见）。
     *                     玩家相关的记忆（被打/被送礼/教知识）打上 owner，避免跨玩家泄露
     */
    public record Fact(String text, long ts, boolean gossipExempt, String ownerUuid) {
        /** 旧构造兼容（无 owner → 公共知识）—— MemoryStore 读旧档与 gossip 用 */
        public Fact(String text, long ts, boolean gossipExempt) {
            this(text, ts, gossipExempt, null);
        }
    }

    /** facts 遗忘窗口：7 天（毫秒）没被提及的非豁免事实，压缩触发时优先丢弃 */
    private static final long FACT_EXPIRE_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * 添加一条关键事实（上限 {@value MAX_FACTS} 条，超限按"过期→最旧"优先淘汰）。
     * facts 是"永久记忆"——玩家给过钻石、帮过忙、打过我、
     * 以及**别人教会我的知识**（"听旅者Steve说，..."）这种不能忘的事。
     */
    public synchronized void addFact(String fact) {
        addFactInternal(fact, false, null);
    }

    /**
     * 添加一条"属于某玩家"的关键事实 —— 打上 ownerUuid，仅该玩家在 prompt 里能看到。
     * 玩家相关的事件（被打/被送礼/教知识）必须走这个重载，避免跨玩家泄露记忆。
     *
     * @param ownerUuid 归属玩家；null 等价于 {@link #addFact(String)}（公共知识）
     */
    public synchronized void addFact(String fact, java.util.UUID ownerUuid) {
        addFactInternal(fact, false, ownerUuid == null ? null : ownerUuid.toString());
    }

    /**
     * 添加传闻事实（村民传话用，SocialLayer 的 A→B 传话路径）—— 与 addFact 相同，
     * 但打上大嘴巴豁免标记（gossip 不参与 7 天遗忘），并返回是否真的新增。
     * 防止 A→B→C 传话链把同一条传闻在记忆里堆满。
     */
    public synchronized boolean addGossipFact(String fact) {
        return addFactInternal(fact, true, null);
    }

    /**
     * 某玩家在 prompt 中可见的事实：公共事实（owner=null）+ 该玩家自己的事实。
     * 玩家相关记忆只回给当事玩家，其他玩家看不到（防跨玩家泄露）。
     */
    public synchronized List<String> factsFor(java.util.UUID playerUuid) {
        String pid = playerUuid == null ? null : playerUuid.toString();
        List<String> out = new ArrayList<>();
        for (Fact f : facts) {
            if (f.ownerUuid() == null || f.ownerUuid().equals(pid)) out.add(f.text());
        }
        return out;
    }

    /**
     * 写入一条事实（addFact/addGossipFact 共用）。
     * <p>
     * 去重规则：完整句子相等或互为子串才算同一条 —— 旧版拿前 10 个字符做 contains，
     * 玩家名稍长（"旅者 Howardsuper..."）就把"打过我"和"送过我东西"当成同一条误吞掉。
     * 命中已有事实视为"又被提及" → 只刷新时间戳（7 天遗忘从最近一次提及起算），不重复存。
     */
    private boolean addFactInternal(String fact, boolean gossipExempt, String ownerUuid) {
        if (fact == null || fact.isBlank()) return false;
        long now = System.currentTimeMillis();
        for (int i = 0; i < facts.size(); i++) {
            Fact existing = facts.get(i);
            if (isSameFact(existing.text(), fact)) {
                // 同一条事实再次出现 → 刷新提及时间（豁免/归属标记保持原值，不能借机升级豁免或改归属）
                facts.set(i, new Fact(existing.text(), now, existing.gossipExempt(), existing.ownerUuid()));
                return false;
            }
        }
        facts.add(new Fact(fact, now, gossipExempt, ownerUuid));
        evictOverflow();
        return true;
    }

    /** 完整句子级去重：完全相等，或一方完整包含另一方（长句是短句的展开版）才算同一条 */
    private static boolean isSameFact(String a, String b) {
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /**
     * 超过硬上限 MAX_FACTS 时的淘汰顺序（优先级从高到低）：
     * 1) 7 天未提及的非豁免事实（遗忘曲线）
     * 2) 最旧的非豁免事实（列表顺序≈时间顺序）
     * 3) 全是大嘴巴豁免事实时才丢最旧的 —— 豁免优先，但硬上限优先于豁免，防 facts 无限膨胀
     */
    private void evictOverflow() {
        long now = System.currentTimeMillis();
        while (facts.size() > MAX_FACTS) {
            int idx = -1;
            for (int i = 0; i < facts.size(); i++) {
                Fact f = facts.get(i);
                if (!f.gossipExempt() && now - f.ts() > FACT_EXPIRE_MS) { idx = i; break; }
            }
            if (idx < 0) {
                for (int i = 0; i < facts.size(); i++) {
                    if (!facts.get(i).gossipExempt()) { idx = i; break; }
                }
            }
            if (idx < 0) idx = 0;
            facts.remove(idx);
        }
    }

    /**
     * 7 天遗忘（硬约束）—— 丢掉 7 天未被提及的事实；大嘴巴传播来的（gossipExempt）不参与。
     * 只在压缩触发时执行（与 addChatEntry 的压缩同一时机），不单独起定时任务。
     */
    private void pruneExpiredFacts() {
        long now = System.currentTimeMillis();
        facts.removeIf(f -> !f.gossipExempt() && now - f.ts() > FACT_EXPIRE_MS);
    }

    /**
     * 追加一条对话并**按需压缩历史**（SPEC 3.4 核心机制）。
     *
     * <p>压缩策略（规则压缩，不调 LLM）：
     * <pre>
     * 触发（任一）：历史 > MAX_RECENT 轮（10 轮 = 20 条）或估算 token 超配置上限
     *   把最老的一轮 (user + assistant 成对) 拼进 summary，再从 chatHistory 移除
     *   同时执行 facts 的 7 天遗忘
     *
     * 完整 LLM 压缩（SPEC 说的"旧原文 → 3-5 句话"）留作 M3+，
     * 因为需要额外一次 LLM 调用，会增加 TTFT。
     * </pre>
     * 必须按轮删：旧版按条删（remove(0) 单条），删完可能留下 assistant 开头的历史，
     * 与 PromptBuilder 拼出的 user 消息衔接错位。
     */
    public synchronized void addChatEntry(String role, String content) {
        chatHistory.add(new ChatEntry(role, content));
        // 归一化：历史首条必须是 user（旧记忆文件/异常路径可能残留 assistant 开头）。
        // 每次追加都顺手纠正，保证进 prompt 的历史严格以 user 起头、user↔assistant 交替
        while (!chatHistory.isEmpty() && !"user".equals(chatHistory.get(0).role())) {
            compressIntoSummary(chatHistory.remove(0));
        }
        compactIfNeeded();
    }

    /** 压缩触发条件：超 10 轮（20 条），或估算 token 超过配置的 max-chat-history-tokens */
    private boolean needCompaction() {
        return chatHistory.size() > MAX_RECENT * 2 || estimateHistoryTokens() > maxChatHistoryTokens;
    }

    /**
     * chatHistory 的 token 估算 —— **粗估**：总字符数 ÷ 4。
     * 英文约 4 字符/token、中文约 1~2 字符/token，混合文本折中按 4 算；
     * 只用来做"超没超阈值"的触发判断，不追求精确计数（避免引入分词器）。
     */
    private int estimateHistoryTokens() {
        int chars = 0;
        for (ChatEntry e : chatHistory) chars += e.content().length();
        return chars / 4;
    }

    /** 触发压缩时执行：先做 7 天遗忘，再按轮删到阈值以内（至少保留最近一轮原文） */
    private void compactIfNeeded() {
        if (!needCompaction()) return;
        pruneExpiredFacts();
        while (needCompaction() && chatHistory.size() > 2) {
            removeOldestRound();
        }
    }

    /**
     * 删掉最老的一轮：user + 同轮 assistant 成对删除，保证留下的历史仍以 user 开头。
     * 队首若没有配对的 assistant（历史被截断过），只删 user 这一条，仍保持 user 开头。
     */
    private void removeOldestRound() {
        if (chatHistory.isEmpty()) return;
        compressIntoSummary(chatHistory.remove(0));
        if (!chatHistory.isEmpty() && "assistant".equals(chatHistory.get(0).role())) {
            compressIntoSummary(chatHistory.remove(0));
        }
    }

    /** 规则压缩——把一条旧对话拼进 summary（不调 LLM，先做规则压缩） */
    private void compressIntoSummary(ChatEntry entry) {
        if (summary.length() > 0) summary.append(" ");
        summary.append(entry.role().equals("user") ? "旅者说: " : "我答: ");
        // 截断到 80 字防止 summary 过长
        String trimmed = entry.content();
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 77) + "...";
        summary.append(trimmed);
    }

    /** summary 硬上限（字符）—— 每次下线全量整理都往里拼，不截断会无限膨胀吃光记忆预算 */
    private static final int MAX_SUMMARY_CHARS = 1500;

    /**
     * 玩家下线时的**全量整理**（硬约束）：把全部 recent（chatHistory）并入 summary，
     * 并追加一段"昨日小结"（谁来过、聊了什么的浓缩版）—— 玩家下次上线时，
     * NPC 开场白的 prompt（PromptBuilder 的 memory block）会带上这段 summary，自然引用它。
     *
     * @param playerName  下线的玩家名（写进小结，让 NPC 开场白点得出名字）
     * @param dateLabel   小结日期标签（如 2026-10-19，PlayerQuitListener 传入）
     * @return 是否真的整理了内容（false = 无需标脏落盘）
     */
    public synchronized boolean consolidateForPlayerQuit(String playerName, String dateLabel) {
        // LLM 响应还在飞 → 历史不能动（清空后 assistant 落单会破坏 user↔assistant 交替），
        // 等下次下线/压缩再整理，宁可少整理一次也不制造脏历史
        if (llmInFlight.get()) return false;
        if (chatHistory.isEmpty()) return false;

        // 先摘出最近几条拼成小结（整理会把原文并进 summary，得在清空前取样）
        StringBuilder digest = new StringBuilder();
        int take = Math.min(3, chatHistory.size());
        for (int i = chatHistory.size() - take; i < chatHistory.size(); i++) {
            ChatEntry e = chatHistory.get(i);
            if (digest.length() > 0) digest.append("；");
            String t = e.content();
            if (t.length() > 40) t = t.substring(0, 38) + "...";
            digest.append("user".equals(e.role()) ? "旅者说" : "我答").append("「").append(t).append("」");
        }

        // 全量：所有 recent 一条不留并入 summary
        for (ChatEntry e : chatHistory) compressIntoSummary(e);
        chatHistory.clear();

        // 昨日小结 —— 单独标记前缀，开场白引用时语境清晰
        if (summary.length() > 0) summary.append(" ");
        summary.append("【昨日小结(").append(dateLabel).append(")】旅者")
                .append(playerName).append("来过，").append(digest).append("。");

        // summary 封顶：从头部截（列表追加顺序是旧→新，保最新的）
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = new StringBuilder("…" + summary.substring(summary.length() - MAX_SUMMARY_CHARS));
        }

        // 整理也是一次压缩触发 → 顺带执行 facts 7 天遗忘（大嘴巴豁免）
        pruneExpiredFacts();
        return true;
    }

    // ===== Interacting Player =====

    public UUID interactingPlayer() {
        return interactingPlayer;
    }

    public void startConversation(UUID playerUuid) {
        // 被顶替的上一位对话者也算“对话结束”——记一笔结束时间，
        // 否则他下次回来会被误判成“没有上次记录”。
        if (this.interactingPlayer != null && !this.interactingPlayer.equals(playerUuid)) {
            recordConversationEnd(this.interactingPlayer);
        }
        this.interactingPlayer = playerUuid;
        this.idleElapsedTicks = 0;
        // 对话期间冻结原版 AI：村民不再自己乱逛，保持"站定和玩家聊"的观感。
        // setAI(false) 只停寻路/行为，交易面板和 setVelocity 肢体动作不受影响。
        if (entity != null && entity.isValid()) {
            entity.setAI(false);
        }
        // 不清空 chatHistory —— recent + summary + facts 保留，NPC 有"记忆"
    }

    public void endConversation() {
        // 记住这次对话什么时候结束的（按玩家分开记）——下次开始对话时，
        // 开场说明靠它区分“刚聊完又回来”与“好久没见”，见 ai/ConversationOpening。
        if (this.interactingPlayer != null) {
            recordConversationEnd(this.interactingPlayer);
            // 没谈完的换物提议随对话一起作废（v0.4.4）：提议只活在一场对话里
            // （另有 3 分钟窗口兜底），防聊完走人后被陈年提议误成交。
            synchronized (this) {
                pendingBarters.remove(this.interactingPlayer);
            }
        }
        this.interactingPlayer = null;
        // 恢复原版 AI，让村民重新正常生活（闲逛/回家/作息）
        if (entity != null && entity.isValid()) {
            entity.setAI(true);
        }
    }

    /** planner 每轮调用一次，传入本轮间隔（游戏 tick），累计真实经过时间 */
    public void tickConversationTimeout(int intervalTicks) {
        if (interactingPlayer == null) return;
        idleElapsedTicks += intervalTicks;
        if (idleElapsedTicks > CONVERSATION_TIMEOUT_TICKS) {
            endConversation();
        }
    }

    public void onPlayerMessage() {
        this.idleElapsedTicks = 0;
    }

    // ===== LLM 并发控制 =====

    public boolean tryAcquireLLM() {
        return llmInFlight.compareAndSet(false, true);
    }

    public void releaseLLM() {
        llmInFlight.set(false);
    }

    public boolean llmBusy() {
        return llmInFlight.get();
    }

    /** LLM 忙时把玩家消息塞进积压队列（等当前响应处理完再入历史 + 续调） */
    public synchronized void enqueuePendingMessage(String message) {
        pendingMessages.add(message);
    }

    /** 取走全部积压消息（取走即清空） */
    public synchronized List<String> drainPendingMessages() {
        if (pendingMessages.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(pendingMessages);
        pendingMessages.clear();
        return out;
    }

    // ===== 记忆恢复（MemoryStore 反序列化用） =====

    public synchronized void setSummary(String s) {
        summary = new StringBuilder(s == null ? "" : s);
    }

    public synchronized void restoreChatHistory(List<ChatEntry> entries) {
        chatHistory.clear();
        chatHistory.addAll(entries);
        // 旧文件可能存着 assistant 开头的脏历史（按条压缩时代的遗留）——
        // 恢复时就归一化，保证下次进 prompt 的历史以 user 开头
        while (!chatHistory.isEmpty() && !"user".equals(chatHistory.get(0).role())) {
            compressIntoSummary(chatHistory.remove(0));
        }
    }

    /**
     * 恢复 facts（MemoryStore 反序列化用）。传入的是带时间戳/豁免标记的完整 Fact；
     * 恢复后再按 MAX_FACTS 收一遍超限（旧版上限 30，可能存了 20+ 条）。
     */
    public synchronized void restoreFacts(List<Fact> restored) {
        facts.clear();
        facts.addAll(restored);
        evictOverflow();
    }

    // ===== 遇见过哪些玩家（首次见面判定，替代旧的字串匹配） =====

    /**
     * 遇到过/互动过的玩家 —— UUID → 名字。持久化。
     * <p>
     * 旧版判断"是否认识某玩家"靠 summary/facts 里子串包含玩家名，玩家名恰好是常见词
     * （"Steve"、"老李"）或出现在别的话里就会误判。改用这张显式表：互动过就记一笔，
     * prompt 的 knowsPlayer 按 UUID 判定，精确且跨重启保留。
     */
    private final Map<java.util.UUID, String> metPlayers = new java.util.HashMap<>();

    /** 是否与某玩家互动过（首次见面判定用） */
    public synchronized boolean hasMet(java.util.UUID playerUuid) {
        return playerUuid != null && metPlayers.containsKey(playerUuid);
    }

    /** 记录一次"见过这位玩家"（上线/右键/索要/对话时调用） */
    public synchronized void recordMeeting(java.util.UUID playerUuid, String name) {
        if (playerUuid == null || name == null || name.isBlank()) return;
        metPlayers.put(playerUuid, name);
    }

    /** metPlayers 快照（MemoryStore 持久化用）：UUID字符串 → 名字 */
    public synchronized Map<String, String> metPlayersSnapshot() {
        Map<String, String> out = new java.util.HashMap<>();
        metPlayers.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }

    /** MemoryStore 从 JSON 恢复遇见的玩家表（覆盖式） */
    public synchronized void restoreMetPlayers(Map<String, String> restored) {
        metPlayers.clear();
        if (restored == null) return;
        for (var e : restored.entrySet()) {
            try {
                metPlayers.put(java.util.UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID（旧档损坏）跳过
            }
        }
    }

    // ===== 上次对话结束时间（开场说明定档用，持久化） =====

    /** 每位玩家上次与本 NPC 对话结束的时刻（毫秒）。开场说明按它定档，见 ai/ConversationOpening */
    private final Map<java.util.UUID, Long> lastConversationEndByPlayer = new java.util.HashMap<>();

    /** 记一笔“与该玩家的对话在现在结束了” */
    public synchronized void recordConversationEnd(java.util.UUID playerUuid) {
        if (playerUuid == null) return;
        lastConversationEndByPlayer.put(playerUuid, System.currentTimeMillis());
    }

    /** 上次与该玩家对话结束的时刻（毫秒）；从没记录过返回 0 */
    public synchronized long lastConversationEndMs(java.util.UUID playerUuid) {
        if (playerUuid == null) return 0L;
        return lastConversationEndByPlayer.getOrDefault(playerUuid, 0L);
    }

    /** lastConversationEndByPlayer 快照（MemoryStore 持久化用）：UUID字符串 → 毫秒时间戳 */
    public synchronized Map<String, Long> lastConversationEndsSnapshot() {
        Map<String, Long> out = new java.util.HashMap<>();
        lastConversationEndByPlayer.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }

    /** MemoryStore 从 JSON 恢复上次对话结束时间表（覆盖式；非法键跳过） */
    public synchronized void restoreLastConversationEnds(Map<String, Long> restored) {
        lastConversationEndByPlayer.clear();
        if (restored == null) return;
        for (var e : restored.entrySet()) {
            try {
                if (e.getValue() != null && e.getValue() > 0) {
                    lastConversationEndByPlayer.put(java.util.UUID.fromString(e.getKey()), e.getValue());
                }
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID（旧档损坏）跳过
            }
        }
    }

    // ===== 稀有物品赠送日账（防刷，持久化） =====

    /** 每位玩家上次从本 NPC 拿到稀有物品（价值≥100）的日期（yyyy-MM-dd，本地日期） */
    private final Map<java.util.UUID, String> lastRareGiftDayByPlayer = new java.util.HashMap<>();

    /** 该玩家今天是否已经从本 NPC 拿到过稀有物品 */
    public synchronized boolean rareGiftGivenOn(java.util.UUID playerUuid, String dayKey) {
        if (playerUuid == null || dayKey == null) return false;
        return dayKey.equals(lastRareGiftDayByPlayer.get(playerUuid));
    }

    /** 记一笔：该玩家今天从本 NPC 拿到了稀有物品 */
    public synchronized void markRareGift(java.util.UUID playerUuid, String dayKey) {
        if (playerUuid == null || dayKey == null) return;
        lastRareGiftDayByPlayer.put(playerUuid, dayKey);
    }

    /** lastRareGiftDayByPlayer 快照（MemoryStore 持久化用）：UUID字符串 → 日期 */
    public synchronized Map<String, String> rareGiftDaysSnapshot() {
        Map<String, String> out = new java.util.HashMap<>();
        lastRareGiftDayByPlayer.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }

    /** MemoryStore 从 JSON 恢复稀有赠送日账（覆盖式；非法键跳过） */
    public synchronized void restoreRareGiftDays(Map<String, String> restored) {
        lastRareGiftDayByPlayer.clear();
        if (restored == null) return;
        for (var e : restored.entrySet()) {
            try {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    lastRareGiftDayByPlayer.put(java.util.UUID.fromString(e.getKey()), e.getValue());
                }
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID（旧档损坏）跳过
            }
        }
    }

    /** 当前库存里所有可食物品的总件数（送礼口粮保留线用；库存键是 Material 名） */
    public synchronized int totalFoodStock() {
        int total = 0;
        for (var e : inventorySnapshot().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(e.getKey());
            if (m != null && m.isEdible()) total += e.getValue();
        }
        return total;
    }

    // ===== 聊天涨好感的节流（运行时，不持久化） =====

    /** 同一玩家跟同一 NPC 聊天涨好感的冷却：30 秒内只记一次，防刷屏刷好感 */
    private static final long CHAT_FAVOR_COOLDOWN_MS = 30_000L;

    /** 上次"聊天涨好感"的时刻（key=玩家 UUID）。重启即清空 —— 只是节流，丢了不影响正确性 */
    private final Map<java.util.UUID, Long> lastChatFavorAtMs = new java.util.HashMap<>();

    /**
     * "聊天涨好感"是否放行 —— 同一玩家 30 秒内只放行一次（放行时会刷新计时）。
     * <p>
     * 聊天的意思是"打招呼/搭话本身就在积累交情"，但必须节流，
     * 否则玩家连发十句话就把好感刷满，门槛又变成摆设。
     *
     * @return true = 这次可以给好感（调用方随后 addFavor）
     */
    public synchronized boolean tryConsumeChatFavor(java.util.UUID playerUuid) {
        if (playerUuid == null) return false;
        long now = System.currentTimeMillis();
        Long last = lastChatFavorAtMs.get(playerUuid);
        if (last != null && now - last < CHAT_FAVOR_COOLDOWN_MS) return false;
        lastChatFavorAtMs.put(playerUuid, now);
        return true;
    }

    /** 索要交付的防重窗口（毫秒）—— 代码层出货后，模型同款的 give_item 调用应被静默跳过 */
    private static final long AUTO_GIVE_GUARD_MS = 8_000L;

    /** 代码层刚替玩家出货的物品（key=Material，value=时刻毫秒）。重启即清空，只做短期防重 */
    private final Map<org.bukkit.Material, Long> autoGivenAtMs = new java.util.HashMap<>();

    /**
     * 记下"代码层已经替这只 NPC 把东西交给玩家了"。
     * <p>
     * 为什么需要：索要物品的交付已改由代码层当场完成（实测模型常常只在台词里写
     * {@code [动作:give_item 面包]} 当纯文本，那一行会被 sanitizeDialogue 剥掉、工具根本没被调用），
     * 但模型这一轮仍可能同时真的调用 {@code give_item} —— 不设防就会对同一件东西重复出货。
     */
    public synchronized void markAutoGiven(org.bukkit.Material mat) {
        if (mat != null) autoGivenAtMs.put(mat, System.currentTimeMillis());
    }

    /** 8 秒内是否已由代码层交过这件东西（是 → give_item 应当静默跳过，防重复出货） */
    public synchronized boolean wasAutoGivenRecently(org.bukkit.Material mat) {
        if (mat == null) return false;
        Long t = autoGivenAtMs.get(mat);
        return t != null && System.currentTimeMillis() - t < AUTO_GIVE_GUARD_MS;
    }

    // ===== 自主移动的两本账（仅内存，不落盘：都是短期经验） =====

    /** 上次自主移动失败的目标点 —— 短期内选点要避开这片，免得对着同一面墙反复撞 */
    private volatile double failedMoveX, failedMoveZ;
    private volatile long failedMoveAtMs;

    public synchronized void recordFailedMoveTarget(org.bukkit.Location loc) {
        if (loc == null) return;
        failedMoveX = loc.getX();
        failedMoveZ = loc.getZ();
        failedMoveAtMs = System.currentTimeMillis();
    }

    /** 该点是否落在"刚失败过"的区域内（窗口内 + 半径内） */
    public synchronized boolean recentlyFailedNear(org.bukkit.Location loc, double radius, long windowMs) {
        if (loc == null || failedMoveAtMs == 0) return false;
        if (System.currentTimeMillis() - failedMoveAtMs > windowMs) return false;
        double dx = loc.getX() - failedMoveX, dz = loc.getZ() - failedMoveZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * 待结算的自主需求：需求驱动选了"建任务"的动作（如 move_to）时，先把需求名挂这里，
     * 等任务真的完成才降需求（由任务执行方调用 consume 取走结算）；任务失败则取走丢弃——
     * 活没干完不记功。旧逻辑是任务一被接受就降 35 点，不可达目标也会反复被满足感喂饱。
     */
    private volatile String pendingNeedSatisfy;

    public void setPendingNeedSatisfy(String need) {
        pendingNeedSatisfy = need;
    }

    /** 取走并清空待结算需求（没有返回 null） */
    public synchronized String consumePendingNeedSatisfy() {
        String n = pendingNeedSatisfy;
        pendingNeedSatisfy = null;
        return n;
    }

    // ===== 背包换物提议（v0.4.1：选中他的货 + 点自己的货登记，模型裁决后执行）=====

    /**
     * 待裁决的换物提议 —— 玩家在背包界面选中 NPC 的 1 件东西、再点自己背包里
     * 的一格物品登记。价值与有利程度由插件算好写进给模型的简报（ai/BarterBrief），
     * 模型同意（调 accept_barter）后由 WorldInteractionService 复核双方物品还在、
     * 一次性交换；模型不表态就什么都不发生。
     */
    public record PendingBarter(org.bukkit.Material npcMat, org.bukkit.Material offerMat,
                                int offerCount, long atMs) {}

    private static final long BARTER_WINDOW_MS = 180_000L;

    private final Map<java.util.UUID, PendingBarter> pendingBarters = new java.util.HashMap<>();

    public synchronized void setPendingBarter(java.util.UUID playerUuid, org.bukkit.Material npcMat,
                                              org.bukkit.Material offerMat, int offerCount) {
        if (playerUuid == null || npcMat == null || offerMat == null || offerCount <= 0) return;
        pendingBarters.put(playerUuid, new PendingBarter(npcMat, offerMat, offerCount,
                System.currentTimeMillis()));
    }

    public synchronized PendingBarter peekPendingBarter(java.util.UUID playerUuid) {
        if (playerUuid == null) return null;
        PendingBarter b = pendingBarters.get(playerUuid);
        if (b == null) return null;
        if (System.currentTimeMillis() - b.atMs() > BARTER_WINDOW_MS) {
            pendingBarters.remove(playerUuid);
            return null;
        }
        return b;
    }

    public synchronized PendingBarter consumePendingBarter(java.util.UUID playerUuid) {
        PendingBarter b = peekPendingBarter(playerUuid);
        if (b != null) pendingBarters.remove(playerUuid);
        return b;
    }

    public synchronized void clearPendingBarter(java.util.UUID playerUuid) {
        if (playerUuid != null) pendingBarters.remove(playerUuid);
    }

    // ===== 玩家送礼（必须玩家亲口确认，代码层绝不擅自没收）=====

    /**
     * 待确认的玩家送礼 —— <b>只登记，不扣货</b>。
     * <p>
     * 旧版是"命中送礼句式就直接从玩家背包扣走"，实测闯过祸：玩家说一句
     * "把铁剑给我，下界合金剑给你"（换物提议），代码层只执行了玩家那一半，
     * 把合金剑没收了，NPC 那一半却因好感不够被拒 —— 等于单方面抢东西。
     * 现在改成：先登记这一笔，让 NPC 回一句"真要给俺？"，玩家下一句明确应一声才真的收下。
     */
    public record PendingGift(org.bukkit.Material mat, int count, long expiryMs) {}

    /** 确认窗口 —— 超过这个时间没等到玩家的确认，视为他改主意了，直接作废 */
    private static final long GIFT_CONFIRM_WINDOW_MS = 45_000L;

    private final Map<java.util.UUID, PendingGift> pendingGifts = new java.util.HashMap<>();

    /** 登记一笔"玩家要送东西"（此时物品仍在玩家身上，什么都没发生） */
    public synchronized void setPendingGift(java.util.UUID playerUuid, org.bukkit.Material mat, int count) {
        if (playerUuid == null || mat == null || count <= 0) return;
        pendingGifts.put(playerUuid, new PendingGift(mat, count,
                System.currentTimeMillis() + GIFT_CONFIRM_WINDOW_MS));
    }

    /** 取该玩家待确认的送礼；过期即作废并返回 null */
    public synchronized PendingGift pendingGift(java.util.UUID playerUuid) {
        if (playerUuid == null) return null;
        PendingGift g = pendingGifts.get(playerUuid);
        if (g == null) return null;
        if (System.currentTimeMillis() > g.expiryMs()) {
            pendingGifts.remove(playerUuid);
            return null;
        }
        return g;
    }

    /** 作废该玩家待确认的送礼（玩家没答应 / 已经处理完） */
    public synchronized void clearPendingGift(java.util.UUID playerUuid) {
        if (playerUuid != null) pendingGifts.remove(playerUuid);
    }

    public synchronized void restoreDynamicPrices(Map<String, Integer> restored) {
        dynamicPrices.clear();
        dynamicPrices.putAll(restored);
    }

    public synchronized void restoreOfferedItems(Map<String, OfferedItem> restored) {
        offeredItems.clear();
        offeredItems.putAll(restored);
    }

    /** chat history 单条 —— role + content */
    public record ChatEntry(String role, String content) {}
}
