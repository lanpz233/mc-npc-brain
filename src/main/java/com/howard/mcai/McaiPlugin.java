package com.howard.mcai;

import com.howard.mcai.ai.BehaviorDecisionParser;
import com.howard.mcai.ai.BehaviorModelClient;
import com.howard.mcai.ai.BehaviorRegistry;
import com.howard.mcai.ai.LLMClient;
import com.howard.mcai.ai.LLMResponse;
import com.howard.mcai.ai.LlmToolAdapter;
import com.howard.mcai.ai.NameLibrary;
import com.howard.mcai.ai.PersonalityTagLibrary;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.RecipeLookup;
import com.howard.mcai.ai.ToolCall;
import com.howard.mcai.ai.TradeRegistry;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.config.PluginConfig;
import com.howard.mcai.listener.PlayerAttackListener;
import com.howard.mcai.listener.PlayerChatListener;
import com.howard.mcai.listener.PlayerInteractListener;
import com.howard.mcai.listener.PlayerQuitListener;
import com.howard.mcai.listener.VillagerAutoRegisterListener;
import com.howard.mcai.listener.VillagerDeathListener;
import com.howard.mcai.memory.MemoryStore;
import com.howard.mcai.mob.MobState;
import com.howard.mcai.mob.VillagerProfession;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.planner.CentralPlanner;
import com.howard.mcai.planner.GroupChatCoordinator;
import com.howard.mcai.planner.SocialRelationshipGenerator;
import com.howard.mcai.planner.TickLayer;
import com.howard.mcai.tool.BukkitGameStateProvider;
import com.howard.mcai.tool.GameStateProvider;
import com.howard.mcai.tool.NpcTaskManager;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolDispatcher;
import com.howard.mcai.tool.ToolPolicyService;
import com.howard.mcai.tool.ToolRegistry;
import com.howard.mcai.tool.ToolRequest;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import com.howard.mcai.tool.tools.NpcInventoryGetTool;
import com.howard.mcai.tool.tools.WorldEnvironmentTool;
import com.howard.mcai.tool.tools.WorldNearbyBlocksTool;
import com.howard.mcai.tool.tools.NpcMoveToTool;
import com.howard.mcai.tool.tools.NpcStatusGetTool;
import com.howard.mcai.tool.tools.NpcTaskCancelTool;
import com.howard.mcai.tool.tools.NpcTaskGetTool;
import com.howard.mcai.tool.tools.WorldObserveEntitiesTool;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MC-AI Paper 插件主入口 —— M1 milestone 完整版本。
 * <p>
 * 所有子系统在这里注入、注册、启动：
 * - BubbleManager（气泡）
 * - MemoryStore（记忆持久化）
 * - Villager 运行时状态 + spawn
 * - CentralPlanner（tick loop）
 * - 事件 listener（右键/聊天/攻击）
 *
 * 关闭时按顺序清理：cancel tick → flush 记忆 → clear 气泡。
 */
public final class McaiPlugin extends JavaPlugin {

    /** 全局配置 */
    private PluginConfig config;

    /** LLM 客户端（HTTP 调本地 llama-server） */
    private LLMClient llmClient;

    /** 气泡管理器（TextDisplay riding villager） */
    private BubbleManager bubbleManager;

    /** 记忆存储（debounce flush + JSON 持久化） */
    private MemoryStore memoryStore;

    /** 中央计划器 tick 任务（存为 BukkitTask 以便 onDisable 时 cancel） */
    private BukkitTask plannerTask;

    /** 中央计划器实例（存引用让 Listener 能调 triggerLLM） */
    private CentralPlanner planner;

    /** 配方查表（recipes.json） */
    private RecipeLookup recipeLookup;

    /**
     * 所有村民的 VillagerState，key = entity UUID。
     * <p>
     * 必须是 ConcurrentHashMap：AsyncPlayerChatEvent 在**异步线程**遍历这个 map
     * 找"正在跟该玩家对话的村民"，主线程同时可能 put（chunk 加载注册新村民）——
     * 普通 HashMap 会 CME/脏读。CHM 迭代是弱一致的，异步只读遍历安全。
     */
    private final Map<UUID, VillagerState> villagers = new ConcurrentHashMap<>();

    /** 最近一次群聊 LLM 回复的时刻（毫秒）—— ambient 闲聊用它做"群里刚有人说话就别插嘴"守卫 */
    private volatile long lastGroupReplyAtMs = 0L;

    /** 群聊回复后 ambient 自动闲聊让位的时间窗（15 秒）—— 防止闲聊气泡覆盖群聊回复气泡 */
    private static final long GROUP_REPLY_GUARD_MS = 15_000L;

    // ===== SPEC 2.14：需求系统 + 权限三档 + 世界交互 =====

    /** 神使审计日志（plugins/MC-AI/audit.log，append-only） */
    private com.howard.mcai.security.AuditLog auditLog;

    /** 权限判定器（平民/亲信/神使三档 + 领地/白名单/稀有度判定） */
    private com.howard.mcai.security.PermissionManager permissionManager;

    /** 世界交互执行器（give/take/break/place/teleport/equip + 传送确认流） */
    private com.howard.mcai.security.WorldInteractionService worldInteraction;

    /** 工具系统：所有 AI 能力统一从 Dispatcher 进入 */
    private GameStateProvider gameStateProvider;
    private ToolRegistry toolRegistry;
    private ToolPolicyService toolPolicyService;
    private NpcTaskManager npcTaskManager;
    private ToolDispatcher toolDispatcher;
    /** 模型输出 → 统一工具请求（wire 名归一） */
    private LlmToolAdapter llmToolAdapter;
    /** 行为模型（MiniCPM）：只做动作选择，不负责说话 */
    private BehaviorModelClient behaviorModelClient;

    /** 记录一次群聊回复（GroupChatCoordinator 在回复气泡上屏时调用） */
    public void markGroupReplyAt() {
        lastGroupReplyAtMs = System.currentTimeMillis();
    }

    /** 最近 GROUP_REPLY_GUARD_MS 内是否发生过群聊回复（PresentationLayer 在开闲聊前检查） */
    public boolean recentlyGroupReplied() {
        return System.currentTimeMillis() - lastGroupReplyAtMs < GROUP_REPLY_GUARD_MS;
    }

    @Override
    public void onEnable() {
        // 1. 加载配置 + LLM 客户端
        saveDefaultConfig();
        reloadConfig();
        config = PluginConfig.load(getConfig());
        llmClient = new LLMClient(config);
        // chatHistory 压缩的 token 阈值接线（VillagerState 无 plugin 引用，走静态镜像）
        VillagerState.setMaxChatHistoryTokens(config.maxChatHistoryTokens());

        // 1.5 加载配方表（拼进 system prompt，让村民对话有真实参考）
        recipeLookup = new RecipeLookup();
        recipeLookup.load(getDataFolder().toPath());
        PromptBuilder.setRecipeLookup(recipeLookup);
        getLogger().info("配方表: " + (recipeLookup.isLoaded() ? recipeLookup.size() + " 条已加载" : "未找到 recipes.json"));

        // 1.6 加载人格标签库（personality_tags.json → 每个 NPC 抽 2-4 个独特性格标签）
        PersonalityTagLibrary.load(getDataFolder().toPath());
        getLogger().info("人格标签: " + (PersonalityTagLibrary.isLoaded()
                ? PersonalityTagLibrary.size() + " 条已加载" : "未找到 personality_tags.json"));

        // 1.7 加载名字库（surnames.json + given_names.json → 按性别的用字生成名字）
        NameLibrary.load(getDataFolder().toPath());
        getLogger().info("名字库: " + (NameLibrary.isLoaded()
                ? NameLibrary.surnameCount() + " 姓 / " + NameLibrary.givenCharCount() + " 用字，主姓=" + NameLibrary.mainSurname()
                : "未找到 surnames.json / given_names.json（退回内置兜底用字）"));

        // 2. BubbleManager（自己注册 listener）
        bubbleManager = new BubbleManager(this);

        // 3. Spawn/加载 铁匠老王 —— Paper 实体持久化，重启后自动加载
        spawnOrLoadBlacksmith();

        // 4. MemoryStore —— 加载已有记忆文件或创建空快照
        memoryStore = new MemoryStore(this, villagers);
        memoryStore.loadAll();

        // 5. 先造中央计划器依赖的服务（SPEC 2.14）：审计 → 权限 → 世界交互
        //    CentralPlanner 构造时会取 worldInteraction，必须先就绪
        auditLog = new com.howard.mcai.security.AuditLog(getDataFolder().toPath());
        permissionManager = new com.howard.mcai.security.PermissionManager(
                config.confidantFavor(), config.territoryRadius());
        worldInteraction = new com.howard.mcai.security.WorldInteractionService(this, permissionManager, auditLog);

        // 5.1 工具系统 —— 统一注册 / 参数校验 / 权限策略 / 任务生命周期
        //     所有 Bukkit 状态读取与行动执行仍然走主线程，Dispatcher 只负责编排
        gameStateProvider = new BukkitGameStateProvider(this);
        // 任务生命周期全量打控制台：创建 / 完成 / 失败 / 取消 / 超时都能追溯
        npcTaskManager = new NpcTaskManager(message -> getLogger().info(message));
        toolRegistry = new ToolRegistry();
        toolPolicyService = new ToolPolicyService(
                gameStateProvider::npcExists,
                id -> {
                    VillagerState vs = villagers.get(id);
                    return vs != null && vs.interactingPlayer() != null;
                },
                (npcId, playerUuid) -> {
                    VillagerState vs = villagers.get(npcId);
                    return vs == null ? null : permissionManager.rankOf(vs, playerUuid);
                });
        toolDispatcher = new ToolDispatcher(
                toolRegistry,
                toolPolicyService,
                npcTaskManager,
                task -> Bukkit.getScheduler().runTask(this, task),
                message -> getLogger().warning("[tool] " + message),
                5 * 60_000L,
                // 主线程闸门：Dispatcher 自己不依赖 Bukkit，插件把权威判定注入进去
                Bukkit::isPrimaryThread,
                // 每次工具调用（命令/行为模型/自主行动）都写一行到控制台
                message -> getLogger().info(message));
        registerBuiltinTools();
        // 模型输出适配层：wire 名（npc_move_to）与规范名（npc.move_to）统一归一到 ToolRequest
        llmToolAdapter = new LlmToolAdapter(toolRegistry);
        // 把新工具名并入 LLMClient 的 fallback 正则，避免模型按新名字输出时丢调用
        LLMClient.registerFallbackToolNames(
                toolRegistry.all().stream().map(NpcTool::wireName).toList());
        // 行为模型：只选动作。配置留空时自动回退对话模型（behaviorSettings() 负责）
        behaviorModelClient = new BehaviorModelClient(config.behaviorSettings(), toolRegistry);

        // 5. 先造 CentralPlanner（Listener 要调它的 triggerLLM）
        //    LLMClient 不再传引用 —— planner 运行时动态取 plugin.getLlmClient()，
        //    这样 /ai reload 换后端（llama-server ↔ Ollama）立即生效，无需重启服务器
        planner = new CentralPlanner(this, villagers, bubbleManager);

        // 5.4 需求层（SPEC 2.14）—— 主动性引擎：需求增长 + 规则满足 + LLM 自主行动
        planner.registerLayer(new com.howard.mcai.planner.NeedsLayer(this, villagers, bubbleManager));

        // 5.5 玩家群体聊天协调器（附近 NPC 附和 + 点名 LLM 回复）
        GroupChatCoordinator groupChat = new GroupChatCoordinator(this, villagers, bubbleManager);

        // 6. 注册事件 listener（都持有 planner 引用；mobs 注册表也传给攻击/交互监听）
        Map<UUID, MobState> mobStates = planner.mobStates();
        Bukkit.getPluginManager().registerEvents(new PlayerInteractListener(this, villagers, mobStates, bubbleManager, planner), this);
        Bukkit.getPluginManager().registerEvents(new PlayerChatListener(this, villagers, planner, groupChat), this);
        Bukkit.getPluginManager().registerEvents(new PlayerAttackListener(villagers, mobStates, memoryStore, bubbleManager), this);
        // AI 村民死亡 → 销户/掉落库存/亲友反应/记仇（"死人是大事"，和现实一样）
        Bukkit.getPluginManager().registerEvents(new VillagerDeathListener(this, villagers, memoryStore, bubbleManager), this);
        // 原版村民自动注册 —— 监听 ChunkLoadEvent，把村庄里自然 spawn 的农夫/牧师等也纳入 AI 管理
        Bukkit.getPluginManager().registerEvents(new VillagerAutoRegisterListener(this, villagers), this);
        // 玩家下线 → 全量整理记忆（recent 并入 summary + 昨日小结，供下次上线开场白引用）
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(this, villagers), this);
        // NPC 背包 GUI 拦截（按 F 打开、点击索要；所有点击/拖拽 cancel，索要转成一次对话）
        Bukkit.getPluginManager().registerEvents(new com.howard.mcai.listener.VillagerInventoryListener(), this);
        // 交易成交 → 好感 +1（玩家照顾 NPC 生意也是互动，让"关系够不够好"能靠日常攒起来）
        Bukkit.getPluginManager().registerEvents(new com.howard.mcai.listener.VillagerTradeListener(this), this);

        // 7. 启动中央计划器心跳（每 20 tick = 1 秒），时间分发给各 TickLayer（表现层 3s / 社交层 60s）
        plannerTask = planner.runTaskTimer(this, TickLayer.HEARTBEAT_TICKS, TickLayer.HEARTBEAT_TICKS);

        // 7.5 对话注视任务 —— 每 2 tick 让所有"正在对话"的村民持续面向玩家
        planner.startFaceTask();

        // 7. 注册命令
        getCommand("ai").setExecutor(this::handleAiCommand);

        // 8. 打印启动信息
        getLogger().info("========================================");
        getLogger().info(" MC-AI v" + getDescription().getVersion() + " (v2 tick layers)");
        getLogger().info("========================================");
        getLogger().info(" 模型: " + config.llmModel() + " @ " + config.llmUrl());
        getLogger().info(" 心跳: 20 ticks (1s) → 表现层 " + config.tickIntervalTicks() / 20.0
                + "s / 社交层 " + config.socialIntervalTicks() / 20.0 + "s");
        getLogger().info(" 已管理村民: " + villagers.keySet().size() + " 个");
        getLogger().info(" 记忆目录: " + getDataFolder().getAbsolutePath() + "/memory");
        getLogger().info("========================================");
        getLogger().info("MC-AI enabled! /ai test 验证 LLM，/ai reload 重载配置。");

        // 9. 全局扫一遍所有已加载 chunks → 把自然 spawn 的原版村民也注册进 AI 管理
        // （ChunkLoadEvent 只对新加载的 chunk 触发，已加载的需要手动扫）
        Bukkit.getScheduler().runTask(this, () -> {
            int autoReg = 0;
            for (var world : Bukkit.getWorlds()) {
                for (var chunk : world.getLoadedChunks()) {
                    for (Entity entity : chunk.getEntities()) {
                        if (entity instanceof org.bukkit.entity.Villager v) {
                            if (!villagers.containsKey(v.getUniqueId())) {
                                VillagerProfession prof = VillagerProfession.fromMinecraftProfession(v.getProfession());
                                // 不再排除 NITWIT/NONE —— 硬约束要求所有自然生成村民（含傻子/无职业）都注册，
                                // fromMinecraftProfession 对两者都有正确映射，PromptBuilder/人设也都覆盖了
                                //
                                // 名字必须在 put **之前**算好：ensureUniqueName 遍历 villagers.values()
                                // 比对 customName，先 put 自己会匹配到"刚入 map、还顶着旧名的自己"，
                                // 导致每次重启所有已命名村民都被改成随机名
                                String proposedName = v.getCustomName() != null
                                        ? v.getCustomName().replaceAll("§.", "") : prof.zhName;
                                String name = ensureUniqueName(proposedName);
                                // 名字全局唯一：旧版持久化的自定义名可能撞名（如两个"农夫老李"），撞了就换随机名
                                villagers.put(v.getUniqueId(), new VillagerState(v));
                                // 从磁盘恢复该村民的历史记忆（对话/事实/砍价/上架），没有文件就静默跳过
                                memoryStore.loadVillager(villagers.get(v.getUniqueId()));
                                if (!name.equals(proposedName)) {
                                    getLogger().info("§a[MC-AI] 启动改名(重名): " + proposedName + " → " + name);
                                }
                                v.setCustomName("§6" + name);
                                v.setCustomNameVisible(true);
                                v.setPersistent(true);
                                initVillagerShop(v, name);
                                autoReg++;
                                getLogger().info("§a[MC-AI] 启动扫到原版村民: " + name + "(" + prof.zhName + ") 职业映射=" + prof);
                            }
                        }
                    }
                }
            }
            getLogger().info("§b[MC-AI] 启动自动注册了 " + autoReg + " 个原版村民，当前共管理 " + villagers.size() + " 个");

            // 10. 社会关系配对：启动注册全部就绪后，给单身村民补夫妻/兄弟姐妹（职业/地缘配对，幂等）
            // 必须在"所有村民都注册进 map"之后再跑，配对器才能看到完整候选池
            // 兄弟随姓：配对器通过 uniqueNameWithSurname 让兄弟姐妹共用同一姓氏
            SocialRelationshipGenerator.assignRelationships(villagers, this::uniqueNameWithSurname);
            int paired = 0;
            for (var vs : villagers.values()) {
                if (!vs.relationships().isEmpty()) paired++;
            }
            // 配对结果立即落盘（markDirty 全部标脏，debounce 统一 flush），重启后关系不变
            villagers.keySet().forEach(memoryStore::markDirty);
            getLogger().info("§b[MC-AI] 社会关系配对完成: " + paired + " 个村民拥有家庭关系");
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("MC-AI shutting down...");

        // 1. cancel tick loop（防止关服时还在跑）
        if (plannerTask != null) {
            plannerTask.cancel();
            plannerTask = null;
        }
        // 1.5 停掉对话注视任务
        if (planner != null) {
            planner.stopFaceTask();
        }
        // 1.6 取消所有持续行动任务，清掉残留速度/状态
        if (npcTaskManager != null) {
            npcTaskManager.shutdown();
        }

        // 2. force flush 所有脏记忆
        if (memoryStore != null) {
            memoryStore.forceFlush();
        }

        // 3. 清理所有气泡实体
        if (bubbleManager != null) {
            bubbleManager.clearAll();
        }

        getLogger().info("MC-AI disabled cleanly.");
    }

    /**
     * 在 spawn 坐标附近查找铁匠老王；找不到就新建。
     * Paper 的 Villager 实体默认持久化到 level.dat，所以这步
     * 主要是"恢复已有的"而不是"每次新建"。
     */
    private void spawnOrLoadBlacksmith() {
        World world = Bukkit.getWorld(config.blacksmithWorld());
        if (world == null) {
            getLogger().severe("找不到世界 '" + config.blacksmithWorld()
                    + "'，老王没法出生。请检查 config.yml。");
            return;
        }

        Location spawn = new Location(world, config.blacksmithX(), config.blacksmithY(), config.blacksmithZ());

        // 在 spawn 半径 5 格内按 customName 匹配
        // 注意：customName 带 §6 颜色前缀，必须剥掉再比，否则永远匹配不上 → 每次重启重复刷老王
        Villager found = null;
        for (Entity e : world.getNearbyEntities(spawn, 5, 5, 5)) {
            if (!(e instanceof Villager v)) continue;
            String strippedName = v.getCustomName() != null
                    ? v.getCustomName().replaceAll("§.", "") : null;
            if (config.blacksmithName().equals(strippedName)) {
                found = v;
                break;
            }
        }

        Villager villager;
        if (found != null) {
            villager = found;
            getLogger().info("找到已有铁匠老王: " + villager.getUniqueId());
        } else {
            // 新建一个：M1 简化版只 setCustomName，Profession 让 Paper 自动给
            villager = world.spawn(spawn, Villager.class, v -> {
                v.setCustomName("§6" + config.blacksmithName());
                v.setCustomNameVisible(true);
                v.setPersistent(true);
            });
            getLogger().info("新建铁匠老王: " + villager.getUniqueId() + " @ " + spawn);
        }

        villagers.put(villager.getUniqueId(), new VillagerState(villager));
        initVillagerShop(villager, config.blacksmithName());
    }

    /**
     * 给村民注入 AI 专属交易配方 —— 替代 Paper 原版随机 level 配方。
     * 原版配方按 level 刷（新手可能卖土豆/面包/铁锭/金胡萝卜乱混），
     * 跟 AI 人设无关（铁匠不该卖面包）。
     * 必须在 entity 完全 spawn 后（下一个 tick）调 setRecipes，否则 Paper 会清掉。
     *
     * 调用点：
     * 1. 村民 spawn / 注册后（本方法）
     * 2. PlayerInteractListener 右键前（双重保险，防止 Paper 重新刷原版配方）
     */
    public void initVillagerShop(org.bukkit.entity.Villager villager, String name) {
        VillagerProfession prof = VillagerProfession.fromMinecraftProfession(villager.getProfession());
        // 优先用 VillagerState 的动态视图（砍价后的价格 + LLM 上架的商品都要体现），
        // 没有状态（理论不可达，所有调用点都先 put）才退回基础配方
        VillagerState state = villagers.get(villager.getUniqueId());
        java.util.List<org.bukkit.inventory.MerchantRecipe> recipes = (state != null)
                ? state.buildCurrentRecipes()
                : TradeRegistry.get(prof);
        if (recipes.isEmpty()) return;
        // 直接同步调用 setRecipes —— 调用方应该已经在主线程（PlayerInteractListener / executeDecision 都是）
        // 之前用 runTaskLater(20L) 延迟导致 Paper 有时间重置原版配方 → GUI 显示错误
        try {
            villager.setRecipes(recipes);
            getLogger().info("§b[MC-AI] 已给 " + name + "(" + prof.zhName + ") 注入 " + recipes.size() + " 条专属配方");
        } catch (Exception e) {
            getLogger().warning("setRecipes 失败: " + e.getMessage());
        }
    }

    /**
     * 职业刷新（主线程）—— 村民运行期职业变化时，把 AI 侧一并纠正到实体真实状态。
     * <p>
     * 为什么需要：原版村民刚加载时 {@code getProfession()} 往往是 {@code none}（还没认领工作站），
     * 而 VillagerState 以前只在注册那一刻读一次且是 final —— 于是"后来上了工的村民"
     * 在插件里永远是无业：GUI 状态卡写"职业: 无业"、人设与交易配方也都按无业走。
     * 现在周期 tick 与每次交互前都会重读，自动纠正。
     * <p>
     * 职业没变（或不合法实体）→ 直接返回，无任何副作用，可高频安全调用。
     * 调用点：SocialLayer 60 秒全村轮询 / CentralPlanner.switchConversation / VillagerInventoryGui.open。
     */
    public void refreshVillagerProfession(VillagerState vs) {
        if (vs == null || vs.entity() == null) return;
        VillagerProfession before = vs.profession();
        if (!vs.refreshProfession()) return;             // 职业没变 → 什么都不做
        VillagerProfession after = vs.profession();
        String name = vs.entity().getCustomName() != null
                ? vs.entity().getCustomName().replaceAll("§.", "") : after.zhName;
        getLogger().info("§b[MC-AI] 职业刷新: " + name + " " + before.zhName + " → " + after.zhName);
        // 只有"原本无业"才补发新职业的招牌物品：换岗（有业→有业）不补，避免背包被反复塞满
        if (before == VillagerProfession.NONE) vs.seedProfessionPool();
        // 重注入新职业的专属配方（替换掉旧的无业配方）
        initVillagerShop(vs.entity(), name);
        memoryStore.markDirty(vs.getUniqueId());
    }

    // ===== 工具系统 =====

    /**
     * 注册内置工具。新增工具只需要在这里加一行，不需要改 CentralPlanner 的执行 switch。
     */
    private void registerBuiltinTools() {
        toolRegistry.register(new NpcStatusGetTool(gameStateProvider));
        toolRegistry.register(new NpcInventoryGetTool(gameStateProvider));
        toolRegistry.register(new WorldObserveEntitiesTool(gameStateProvider));
        toolRegistry.register(new WorldNearbyBlocksTool(gameStateProvider));
        toolRegistry.register(new WorldEnvironmentTool(gameStateProvider));
        toolRegistry.register(new NpcTaskGetTool(npcTaskManager));
        toolRegistry.register(new NpcTaskCancelTool(npcTaskManager));
        toolRegistry.register(new NpcMoveToTool(this));
        // give_item 已从老后门迁入统一柜（NpcGiveItemTool 以别名桥接旧名，对话模型菜单不变）
        toolRegistry.register(new com.howard.mcai.tool.tools.NpcGiveItemTool(this));
        // accept_swap 同样迁入统一柜（值不值由 SwapPolicy 裁决）
        toolRegistry.register(new com.howard.mcai.tool.tools.NpcAcceptSwapTool(this));
        // break_block / place_block 迁入统一柜（权限把关仍在 WorldInteractionService）
        toolRegistry.register(new com.howard.mcai.tool.tools.NpcBreakBlockTool(this));
        toolRegistry.register(new com.howard.mcai.tool.tools.NpcPlaceBlockTool(this));
        // teleport / teleport_to 迁入统一柜（teleport_to 标 HIGH，策略层先卡神使）
        toolRegistry.register(new com.howard.mcai.tool.tools.NpcTeleportTool(this));
        toolRegistry.register(new com.howard.mcai.tool.tools.NpcTeleportToTool(this));
        getLogger().info("工具系统: 已注册 " + toolRegistry.size() + " 个工具");
    }

    // ===== 命令处理 =====

    private boolean handleAiCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§aMC-AI v" + getDescription().getVersion() + " §7(M1)");
            sender.sendMessage("§e/ai reload §f- 重载配置");
            sender.sendMessage("§e/ai test §f- 测试 LLM 连通性");
            sender.sendMessage("§e/ai llm §f- 查看/切换 LLM 后端（可接自己的 API，不必启动本地模型）");
            sender.sendMessage("§e/ai chat <消息> §f- 对准 AI 生物说话");
            sender.sendMessage("§e/ai spawn [名字] §f- 在你位置生成一个 AI 村民");
            sender.sendMessage("§e/ai list §f- 列出所有 AI 生物");
            sender.sendMessage("§e/ai needs [名字] §f- 查看 NPC 需求状态");
            sender.sendMessage("§e/ai envoy grant <名字> §f- 授予神使（完整权限，audit.log 审计）");
            sender.sendMessage("§e/ai envoy revoke <名字> §f- 撤销神使");
            sender.sendMessage("§e/ai envoy list §f- 查看权限等级说明与神使名单");
            sender.sendMessage("§e/ai tool list §f- 查看已注册工具");
            sender.sendMessage("§e/ai tool <工具名> <村民名> [参数1 参数2 ... | JSON] §f- 直接调用工具（调试用）");
            sender.sendMessage("§e/ai behavior <村民名> [提示] §f- 让行为模型基于真实状态选一步动作");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "test" -> handleTest(sender);
            case "llm" -> handleLlm(sender, args);
            case "chat" -> handleChat(sender, args);
            case "spawn" -> handleSpawn(sender, args);
            case "list" -> handleList(sender);
            case "lookup" -> handleLookup(sender, args);
            case "needs" -> handleNeeds(sender, args);
            case "envoy" -> handleEnvoy(sender, args);
            case "tool" -> handleTool(sender, args);
            case "behavior" -> handleBehavior(sender, args);
            default -> sender.sendMessage("§c未知子命令: " + args[0]);
        }
        return true;
    }

    /**
     * /ai spawn [名字] [职业] —— 在玩家当前位置生成一个新的 AI 村民。
     * 职业可省略（Paper 自动分配）。支持职业名（"blacksmith"）或中文名（"铁匠"）。
     * 自动加入 villagers map → 进 tick loop → 自动有记忆文件。
     *
     * 示例：
     * /ai spawn                              随机名字 + 随机职业
     * /ai spawn 铁匠老李                     随机职业
     * /ai spawn 铁匠老李 blacksmith          指定职业
     * /ai spawn 王婶 牧师                    中文职业名
     */
    private void handleSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家能用这个命令");
            return;
        }

        // 解析参数：最后一个如果是职业名就用它，剩下的拼名字
        VillagerProfession prof = null;
        String name;

        if (args.length >= 2) {
            // 先看最后一个是不是职业
            String lastArg = args[args.length - 1];
            VillagerProfession argProf = VillagerProfession.fromArg(lastArg);
            if (argProf != null) {
                // 最后一个是职业，前面的是名字
                name = args.length >= 3
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length - 1))
                        : uniqueRandomName();
                prof = argProf;
            } else {
                // 不是职业 → 全部是名字
                name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            }
        } else {
            name = uniqueRandomName();
        }

        // 用户指定的名字也可能撞名 → 全局唯一兜底（换随机名）
        name = ensureUniqueName(name);

        // lambda 需要 effectively final —— 提前设好 finalProf / finalName
        final VillagerProfession finalProf = prof;
        final String finalName = name;

        Location loc = player.getLocation().clone();
        World world = loc.getWorld();

        // Spawn 村民
        Villager villager = world.spawn(loc, Villager.class, v -> {
            v.setCustomName("§6" + finalName);
            v.setCustomNameVisible(true);
            v.setPersistent(true);
            if (finalProf != null) {
                v.setProfession(finalProf.toMinecraftProfession());
            }
        });

        // 注册到 AI 管理（指定职业优先，不然自动从 entity 读）
        VillagerState state = (prof != null)
                ? new VillagerState(villager, prof)
                : new VillagerState(villager);
        villagers.put(villager.getUniqueId(), state);
        initVillagerShop(villager, name);

        // 发一条气泡打招呼（用职业专属开头）
        String greeting = switch (state.profession()) {
            case TOOLSMITH -> "（嘿娃儿！俺是" + name + "，打铁的！）";
            case WEAPONSMITH -> "（来看看！好剑！俺是" + name + "！）";
            case ARMORER -> "（合身耐用！俺是" + name + "！）";
            case CLERIC -> "（施主好，我是" + name + "，村里的牧师。）";
            case FARMER -> "（俺是" + name + "，种地的，好年头啊！）";
            case LIBRARIAN -> "（阁下好，我是" + name + "，管书的。）";
            case CARTOGRAPHER -> "（好地图！远方宝藏！我是" + name + "！）";
            case FLETCHER -> "（好用！我是" + name + "！）";
            case FISHERMAN -> "（水好鱼鲜！我是" + name + "！）";
            case BUTCHER -> "（来块肉！新鲜的！我是" + name + "！）";
            case LEATHERWORKER -> "（好皮耐磨！我是" + name + "！）";
            case MASON -> "（实心结实！我是" + name + "！）";
            case SHEPHERD -> "（咩～小羊好毛！我是" + name + "！）";
            case NITWIT -> "（啊？啥？我...我是" + name + "。）";
            case NONE -> "（你好啊，我是" + name + "！）";
        };
        bubbleManager.show(villager, greeting, 80L);
        villager.sendMessage("§6" + name + "§f: " + greeting);

        // 反馈
        String profLabel = state.profession().zhName;
        sender.sendMessage("§a✓ 生成了 §e" + profLabel + " §6" + name + " §a@ ("
                + Math.round(loc.getX()) + ", " + Math.round(loc.getY()) + ", "
                + Math.round(loc.getZ()) + ")");
        sender.sendMessage("§e对准他 §f/ai chat <消息> §e说话");
        getLogger().info("玩家 " + player.getName() + " 生成 " + profLabel + " " + name
                + " UUID=" + villager.getUniqueId());
    }

    /**
     * 生成一个不与现有 AI 村民重名的随机名字（抽到重名就重抽，兜底加数字后缀）。
     * 名字用字由 NameLibrary 按性别抽取 —— <b>性别天然跟着名字走</b>，不会再出现
     * "刘翠花是男的"这种崩人设问题。铁匠老王（config）的名字也纳入去重。
     */
    public String uniqueRandomName() {
        return uniqueRandomName(NameLibrary.randomGender());
    }

    /** 指定性别生成唯一名（如兄弟随姓改名时要保留原性别，不能随机翻转） */
    public String uniqueRandomName(String gender) {
        java.util.Set<String> used = usedNames();
        for (int i = 0; i < 50; i++) {
            String name = NameLibrary.fullName(gender);
            if (!used.contains(name)) return name;
        }
        return NameLibrary.fullName(gender) + (int) (Math.random() * 100);  // 重抽池抽完兜底，防死循环
    }

    /**
     * 生成一个"指定姓氏 + 指定性别"且全局唯一的中文村民名 —— 兄弟姐妹随爸/妈姓时改名用。
     * 姓氏非法/为空时退回随机名；性别必须传入以保留当事人原有的男女（否则改名会变性）。
     */
    public String uniqueNameWithSurname(String surname, String gender) {
        if (surname == null || surname.isBlank()) return uniqueRandomName(gender);
        java.util.Set<String> used = usedNames();
        for (int i = 0; i < 50; i++) {
            String name = surname + NameLibrary.randomGivenName(gender);
            if (!used.contains(name)) return name;
        }
        return surname + (int) (Math.random() * 100);  // 兜底加数字，防死循环
    }

    /** 现有村民（含铁匠老王）已用的名字集合（剥掉颜色码），用于生成时唯一性去重 */
    private java.util.Set<String> usedNames() {
        java.util.Set<String> used = new java.util.HashSet<>();
        used.add(config.blacksmithName().replaceAll("§.", ""));
        for (VillagerState vs : villagers.values()) {
            String n = vs.entity().getCustomName();
            used.add(n != null ? n.replaceAll("§.", "") : vs.profession().zhName);
        }
        return used;
    }

    /**
     * 确保名字全局唯一 —— 已有村民（含铁匠老王）撞名时自动换一个随机名。
     * 用于自动注册路径：自然生成的村民可能带自定义名（如旧版持久化的"农夫老李"），
     * 多个村民撞名会导致玩家分不清谁是谁，聊天点名也会张冠李戴。
     * 记忆文件按 UUID 关联，改名不影响已有记忆。
     */
    public String ensureUniqueName(String proposed) {
        if (proposed == null || proposed.isBlank()) return uniqueRandomName();
        String clean = proposed.replaceAll("§.", "");
        // 撞名检查：遍历现有村民的 customName（剥掉颜色码）
        for (VillagerState vs : villagers.values()) {
            String n = vs.entity().getCustomName();
            if (n != null && n.replaceAll("§.", "").equals(clean)) {
                return uniqueRandomName();
            }
        }
        return clean;
    }

    /** /ai list —— 列出所有 AI 生物 */
    private void handleList(CommandSender sender) {
        if (villagers.isEmpty()) {
            sender.sendMessage("§c没有 AI 生物");
            return;
        }
        sender.sendMessage("§aMC-AI 生物列表（" + villagers.size() + " 个村民 + "
                + planner.mobStates().size() + " 只动物/怪物）：");
        for (var entry : villagers.entrySet()) {
            VillagerState state = entry.getValue();
            Villager v = state.entity();
            String status = v.isValid() ? "§a在线" : "§c离线";
            String interacting = state.interactingPlayer() != null ? "§b对话中" : "§7空闲";
            String rank = permissionManager != null ? "§d" + permissionManager.rankOf(state, null).zhName() : "";
            String loc = "§e(" + Math.round(v.getLocation().getX()) + ","
                    + Math.round(v.getLocation().getY()) + ","
                    + Math.round(v.getLocation().getZ()) + ")";
            sender.sendMessage("  §f• §6" + v.getCustomName() + " §fUUID=" + entry.getKey().toString().substring(0, 8)
                    + " " + status + " " + interacting + " " + rank + " " + loc);
        }
    }

    /**
     * /ai needs [名字] —— 查看 NPC 需求状态（SPEC 2.14 调试入口）。
     * 无参数列出全村五项需求概览，带名字看单个详情（含等级 + 库存）。
     */
    private void handleNeeds(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            VillagerState vs = findVillagerByExactName(String.join(" ",
                    java.util.Arrays.copyOfRange(args, 1, args.length)));
            if (vs == null) {
                sender.sendMessage("§c找不到这个村民（/ai list 查看名单）");
                return;
            }
            sender.sendMessage("§a=== " + SmalltalkName(vs) + " 的状态 ===");
            var snap = vs.needs().snapshot();
            for (var e : snap.entrySet()) {
                sender.sendMessage("  §e" + com.howard.mcai.mob.NeedsState.zhName(e.getKey())
                        + " §f" + bar(e.getValue().intValue()) + " " + e.getValue().intValue());
            }
            String inv = vs.inventorySnapshot().entrySet().stream()
                    .map(en -> PromptBuilder.materialToZh(org.bukkit.Material.matchMaterial(en.getKey()))
                            + "x" + en.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("空");
            sender.sendMessage("  §d等级: " + (permissionManager != null ? permissionManager.rankOf(vs, null).zhName() : vs.rank())
                    + " §7库存: §f" + inv);
            return;
        }
        // 无参数：全村概览
        sender.sendMessage("§a全村需求概览（≥" + (int) pluginConfigThreshold() + " 触发自主行动）：");
        for (VillagerState vs : villagers.values()) {
            var snap = vs.needs().snapshot();
            StringBuilder line = new StringBuilder("  §6" + SmalltalkName(vs) + " §7");
            for (var e : snap.entrySet()) {
                line.append(com.howard.mcai.mob.NeedsState.zhName(e.getKey()).substring(0, 1))
                        .append(":").append(e.getValue().intValue()).append(" ");
            }
            sender.sendMessage(line.toString());
        }
    }

    private double pluginConfigThreshold() {
        return getPluginConfig().needsThreshold();
    }

    /** 村民显示名（去颜色码）—— 命令输出用 */
    private static String SmalltalkName(VillagerState vs) {
        String n = vs.entity().getCustomName();
        return n != null && !n.isBlank() ? n.replaceAll("§.", "") : vs.profession().zhName;
    }

    /** 需求值 → 十格文本进度条 */
    private static String bar(int v) {
        int filled = Math.max(0, Math.min(10, v / 10));
        return "§a" + "■".repeat(filled) + "§8" + "□".repeat(10 - filled);
    }

    /** 按显示名精确查找村民（去颜色码比对） */
    private VillagerState findVillagerByExactName(String name) {
        if (name == null || name.isBlank()) return null;
        for (VillagerState vs : villagers.values()) {
            if (SmalltalkName(vs).equals(name)) return vs;
        }
        return null;
    }

    /**
     * /ai envoy grant|revoke <名字> | /ai envoy list —— 神使授予/撤销（SPEC 2.14 三档权限）。
     * 授予/撤销都写 audit.log；神使完整权限（破坏任意方块/传送任意实体/刷物品），
     * 撤销后打回派生等级（多数情况下=平民）。持久化到记忆文件，重启不丢。
     */
    private void handleEnvoy(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ai envoy grant|revoke <名字> 或 /ai envoy list");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> {
                sender.sendMessage("§d=== 权限等级说明 ===");
                if (permissionManager != null) {
                    permissionManager.rankRuleLines().forEach(l -> sender.sendMessage("  §7" + l));
                }
                sender.sendMessage("§d=== 当前神使 ===");
                int n = 0;
                for (VillagerState vs : villagers.values()) {
                    if (vs.rank() == com.howard.mcai.security.NpcRank.ENVOY) {
                        sender.sendMessage("  §6" + SmalltalkName(vs));
                        n++;
                    }
                }
                if (n == 0) sender.sendMessage("  §7（无）");
                sender.sendMessage("§7审计日志: " + (auditLog != null ? auditLog.file() : "未初始化"));
            }
            case "grant", "revoke" -> {
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /ai envoy " + args[1] + " <名字>");
                    return;
                }
                VillagerState vs = findVillagerByExactName(String.join(" ",
                        java.util.Arrays.copyOfRange(args, 2, args.length)));
                if (vs == null) {
                    sender.sendMessage("§c找不到这个村民（/ai list 查看名单）");
                    return;
                }
                boolean grant = args[1].equalsIgnoreCase("grant");
                vs.setRank(grant
                        ? com.howard.mcai.security.NpcRank.ENVOY
                        : com.howard.mcai.security.NpcRank.CIVILIAN);
                memoryStore.markDirty(vs.getUniqueId());
                // 授予/撤销事件本身也进审计 —— 权限变更必须留痕
                if (auditLog != null) {
                    auditLog.log(SmalltalkName(vs), vs.getUniqueId(),
                            grant ? "ENVOY_GRANT" : "ENVOY_REVOKE",
                            "by=" + sender.getName(), "OK");
                }
                sender.sendMessage((grant ? "§a✓ 已授予 §6" : "§e✓ 已撤销 §6")
                        + SmalltalkName(vs) + (grant ? " §a的神使权限（操作将写 audit.log）" : " §e的神使权限，已打回平民"));
                getLogger().info((grant ? "§b[MC-AI] 神使授予: " : "§e[MC-AI] 神使撤销: ")
                        + SmalltalkName(vs) + " by " + sender.getName());
            }
            default -> sender.sendMessage("§c未知子命令: " + args[1] + "（可用 grant/revoke/list）");
        }
    }

    /**
     * /ai tool list —— 列出所有已注册工具。
     * /ai tool <工具名> <村民名> [参数1 参数2 ... | JSON] —— 不经过模型直接调用工具，用于验收和排查。
     * 位置参数按工具 schema 顺序填写，坐标支持 ~ 相对值，如：
     * {@code /ai tool npc.move_to 汤孟和 100 64 105}、{@code /ai tool npc.move_to 汤孟和 ~1 ~0 ~-15}
     */
    private void handleTool(CommandSender sender, String[] args) {
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§a已注册工具: " + toolRegistry.size());
            for (NpcTool tool : toolRegistry.all()) {
                sender.sendMessage("§e" + tool.name() + " §7[" + tool.category() + "/" + tool.risk() + "] §f"
                        + tool.description());
            }
            sender.sendMessage("§7用法: /ai tool <工具名> <村民名> [参数1 参数2 ... | JSON]");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ai tool <工具名> <村民名> [参数1 参数2 ... | JSON]");
            return;
        }

        String toolName = args[1];
        int jsonIndex = -1;
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("{")) {
                jsonIndex = i;
                break;
            }
        }

        String npcName;
        String json = null;
        List<String> positional = new ArrayList<>();
        if (jsonIndex != -1) {
            npcName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, jsonIndex));
            json = String.join(" ", java.util.Arrays.copyOfRange(args, jsonIndex, args.length));
        } else {
            // 位置参数形式：/ai tool npc.move_to 汤孟和 100 64 105
            // 从尾部往前收集坐标样子的 token（数字 / ~数字），剩下整体当村民名，
            // 这样村民名里带空格也不会被误拆。
            int valueStart = args.length;
            while (valueStart > 3 && isCoordinateToken(args[valueStart - 1])) {
                valueStart--;
            }
            npcName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, valueStart));
            for (int i = valueStart; i < args.length; i++) positional.add(args[i]);
        }

        VillagerState target = findVillagerByExactName(npcName);
        if (target == null) {
            sender.sendMessage("§c找不到村民: " + npcName);
            return;
        }

        JsonObject arguments = new JsonObject();
        if (json != null) {
            try {
                JsonElement parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()) {
                    sender.sendMessage("§c参数必须是 JSON 对象");
                    return;
                }
                arguments = parsed.getAsJsonObject();
            } catch (Exception e) {
                sender.sendMessage("§cJSON 参数无效: " + e.getMessage());
                return;
            }
        } else if (!positional.isEmpty()) {
            NpcTool tool = toolRegistry.find(toolName);
            if (tool == null) {
                sender.sendMessage("§c未知工具: " + toolName);
                return;
            }
            try {
                arguments = resolvePositionalArgs(tool, target, positional);
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§c" + e.getMessage());
                return;
            }
        }

        UUID playerUuid = sender instanceof Player player ? player.getUniqueId() : null;
        ToolRequest request = ToolRequest.create(ToolContext.CallerType.PLUGIN, "command",
                target.getUniqueId(), playerUuid, toolName, arguments);
        // 控制台留一条命令层面的记录（Dispatcher 还会打一条带结果的 [tool] 行）
        getLogger().info("[cmd] /ai tool " + toolName + " npc=" + npcName + " args=" + arguments);
        try {
            ToolResult result = toolDispatcher.dispatchSync(request);
            sender.sendMessage("§a[tool] " + toolName + " → " + result.status()
                    + (result.success() ? "" : " §c" + result.errorCode() + ": " + result.errorMessage()));
            if (result.taskId() != null) {
                sender.sendMessage("§7task_id: " + result.taskId()
                        + "（用 /ai tool npc.task.get " + npcName + " {\"task_id\":\"" + result.taskId() + "\"} 查询）");
            }
            if (result.data() != null) {
                sender.sendMessage("§f" + result.data());
            }
        } catch (Exception e) {
            getLogger().warning("[cmd] /ai tool " + toolName + " 异常: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            sender.sendMessage("§c工具调用异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 位置参数 → 工具参数对象（按 schema 声明顺序一一对应）。
     * <p>
     * 例如 npc.move_to 的顺序是 x y z speed：
     * {@code /ai tool npc.move_to 汤孟和 100 64 105}。
     * 坐标支持原版风格相对值：{@code ~} 表示村民当前位置，{@code ~1} 表示 +1，{@code ~-15} 表示 -15。
     */
    private JsonObject resolvePositionalArgs(NpcTool tool, VillagerState target, List<String> tokens) {
        Map<String, ToolSchema.Param> params = tool.schema().params();
        List<String> names = new ArrayList<>(params.keySet());
        if (tokens.size() > names.size()) {
            throw new IllegalArgumentException("参数过多：该工具最多 " + names.size()
                    + " 个（顺序: " + String.join(" ", names) + "）");
        }
        Location base = target.entity().getLocation();
        JsonObject out = new JsonObject();
        for (int i = 0; i < tokens.size(); i++) {
            String key = names.get(i);
            ToolSchema.Param param = params.get(key);
            String token = tokens.get(i);
            switch (param.type()) {
                case NUMBER -> out.addProperty(key, parseCoordinate(token, key, base));
                case INTEGER -> out.addProperty(key, (long) parseCoordinate(token, key, base));
                case BOOLEAN -> out.addProperty(key, Boolean.parseBoolean(token));
                case STRING -> out.addProperty(key, token);
            }
        }
        return out;
    }

    /**
     * 解析单个坐标参数：绝对数字原样使用；{@code ~}/{@code ~1}/{@code ~-15} 以村民当前位置为基准偏移。
     * 只允许 x/y/z 用相对坐标，其它数值参数（如 speed）必须写绝对数字。
     */
    private static double parseCoordinate(String token, String key, Location base) {
        boolean coordinate = key.equals("x") || key.equals("y") || key.equals("z");
        if (token.startsWith("~")) {
            if (!coordinate) {
                throw new IllegalArgumentException("参数 " + key + " 不支持相对坐标，请填数字");
            }
            String offset = token.substring(1).trim();
            if (offset.isEmpty()) {
                return switch (key) {
                    case "x" -> base.getX();
                    case "y" -> base.getY();
                    default -> base.getZ();
                };
            }
            try {
                double delta = Double.parseDouble(offset);
                return switch (key) {
                    case "x" -> base.getX() + delta;
                    case "y" -> base.getY() + delta;
                    default -> base.getZ() + delta;
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("坐标格式错误: " + token + "（应为 ~、~1、~-15 或数字）");
            }
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("坐标格式错误: " + token + "（应为数字或 ~ 相对坐标）");
        }
    }

    /** 判断一个命令行 token 是否像坐标：数字或 ~ 开头的相对坐标（单独一个 ~ 也表示相对） */
    private static boolean isCoordinateToken(String token) {
        return token.matches("~(-?\\d+(\\.\\d+)?)?") || token.matches("-?\\d+(\\.\\d+)?");
    }

    /**
     * /ai behavior <村民名> [提示] —— 用行为模型（MiniCPM）基于真实游戏状态选一步动作，
     * 然后走统一 Dispatcher 执行。用于阶段五"模型适配"的实机验收：
     * 验证 1B 模型的 JSON 输出能被解析、校验并真正落地，而不经过对话模型。
     */
    private void handleBehavior(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ai behavior <村民名> [提示语]");
            return;
        }
        if (behaviorModelClient == null) {
            sender.sendMessage("§c行为模型尚未初始化（插件未完成启动？）");
            return;
        }
        String npcName = args[1];
        String hint = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : null;
        VillagerState target = findVillagerByExactName(npcName);
        if (target == null) {
            sender.sendMessage("§c找不到村民: " + npcName);
            return;
        }

        UUID playerUuid = sender instanceof Player player ? player.getUniqueId() : null;
        // 状态必须在主线程采集（Bukkit API 约束）
        JsonObject state = gameStateProvider.npcStatus(target.getUniqueId(), playerUuid);
        if (state == null) {
            sender.sendMessage("§c读不到该村民的状态（实体已失效？）");
            return;
        }
        state.add("nearby_entities", gameStateProvider.nearbyEntities(target.getUniqueId(), 16, 32));

        sender.sendMessage("§7行为模型: §f" + behaviorModelClient.settings().model()
                + " §7@ §f" + behaviorModelClient.settings().url());
        // 控制台同步留痕：命令参数、模型选择/空闲/解析失败都要能看到
        getLogger().info("[behavior] " + npcName + " hint=" + (hint == null ? "(none)" : hint)
                + " model=" + behaviorModelClient.settings().model());
        behaviorModelClient.decide(target.getUniqueId(), playerUuid, "command", state, hint)
                .thenAccept(outcome -> Bukkit.getScheduler().runTask(this, () -> {
                    if (!outcome.ok()) {
                        getLogger().warning("[behavior] " + npcName + " 解析失败: " + outcome.error());
                        sender.sendMessage("§c行为模型输出无法解析: " + outcome.error());
                        return;
                    }
                    if (outcome.idle()) {
                        getLogger().info("[behavior] " + npcName + " → idle");
                        sender.sendMessage("§e行为模型决定: idle（这一步不行动）");
                        return;
                    }
                    ToolRequest request = outcome.request();
                    getLogger().info("[behavior] " + npcName + " → " + request.tool()
                            + " args=" + request.arguments());
                    sender.sendMessage("§a行为模型选择: §6" + request.tool()
                            + " §7args=" + request.arguments());
                    try {
                        ToolResult result = toolDispatcher.dispatchSync(request);
                        sender.sendMessage("§a[tool] → " + result.status()
                                + (result.success() ? "" : " §c" + result.errorCode() + ": "
                                + result.errorMessage()));
                        if (result.taskId() != null) {
                            sender.sendMessage("§7task_id: " + result.taskId());
                        }
                    } catch (Exception e) {
                        getLogger().warning("[behavior] " + npcName + " 工具执行异常: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                        sender.sendMessage("§c工具执行异常: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    getLogger().warning("[behavior] " + npcName + " 调用失败: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§c行为模型调用失败: " + ex.getMessage()));
                    return null;
                });
    }

    /**
     * /ai chat <消息> —— 对准 AI 生物说话（手动触发的快捷方式）。
     * 真正的对话现在靠 PlayerChatListener 自动处理（进入对话模式后正常聊天就行），
     * /ai chat 留着给"想指定对话对象"或者"没进入对话模式"的场景。
     *
     * 内部流程跟 PlayerChatListener 完全一样：append chatHistory → triggerLLM。
     */
    private void handleChat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家能用这个命令");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ai chat <消息>");
            return;
        }

        // 拼完整消息（/ai chat 你好啊老王 → "你好啊老王"）
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        // 找玩家视线中的村民（4 格内 + 视野 cone）
        Villager target = findVillagerInLineOfSight(player);
        if (target == null) {
            sender.sendMessage("§c附近没有村民可对话（先走到村民身边对准他）");
            return;
        }

        VillagerState state = villagers.get(target.getUniqueId());
        if (state == null) {
            sender.sendMessage("§c这个村民不属于 MC-AI 管理");
            return;
        }

        // 1. 启动对话（如果还没激活）—— 多 NPC 时先切走玩家在其他村民身上的对话状态
        String content;
        if (state.interactingPlayer() == null) {
            planner.switchConversation(player, state);
            content = "Player " + player.getName()
                    + " (ID: player_" + player.getUniqueId().toString().substring(0, 8)
                    + ") said: " + message;
        } else {
            content = message;
        }

        // 2. 立刻触发 LLM —— 统一走 planner.onPlayerMessage：
        //    LLM 忙时自动积压续调，保证 user↔assistant 严格交替
        bubbleManager.showDialog(target,
                target.getCustomName() != null ? target.getCustomName().replaceAll("§.", "") : "村民",
                "（思考中...）");
        planner.onPlayerMessage(state, player, content);
    }

    /** 找玩家视线 4 格内的最近村民 */
    private Villager findVillagerInLineOfSight(Player player) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector look = eye.getDirection();
        Location end = eye.clone().add(look.clone().multiply(4));

        Villager nearest = null;
        double nearestDist = 4.5;  // 4 格 + 容错

        for (Villager v : player.getWorld().getNearbyEntitiesByType(Villager.class, eye, 5)) {
            double d = eye.distance(v.getLocation().clone().add(0, 0.5, 0));
            if (d < nearestDist) {
                // 额外：检查村民是否在玩家视野角度内（45° cone）
                org.bukkit.util.Vector toVillager = v.getLocation().clone().add(0, 0.5, 0).toVector().subtract(eye.toVector()).normalize();
                double dot = look.dot(toVillager);
                // cos(45°) ≈ 0.707，要求 dot > 0.5（约 60° cone，宽容一点）
                if (dot > 0.5) {
                    nearestDist = d;
                    nearest = v;
                }
            }
        }
        return nearest;
    }

    private void handleReload(CommandSender sender) {
        reloadConfig();
        config = PluginConfig.load(getConfig());
        llmClient = new LLMClient(config);
        rebuildBehaviorModelClient();
        // 新 config 对象要同步给静态镜像，否则压缩阈值永远是启动时的旧值
        VillagerState.setMaxChatHistoryTokens(config.maxChatHistoryTokens());
        // 顺带 reload 配方表 + 人格标签库（用户可能编辑了）
        recipeLookup.load(getDataFolder().toPath());
        PromptBuilder.setRecipeLookup(recipeLookup);
        PersonalityTagLibrary.load(getDataFolder().toPath());
        NameLibrary.load(getDataFolder().toPath());
        sender.sendMessage("§a配置 + 配方 + 人格标签 + 名字库已重载 — 模型: " + config.llmModel()
                + " §7@ §f" + config.llmUrl()
                + " §7(recipes=" + (recipeLookup.isLoaded() ? recipeLookup.size() : 0)
                + ", tags=" + (PersonalityTagLibrary.isLoaded() ? PersonalityTagLibrary.size() : 0)
                + ", names=" + (NameLibrary.isLoaded() ? NameLibrary.surnameCount() : 0) + ")");
        getLogger().info("Config + recipes + personality tags + names reloaded. model=" + config.llmModel()
                + " recipes=" + (recipeLookup.isLoaded() ? recipeLookup.size() : 0)
                + " tags=" + (PersonalityTagLibrary.isLoaded() ? PersonalityTagLibrary.size() : 0)
                + " names=" + (NameLibrary.isLoaded() ? NameLibrary.surnameCount() : 0));
    }

    /**
     * /ai lookup <物品名> —— 查配方（按中文名或 ID）。
     * 无参数列出全部可查物品。
     */
    private void handleLookup(CommandSender sender, String[] args) {
        if (!recipeLookup.isLoaded()) {
            sender.sendMessage("§crecipes.json 没加载 — 检查 plugins/MC-AI/ 目录下有没有这个文件");
            return;
        }

        if (args.length < 2) {
            // 列全部
            var names = recipeLookup.listAllNames();
            sender.sendMessage("§a配方表（" + names.size() + " 条）— 无参数: /ai lookup <物品名>");
            // 分批发，防刷屏
            for (int i = 0; i < names.size(); i += 10) {
                int end = Math.min(i + 10, names.size());
                sender.sendMessage("  §f" + String.join("§7, §f", names.subList(i, end)));
            }
            return;
        }

        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        var entry = recipeLookup.lookup(query);
        if (entry == null) {
            sender.sendMessage("§c没找到 \"" + query + "\" 的配方 — /ai lookup 列全部");
            return;
        }

        sender.sendMessage("§a✓ §6" + entry.nameZh() + " §7(" + entry.key() + ")");
        sender.sendMessage("  §e材料: §f" + entry.materials());
        sender.sendMessage("  §e产出: §f" + entry.yields());
    }

    private void handleTest(CommandSender sender) {
        // 把当前后端一起打出来 —— 换 API 后最常见的疑问就是"我到底连的是哪个地址"
        sender.sendMessage("§7正在测试 LLM：§f" + config.llmModel() + " §7@ §f" + config.llmUrl());

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system",
                        "content", "You are Old Wang, a blacksmith villager in a Minecraft village. "
                                + "You see a player named Steve holding a hostile iron sword just 3 blocks away. "
                                + "Decide what ONE action to take. You MUST call a tool."),
                Map.of("role", "user",
                        "content", "Steve (player_1) is approaching aggressively. What do you do?")
        );

        CompletableFuture<LLMResponse> future = llmClient.chat(messages, BehaviorRegistry.toolSchemas());

        future.thenAccept(response -> {
            String resultMsg;
            if (response.hasToolCall()) {
                var call = response.firstToolCall();
                resultMsg = "§a✓ 收到 tool_call: §6" + call.name()
                        + "§7 参数: §f" + call.arguments();
                getLogger().info("/ai test 成功 — tool_call=" + call.name() + " args=" + call.arguments());
            } else {
                resultMsg = "§a✓ LLM 连通（无 tool_call）\n  §7content: §f" + response.content();
                getLogger().info("/ai test 纯文本回复: " + response.content());
            }
            sender.sendMessage(resultMsg);
        }).exceptionally(ex -> {
            sender.sendMessage("§c✗ LLM 调用失败: " + ex.getMessage());
            getLogger().warning("/ai test 失败 — " + ex.getMessage());
            return null;
        });
    }

    // ===== LLM 后端切换（/ai llm，SPEC：支持接入任意 OpenAI 兼容 API）=====

    /**
     * 预置后端 —— 值 = {endpoint, 默认模型}（api-key 由用户在命令里带）。
     * <p>
     * 目的：用户想接自己的云 API 时不用翻各家文档拼 url，
     * `/ai llm preset deepseek sk-xxx` 一条命令切过去，立即生效且写回 config.yml。
     */
    private static final Map<String, String[]> LLM_PRESETS = new java.util.LinkedHashMap<>();

    static {
        LLM_PRESETS.put("local", new String[]{"http://localhost:8080/v1/chat/completions", "qwen3.5-9b"});
        LLM_PRESETS.put("ollama", new String[]{"http://localhost:11434/v1/chat/completions", "qwen3.5-9b"});
        LLM_PRESETS.put("deepseek", new String[]{"https://api.deepseek.com/v1/chat/completions", "deepseek-chat"});
        LLM_PRESETS.put("openai", new String[]{"https://api.openai.com/v1/chat/completions", "gpt-4o-mini"});
        LLM_PRESETS.put("qwen", new String[]{"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"});
        LLM_PRESETS.put("siliconflow", new String[]{"https://api.siliconflow.cn/v1/chat/completions", "Qwen/Qwen2.5-7B-Instruct"});
        LLM_PRESETS.put("moonshot", new String[]{"https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"});
    }

    /**
     * /ai llm —— 查看当前后端 / 预置切换 / 自定义切换。
     * <pre>
     *   /ai llm                                    查看当前后端
     *   /ai llm preset deepseek sk-xxx             切到预置后端
     *   /ai llm set https://x/v1 gpt-4o-mini sk-x  自定义后端
     * </pre>
     */
    private void handleLlm(CommandSender sender, String[] args) {
        if (args.length < 2) {
            printLlmStatus(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "preset" -> {
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /ai llm preset <" + String.join("|", LLM_PRESETS.keySet()) + "> [api-key]");
                    return;
                }
                String name = args[2].toLowerCase();
                String[] preset = LLM_PRESETS.get(name);
                if (preset == null) {
                    sender.sendMessage("§c未知预置后端: " + name + "，可用: " + String.join(" / ", LLM_PRESETS.keySet()));
                    return;
                }
                String key = args.length >= 4
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
                // 云端预置没给 key → 大概率 401。仍然允许（可能接的是免鉴权内网代理），只提醒
                if (key.isBlank() && !PluginConfig.isLocalEndpoint(preset[0])) {
                    sender.sendMessage("§e⚠ 没带 api-key，云端后端通常会返回 401；用法: /ai llm preset "
                            + name + " <api-key>");
                }
                applyLlmBackend(sender, preset[0], preset[1], key, "预置 " + name);
            }
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /ai llm set <url> <model> [api-key]");
                    return;
                }
                String key = args.length >= 5
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : "";
                applyLlmBackend(sender, args[2], args[3], key, "自定义");
            }
            case "model" -> {
                // 只换模型名（最常用：在 Ollama 里换了模型后同步过来）；填 auto 则交给插件自动跟随
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /ai llm model <模型名|auto>");
                    return;
                }
                applyLlmModel(sender, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim());
            }
            case "list" -> listLlmModels(sender);
            default -> printLlmStatus(sender);
        }
    }

    /** 打印当前 LLM 后端（key 打码，避免截屏泄露） */
    private void printLlmStatus(CommandSender sender) {
        boolean local = PluginConfig.isLocalEndpoint(config.llmUrl());
        sender.sendMessage("§a=== LLM 后端 ===");
        sender.sendMessage("  §eurl  §f" + config.llmUrl());
        sender.sendMessage("  §e模型 §f" + config.llmModel()
                + (llmClient.isAutoModel() ? "  §7(auto：自动跟随 " + config.llmUrl() + " 当前加载的模型)" : ""));
        sender.sendMessage("  §ekey  §f" + maskKey(config.llmApiKey()));
        sender.sendMessage("  §e类型 §f" + (local ? "本机（需自己启 llama-server/Ollama）" : "云端（不用启动本地模型）"));
        sender.sendMessage("  §e超时 §f连接 " + config.connectTimeoutMs() + "ms / 读取 " + config.readTimeoutMs() + "ms");
        sender.sendMessage("§7切换: §f/ai llm preset <" + String.join("|", LLM_PRESETS.keySet()) + "> [api-key]");
        sender.sendMessage("§7      §f/ai llm set <url> <model> [api-key]");
        sender.sendMessage("§7      §f/ai llm model <模型名|auto> §7- 只换模型 / 让插件自动跟随");
        sender.sendMessage("§7      §f/ai llm list §7- 列出后端当前可用模型");
    }

    /**
     * 只改 llm.model 并立即生效 —— 你在 Ollama 里换了模型后，用这条同步过来；
     * 填 auto 则以后都交给插件自动探测，换模型不用再管配置。
     */
    private void applyLlmModel(CommandSender sender, String model) {
        boolean written = persistLlmConfig(java.util.Map.of("model", model));
        reloadConfig();
        config = PluginConfig.load(getConfig());
        llmClient = new LLMClient(config);
        rebuildBehaviorModelClient();
        sender.sendMessage("§a✓ 模型已设为: §f" + config.llmModel());
        if (llmClient.isAutoModel()) {
            sender.sendMessage("§7auto 模式：每次请求前探测后端当前加载的模型（30 秒缓存），"
                    + "模型被换掉会自动重探并重试一次");
        }
        sender.sendMessage(written ? "§7已写回 plugins/MC-AI/config.yml"
                : "§c未能写回 config.yml（见控制台警告），当前仅本次运行生效");
    }

    /** 列出后端当前可用模型（Ollama /api/tags 或 OpenAI 风格 /v1/models）—— 同步 HTTP，必须异步执行 */
    private void listLlmModels(CommandSender sender) {
        sender.sendMessage("§7正在查询 " + config.llmUrl() + " 的模型列表…");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            java.util.List<String> models = llmClient.listModelsBlocking();
            // 回到主线程发消息（玩家/控制台交互必须在主线程）
            Bukkit.getScheduler().runTask(this, () -> {
                if (models.isEmpty()) {
                    sender.sendMessage("§c没查到模型：后端未启动，或该后端不支持 /api/tags 与 /v1/models");
                    return;
                }
                sender.sendMessage("§a=== 可用模型 (" + models.size() + ") ===");
                for (String m : models) sender.sendMessage("  §f" + m);
                sender.sendMessage("§7切换: §f/ai llm model <模型名> §7或 §f/ai llm model auto");
            });
        });
    }

    /** api-key 打码：只露前 6 位（空 = 不发 Authorization 头） */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "（空，不发 Authorization 头）";
        if (key.length() <= 6) return "***";
        return key.substring(0, 6) + "***";
    }

    /**
     * 应用新的 LLM 后端：写回 config.yml → 重载内存配置 → 重建 LLMClient（立即生效，无需重启）。
     *
     * @param source 来源描述（"预置 deepseek" / "自定义"），只用于日志
     */
    private void applyLlmBackend(CommandSender sender, String url, String model, String apiKey, String source) {
        String normalized = PluginConfig.normalizeLlmUrl(url);
        boolean local = PluginConfig.isLocalEndpoint(normalized);

        java.util.LinkedHashMap<String, String> patch = new java.util.LinkedHashMap<>();
        patch.put("url", normalized);
        patch.put("model", model);
        patch.put("api-key", apiKey == null ? "" : apiKey);
        // 云端 API 排队 + 长回复常超 30 秒 → 读超时抬到 60 秒（只调大不调小，不覆盖用户设的更大值）
        boolean bumpedTimeout = false;
        if (!local && getConfig().getInt("llm.read-timeout-ms", 30000) < 60000) {
            patch.put("read-timeout-ms", "60000");
            bumpedTimeout = true;
        }
        // reasoning_effort 是部分后端专有字段（OpenAI 新模型支持，DeepSeek 等不支持）→ 切云端时清空
        boolean clearedEffort = false;
        String curEffort = getConfig().getString("llm.reasoning-effort", "");
        if (!local && curEffort != null && !curEffort.isBlank()) {
            patch.put("reasoning-effort", "");   // 空串：YAML 里写成 ''，PluginConfig 读到空白 → 不发该字段
            clearedEffort = true;
        }

        boolean written = persistLlmConfig(patch);
        reloadConfig();
        config = PluginConfig.load(getConfig());
        llmClient = new LLMClient(config);
        rebuildBehaviorModelClient();
        VillagerState.setMaxChatHistoryTokens(config.maxChatHistoryTokens());

        sender.sendMessage("§a✓ 已切换到 " + (local ? "§e本机" : "§b云端") + " §f" + config.llmModel());
        sender.sendMessage("  §7url: §f" + config.llmUrl());
        if (bumpedTimeout) sender.sendMessage("  §7云端后端：llm.read-timeout-ms 已自动放宽到 60000ms");
        if (clearedEffort) sender.sendMessage("  §7已清空 llm.reasoning-effort（部分云端 API 不支持该字段）");
        sender.sendMessage(written
                ? "§7已写回 plugins/MC-AI/config.yml，重启后依然生效"
                : "§c未能写回 config.yml（见控制台警告），当前仅在本次运行内生效");
        sender.sendMessage("§7用 §f/ai test §7验证连通性");
        getLogger().info("[MC-AI] LLM 后端切换(" + source + "): " + config.llmUrl()
                + " model=" + config.llmModel() + " 写回=" + written);
    }

    /**
     * 把 LLM 配置写回 plugins/MC-AI/config.yml。
     * <p>
     * 故意不用 Bukkit 的 saveConfig()：它会把整份 YamlConfiguration 重写、<b>注释全丢</b>，
     * 而 llm 段上方那段注释正是"各家 API 怎么填"的说明书 —— 丢了反而更难用。
     * 这里做段内行级替换：只改/插入指定 key，其余内容（含注释）原样保留。
     *
     * @return true = 已写回磁盘；false = 文件缺失或写入失败（调用方提示用户手改）
     */
    private boolean persistLlmConfig(java.util.Map<String, String> kv) {
        java.nio.file.Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            if (!java.nio.file.Files.exists(file)) return false;
            java.util.List<String> lines = new java.util.ArrayList<>(
                    java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8));

            // 1. 定位 llm: 段（第一个顶格的 "llm:" 行）
            int sectionStart = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("llm:")) { sectionStart = i; break; }
            }
            if (sectionStart < 0) return false;

            // 2. 段区间 = 到下一个"顶格非注释行"为止（缩进行 / 空行 / 顶格注释都算本段）
            int sectionEnd = lines.size();
            for (int i = sectionStart + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.isBlank() || l.startsWith(" ") || l.startsWith("\t") || l.trim().startsWith("#")) continue;
                sectionEnd = i;
                break;
            }

            // 3. 段内逐 key 替换（保留原缩进）
            java.util.Set<String> remaining = new java.util.LinkedHashSet<>(kv.keySet());
            for (int i = sectionStart + 1; i < sectionEnd; i++) {
                String l = lines.get(i);
                String trimmed = l.trim();
                for (String key : kv.keySet()) {
                    if (!remaining.contains(key) || !trimmed.startsWith(key + ":")) continue;
                    int indent = l.length() - l.stripLeading().length();
                    lines.set(i, " ".repeat(indent) + key + ": " + yamlScalar(kv.get(key)));
                    remaining.remove(key);
                    break;   // 一行只可能匹配一个 key
                }
            }

            // 4. 段里原本没有的 key → 补在段尾（缩进 2 空格，与其它键对齐）
            int insertAt = sectionEnd;
            for (String key : remaining) {
                lines.add(insertAt++, "  " + key + ": " + yamlScalar(kv.get(key)));
            }

            java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            getLogger().warning("写回 config.yml 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * YAML 标量：纯数字/布尔不加引号（加了引号 getInt 会读成默认值），
     * 其余一律单引号包裹（内部单引号按规范双写），避免 url 里的 ": / #" 被 YAML 当成结构；
     * 空串写成 {@code ''}（= 空字符串，而不是 null）。
     */
    private static String yamlScalar(String v) {
        String s = v == null ? "" : v;
        if (s.isEmpty()) return "''";
        if (s.matches("-?\\d+")) return s;                     // 数值配置项（如 read-timeout-ms）必须裸写
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return s;
        return "'" + s.replace("'", "''") + "'";
    }

    // ===== Getters =====

    public PluginConfig getPluginConfig() { return config; }
    public LLMClient getLlmClient() { return llmClient; }
    public BubbleManager getBubbleManager() { return bubbleManager; }
    public MemoryStore getMemoryStore() { return memoryStore; }
    public Map<UUID, VillagerState> getVillagers() { return villagers; }
    public com.howard.mcai.security.AuditLog getAuditLog() { return auditLog; }
    public com.howard.mcai.security.PermissionManager getPermissionManager() { return permissionManager; }
    public com.howard.mcai.security.WorldInteractionService getWorldInteraction() { return worldInteraction; }
    public CentralPlanner getPlanner() { return planner; }
    public GameStateProvider getGameStateProvider() { return gameStateProvider; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public ToolPolicyService getToolPolicyService() { return toolPolicyService; }
    public NpcTaskManager getNpcTaskManager() { return npcTaskManager; }
    public ToolDispatcher getToolDispatcher() { return toolDispatcher; }
    public LlmToolAdapter getLlmToolAdapter() { return llmToolAdapter; }
    public BehaviorModelClient getBehaviorModelClient() { return behaviorModelClient; }

    /**
     * 配置变更后重建行为模型客户端（/ai reload、/ai llm 切换后端时调用）。
     * 注册表不变，只换后端参数；解析器也跟着新 settings 走。
     */
    public void rebuildBehaviorModelClient() {
        if (toolRegistry == null) return;
        behaviorModelClient = new BehaviorModelClient(config.behaviorSettings(), toolRegistry);
    }
}
