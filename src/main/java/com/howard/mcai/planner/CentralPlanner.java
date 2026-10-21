package com.howard.mcai.planner;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.BehaviorRegistry;
import com.howard.mcai.ai.LLMClient;
import com.howard.mcai.ai.LLMResponse;
import com.howard.mcai.ai.LlmToolAdapter;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.ToolCall;
import com.howard.mcai.ai.TradeRegistry;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.mob.MobState;
import com.howard.mcai.mob.VillagerProfession;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.ToolRequest;
import com.howard.mcai.tool.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中央计划器 —— Paper tick loop 核心（SPEC 2.1 + 2.5）。
 * <p>
 * v2 架构：**心跳 + TickLayer 注册表**。
 * 主循环每 {@link TickLayer#HEARTBEAT_TICKS} tick（1 秒）心跳一次，把时间分发给所有已注册层；
 * 每层自己决定频率（表现层 3s / 社交层 60s），加新系统只需 registerLayer，不重构主循环。
 * <pre>
 * 表现层 PresentationLayer（3 秒/轮）：村民维护 + 唤醒扫描动物/僵尸 + 规则行为树 + 气泡
 * 社交层 SocialLayer（60 秒/轮）：八卦传话 + favor 衰减（玩家不在场也跑）
 * </pre>
 * <p>
 * LLM 调用保持**事件驱动**（玩家聊天/右键触发），不在 tick 里主动调 ——
 * 符合 SPEC 5 "事件驱动：无事件不决策，闲着发呆零调用"，也避免每 tick 批处理烧 token。
 *
 * 线程模型（关键！）：
 * <pre>
 * tick 触发（主线程）
 *   ├─ 各层 onTick（Paper API，主线程）
 *   ├─ 事件驱动（聊天/右键）→ 拼 messages（主线程）
 *   └─ 异步调 LLMClient.chat()（HTTP 在后台线程）
 *        ├─ thenAccept → CompletableFuture 回调（后台线程！）
 *        │    └─ runTask → 切回主线程执行 Paper API
 *        └─ exceptionally → 异常 fallback（切主线程）
 * </pre>
 *
 * 为什么要切回主线程？
 * Paper 的所有实体操作（addPassenger、setVelocity、openMerchant、Entity.remove 等）
 * 都必须在主线程调，否则会抛 IllegalThreadStateException。
 * AsyncHTTP 回调是在后台线程，所以必须用 runTask 切。
 */
public final class CentralPlanner extends BukkitRunnable {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final BubbleManager bubbleManager;

    /** 已注册的 tick 层（SPEC 2.5 注册表） */
    private final List<TickLayer> layers = new ArrayList<>();

    /** 对话注视任务（每 2 tick 让所有"正在对话"的村民持续面向玩家），onDisable 取消 */
    private BukkitTask faceTask;

    /**
     * 非村民生物状态注册表（动物/怪物）—— 表现层维护，Listener 经 mobStates() 拿引用改状态。
     * key = 生物实体 UUID。ConcurrentHashMap：攻击/喂食事件可能在任何线程触发。
     */
    private final Map<UUID, MobState> mobs = new ConcurrentHashMap<>();

    public CentralPlanner(McaiPlugin plugin,
                          Map<UUID, VillagerState> villagers,
                          BubbleManager bubbleManager) {
        this.plugin = plugin;
        this.villagers = villagers;
        this.bubbleManager = bubbleManager;
        // SPEC 2.5：注册当前两层。以后加经济层/世界事件层/演化层只需 registerLayer
        layers.add(new SocialLayer(plugin, villagers, bubbleManager));
        layers.add(new PresentationLayer(plugin, villagers, bubbleManager, mobs));
    }

    /** 注册新 tick 层（扩展点：新系统只需一层，不重构主循环） */
    public void registerLayer(TickLayer layer) {
        layers.add(layer);
    }

    /** 非村民生物状态注册表（监听器传引用，攻击/喂食事件直接改状态） */
    public Map<UUID, MobState> mobStates() {
        return mobs;
    }

    // ===== 对话注视（直视玩家） =====

    /**
     * 启动对话注视任务 —— 每 2 tick 把所有"正在对话"的村民转过去面向玩家。
     * 玩家走动时 NPC 会一直"看着你"，对话临场感更强。
     * 只对 interactingPlayer 非空的村民生效，对话结束自动不再处理。
     */
    public void startFaceTask() {
        if (faceTask != null) return;
        faceTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (VillagerState vs : villagers.values()) {
                    UUID pid = vs.interactingPlayer();
                    if (pid == null) continue;
                    Villager v = vs.entity();
                    if (!v.isValid()) continue;
                    Player p = plugin.getServer().getPlayer(pid);
                    if (p == null || !p.isOnline() || p.isDead()) continue;
                    faceTowards(v, p.getLocation());
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    /** 停止注视任务（onDisable 调用，防止关服后任务还在跑） */
    public void stopFaceTask() {
        if (faceTask != null) {
            faceTask.cancel();
            faceTask = null;
        }
    }

    /**
     * 让村民面向某个位置（只旋转身体，不移动）—— 对话时"直视玩家"。
     * MC 角度约定：yaw=0 朝 +Z，逆时针为正；pitch 负值朝上看、正值朝下看。
     * 用 setRotation 而不是 teleport+setDirection（teleport 有传送闪烁和掉线风险）。
     */
    public static void faceTowards(Villager v, Location target) {
        if (v == null || !v.isValid()) return;
        Location from = v.getLocation();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dist = Math.hypot(dx, dz);
        float pitch = (float) Math.toDegrees(Math.atan2(-(target.getY() - from.getY()), Math.max(dist, 0.01)));
        v.setRotation(yaw, pitch);
    }

    /**
     * 多 NPC 对话切换（关键！）：让玩家**同时只跟一个 NPC 保持对话**。
     * <p>
     * 多村民场景下，玩家右键 B（或走到 B 面前说话）时，A 的 interactingPlayer
     * 还挂着这个玩家 —— 不清掉会导致聊天路由在两个 NPC 之间随机选。
     * 此方法：先把玩家在其他 NPC 身上的对话状态清掉，再挂到 target 上。
     *
     * @param player 玩家
     * @param target 要开始对话的目标 NPC
     */
    public void switchConversation(Player player, VillagerState target) {
        UUID pid = player.getUniqueId();
        // 开口前先纠正职业：村民可能刚认领工作站（从"无业"变成真实职业），
        // 不刷新会导致回复口吻/GUI 状态卡与他真实职业对不上
        plugin.refreshVillagerProfession(target);
        for (VillagerState other : villagers.values()) {
            if (other != target && pid.equals(other.interactingPlayer())) {
                other.endConversation();
            }
        }
        target.startConversation(pid);
    }

    @Override
    public void run() {
        // 心跳：把这一秒分发给所有层，每层自己判断要不要执行本轮
        for (TickLayer layer : layers) {
            layer.heartbeat(this);
        }
        // 持续行动任务：推进、超时、清理已完成结果（统一在主线程心跳里跑）
        if (plugin.getNpcTaskManager() != null) {
            plugin.getNpcTaskManager().tick();
        }
    }

    /**
     * 玩家发来一条新消息（必须在主线程调用）。
     * <p>
     * LLM 忙时消息进积压队列而**不是直接入 chatHistory**——
     * 否则历史会变成 [user1, user2, assistant]，assistant 答的是 user1 却排在 user2 后面，
     * 而且续调时 prompt 以 assistant 结尾，违反 Jinja 交替铁律。
     * 积压消息等当前响应处理完后再入历史 + 续调，顺序就是干净的 [user1, assistant, user2]。
     */
    public void onPlayerMessage(VillagerState vState, Player player, String message) {
        onPlayerMessage(vState, player, message, false);
    }

    /** 动作栏对话提示：「💬 正在与 X 对话中 · 再右键一次结束」—— 开始对话与对话中发言时刷新 */
    public static void showConversationHint(Player player, VillagerState vState) {
        if (player == null || !player.isOnline() || vState.entity() == null) return;
        String text = "§e💬 正在与 §6" + com.howard.mcai.ai.Smalltalk.nameOf(vState)
                + " §e对话中 §7· 再右键一次结束";
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(text));
    }

    /** 清掉动作栏提示（结束对话时） */
    public static void clearConversationHint(Player player) {
        if (player == null || !player.isOnline()) return;
        player.sendActionBar(net.kyori.adventure.text.Component.empty());
    }

    /**
     * @param systemNote true = GUI 生成的系统说明，不是玩家亲口说的话。
     *                   这种消息必须跳过送礼/讨要/砍价/教学等句式解析 ——
     *                   说明文字里会引用"给你""拿去"作为示例，误当玩家发言会登记出莫名其妙的送礼请求。
     */
    public void onPlayerMessage(VillagerState vState, Player player, String message, boolean systemNote) {
        // 对话对象提示（Fong 实测需求）：多人围着时分不清在跟谁说话 ——
        // 正式对话中他每说一句，屏幕下方动作栏就亮一下当前对象（开始对话时也会亮，见交互监听）
        if (!systemNote && player != null && player.getUniqueId().equals(vState.interactingPlayer())) {
            showConversationHint(player, vState);
        }
        vState.onPlayerMessage();  // 重置对话超时
        // 见过这位玩家（首次见面判定改用 UUID 表，替代旧的字串匹配）
        vState.recordMeeting(player.getUniqueId(), player.getName());
        // 搭话本身就在积累交情：同一玩家 30 秒内只涨一次、每次 +1（防连发消息刷好感）。
        // 陌生关系从 0 起步，好感必须靠互动一点点攒出来，见 EmotionState.STRANGER_FAVOR
        if (vState.tryConsumeChatFavor(player.getUniqueId())) {
            vState.emotion().addFavor(player.getUniqueId(), 1.0);
            plugin.getMemoryStore().markDirty(vState.getUniqueId());
        }
        String effectiveMessage = message;
        if (!systemNote) {
            // 嘴上冒犯/威胁 → 代码层直接改好感/恐惧（不能指望模型"自觉生气"）
            applyVerbalTemper(vState, player, message);
            // 聊天砍价已退役（2026-10-21，交易界面路线二）：聊天里报数字不再改价，
            // 价格只在商店界面里由玩家点砍价按钮、插件守底线。这里只做识别 + 事实注入：
            // 让模型知道自己无权在聊天里答应价格，防止它嘴上许诺一个插件不会兑现的价。
            effectiveMessage = annotateBargainAttempt(vState, message);
            // 识别到砍价意图 → 后面不再按"讨东西"处理，避免"给我3个面包，1绿宝石"这种交易话被当成索要
            boolean bargained = !effectiveMessage.equals(message);
            // 教学意图检测 —— 玩家在"教"NPC 东西时，按好感/熟悉度/开放度判定这次信不信，
            // 把立场注入消息，让 NPC 台词语气（好奇接受 vs 半信半疑）和真实存储结果一致
            effectiveMessage = annotateTeaching(vState, player, effectiveMessage);
            // 上一轮"送礼待确认"的收口：只有玩家这一句明确应了，才真的把东西收下（见 annotateGift）
            boolean hadPendingGift = vState.pendingGift(player.getUniqueId()) != null;
            effectiveMessage = resolvePendingGift(vState, player, message, effectiveMessage);
            // 玩家递东西给 NPC —— 只登记、不扣货：命中送礼句式先请 NPC 问一句"真要给俺？"，
            // 等玩家亲口确认了才收。旧版直接扣货会闯祸：换物提议被砍一半执行 = 单方面没收玩家装备。
            // 刚处理完一笔待确认送礼的那一句不再登记新的一笔（"拿去"是答应，不是新提议）
            if (!hadPendingGift) effectiveMessage = annotateGift(vState, player, message, effectiveMessage);
            // 聊天里向 NPC 讨东西 —— 与 F 键 GUI 索要共用同一份耐心：好感不够也能靠反复磨让他松口
            if (!bargained) effectiveMessage = annotateBeg(vState, player, message, effectiveMessage);
        }
        if (vState.llmBusy()) {
            vState.enqueuePendingMessage(effectiveMessage);
            sendDebug(player, "§e[MC-AI] LLM 忙，消息已积压（当前回复后处理）");
            return;
        }
        vState.addChatEntry("user", effectiveMessage);
        triggerLLM(vState, player);
    }

    // ===== 嘴上冒犯 / 威胁（代码层情绪反馈） =====

    /**
     * 冒犯/无礼的说法 —— 玩家骂人、贬低 NPC 时好感下降。
     * <p>
     * 只用明确的人身攻击词，不收"蠢/傻"这类单字（"蠢蠢欲动""傻得可爱"会误伤），
     * 宁少勿滥：夸一个人不该被罚，骂一个人不该被漏。
     */
    private static final Pattern OFFEND_PATTERN = Pattern.compile(
            "蠢货|蠢驴|蠢死了|蠢得|笨蛋|白痴|弱智|废物|没用的东西|没用的家伙|讨厌你|"
                    + "滚开|滚蛋|滚远点|闭嘴|住口|恶心|丑八怪|臭东西|去死");

    /**
     * 威胁 —— 玩家放狠话时 NPC 恐惧上升。
     * <p>
     * 动词尽量要求紧跟"你"这类指向，避免"我要弄死僵尸""杀死这怪物"这种冲着怪物说的狠话
     * 被误判成吓唬村民；"屠村/杀光/灭村"这种冲着整座村子来的说法另外单独收录。
     */
    private static final Pattern THREAT_PATTERN = Pattern.compile(
            "杀了你|砍死你|砍了你|打死你|弄死你|宰了你|灭了你|揍你|烧了你|拆了你|拆了你家|"
                    + "打断你的腿|抢了你的|杀你全家|屠村|杀光|灭村|你等着|给我等着|让你好看|报复你");

    /** 一句冒犯话扣的好感（一次性，不做累加惩罚 —— 骂完这口气也就过去了） */
    private static final double OFFEND_FAVOR_HIT = -8.0;

    /** 被威胁时恐惧上升的幅度 —— 玩家放狠话，NPC 会真的怕 */
    private static final int THREAT_FEAR_HIT = 20;

    /**
     * 按玩家原话判定"这话有没有冒犯/威胁到这只 NPC"，并当场落到情绪状态上。
     * <p>
     * 为什么放代码层：模型多半会为了"脾气好"而当作没被冒犯，情绪反馈就不真实了。
     * 这里先按关键词改好感/恐惧，再让模型基于新的情绪状态开口说话，语气和数值前后一致。
     */
    private void applyVerbalTemper(VillagerState vState, Player player, String message) {
        boolean offended = OFFEND_PATTERN.matcher(message).find();
        boolean threatened = THREAT_PATTERN.matcher(message).find();
        if (!offended && !threatened) return;

        UUID pid = player.getUniqueId();
        if (offended) {
            // 被冒犯 → 好感下降（不写事实：一句气话不值得记一辈子，让日子慢慢冲淡）
            vState.emotion().addFavor(pid, OFFEND_FAVOR_HIT);
        }
        if (threatened) {
            vState.emotion().addFear(THREAT_FEAR_HIT);
            // 挨威胁是不能忘的事 —— 记一条只对该玩家可见的事实（日后对峙/记恨的依据）
            vState.addFact("旅者 " + player.getName() + " 威胁过我", pid);
        }
        plugin.getMemoryStore().markDirty(vState.getUniqueId());
        sendDebug(player, "§b[MC-AI] 言行情绪:"
                + (offended ? " 冒犯 好感" + String.format("%.0f", OFFEND_FAVOR_HIT) : "")
                + (threatened ? " 威胁 恐惧+" + THREAT_FEAR_HIT : ""));
    }

    // ===== 教学/学习机制 =====

    /** 教学意图关键词 —— 玩家在"教"东西的典型句式（宽松匹配，误报只多注入一条立场，无害） */
    private static final Pattern TEACH_PATTERN = Pattern.compile(
            "你知道吗|我告诉你|告诉你说|教.{0,3}你|听说|这个叫|记住.{0,2}(：|:)?|应该是|其实是|传说是|跟你说");

    /** 自我介绍句式 —— 玩家主动报名字/称呼（"我名字叫lanpz"），learn 参数为空时兜底提取 */
    private static final Pattern SELF_INTRO_PATTERN = Pattern.compile(
            "(?:我名字叫|俺叫|我叫|我的名字是|名字叫|人家叫|叫我|喊我|你叫我)([\\u4e00-\\u9fa5A-Za-z0-9_]{1,12})");

    /** 从最近一条 user 消息抓自我介绍内容（如"我名字叫lanpz" → "lanpz"），抓不到返回 null */
    private String extractSelfIntro(VillagerState vState) {
        var hist = vState.chatHistory();
        for (int i = hist.size() - 1; i >= 0; i--) {
            var e = hist.get(i);
            if (!"user".equals(e.role())) continue;
            Matcher m = SELF_INTRO_PATTERN.matcher(e.content());
            return m.find() ? m.group(1).trim() : null;
        }
        return null;
    }

    /**
     * 教学立场注入。
     * 玩家消息命中教学句式时：熟悉度 +1，按"开放度+熟悉度 vs 好感度"判定这次信不信，
     * 然后往给 LLM 的消息末尾注入立场说明 —— NPC 的台词语气会和 learn 工具的落地结果一致。
     */
    private String annotateTeaching(VillagerState vState, Player player, String message) {
        // 自我介绍：玩家主动报名字 —— 身份信息，NPC 顺嘴记住即可，不卡信任门槛
        if (SELF_INTRO_PATTERN.matcher(message).find()) {
            return message + "\n[Teaching note: the player just told you their name. "
                    + "Remember it naturally: react in Chinese, call the learn tool "
                    + "with their name as the content argument, and ALSO say a short spoken line out loud.]";
        }
        if (!TEACH_PATTERN.matcher(message).find()) return message;

        String playerName = player.getName();
        vState.recordTeachAttempt(playerName);  // 熟悉度 +1，下次门槛降低
        boolean accept = vState.wouldAcceptTeaching(playerName, player.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] 教学检测: " + playerName + " → " + vState.profession().zhName
                + " 判定=" + (accept ? "接受" : "怀疑")
                + " (openness=" + vState.openness() + " favor=" + String.format("%.0f", vState.emotion().favor(player.getUniqueId()))
                + " familiar=" + vState.teachFamiliarity(playerName) + ")");

        if (accept) {
            return message + "\n[Teaching note: the player is teaching you something new. "
                    + "You KNOW and TRUST this player well, and you're curious by nature. "
                    + "React with genuine interest in Chinese; if it's new useful knowledge, "
                    + "call the learn tool to remember it — fill its content argument with the knowledge, "
                    + "and ALSO say a short spoken line out loud. Never doubt them.]";
        }
        return message + "\n[Teaching note: the player is teaching you something new, "
                + "but you don't know them well enough to fully believe it (and you're stubborn). "
                + "React with polite doubt in Chinese — 半信半疑, ask where they heard it, "
                + "and do NOT call the learn tool this time.]";
    }

    // ===== 玩家递东西给 NPC（代码层真收货） =====

    /**
     * 明确的"把东西递过去"句式 —— 命中后可以直接按主手的物品收货。
     * <p>
     * 和 {@link #GIFT_GIVE_VERB} 分开的原因:"给你"能出现在"给你说个事"里，
     * 单靠它会把玩家手里的剑当成礼没收掉；而"拿去/拿着/收下"基本只在真递东西时出现。
     */
    private static final Pattern GIFT_HANDOVER_CUE = Pattern.compile(
            "拿去|拿着|收下|你拿着|给你吃|给你用|送你吃|送你用|送给你|给你这个|这个给你|这些都给你"
                    + "|都给你|全给你|这些给你|给你吧|请收下|请你收下");

    /** 泛化的给予词 —— 必须同时"点名了一件玩家背包里真有的物品"才成立（防"给你说个事"误伤） */
    private static final Pattern GIFT_GIVE_VERB = Pattern.compile("给你|送你|递给你|交给你");

    /** "都给"的说法 → 整叠给（否则只收 1 个，NPC 台词也常说"这块/这个"） */
    private static final Pattern GIFT_ALL_PATTERN = Pattern.compile(
            "都给你|全给你|这些都给你|这些给你|全部都|全都给|整个给你");

    /**
     * 玩家递东西 —— 命中送礼句式后<b>只登记一笔"待确认"，绝不直接扣货</b>。
     * <p>
     * 演变史：最初模型常只回一句"多谢，俺收下了"却不调 {@code take_item}（口惠而实不至），
     * 于是改成"代码层当场收货"。但那是个不可逆操作，句式一命中就把玩家背包里的东西拿走 ——
     * 实测出过事故：玩家说"把铁剑给我，下界合金剑给你"（换物提议），代码层只执行了玩家那一半，
     * 把合金剑没收，NPC 那一半却因好感不够被拒，玩家东西没了还讨不回来。
     * 现在改成两步：这一步只登记意向 + 请 NPC 问一句，等玩家下一句明确答应，
     * 才由 {@link #resolvePendingGift} 真正把东西收下。
     *
     * @param original 玩家原始消息（用来判断递东西句式、点名物品）
     * @param base     已附加其它说明的消息（本方法在末尾追加 Gift note）
     * @return 追加了 Gift note 的消息；没命中/没东西可提则原样返回
     */
    private String annotateGift(VillagerState vState, Player player, String original, String base) {
        // 换物提议（同一句里既"跟我要某件东西"又"把另一件给我"）→ 这是交易，不是单纯送礼。
        // 代码层对半执行的后果极其糟糕：玩家那一半被强制执行、NPC 那一半又被好感门槛拒掉，
        // 等于把玩家的装备单方面没收（实测玩家当场炸了）—— 所以这种整句一次都不碰。
        if (BEG_PATTERN.matcher(original).find() || BEG_ALL_PATTERN.matcher(original).find()) return base;

        boolean handoverCue = GIFT_HANDOVER_CUE.matcher(original).find();
        boolean giveVerb = GIFT_GIVE_VERB.matcher(original).find();
        if (!handoverCue && !giveVerb) return base;

        // 本条消息里点名的物品（玩家真有才算）—— "给你"这类泛化说法必须靠它才成立
        Material namedHere = findItemInText(player, original);
        // 本条的物品优先；没有就回看最近几条玩家消息（"我手里有西瓜片" → "你拿去吃"是两句话）
        Material named = namedHere != null ? namedHere : findItemInRecentChat(vState, player, original);
        if (!handoverCue && namedHere == null) return base;   // 只有"给你"且没点名物品 → 不动手

        Material mat = (named != null) ? named : mainHandOf(player);
        if (mat == null || mat.isAir()) return base;

        int count = GIFT_ALL_PATTERN.matcher(original).find()
                ? Math.min(64, Math.max(1, countInInventory(player, mat)))
                : 1;

        // === 只登记，绝不扣货 ===
        // 扣玩家的东西是不可逆操作，靠句式猜"他是不是真要给"迟早出事：
        // "我给你看看这把剑"/"下界合金剑给你，铁剑给我"都会被句式命中。
        // 所以这里只记下这笔意向，请 NPC 先问一句，等玩家下一句明确答应了才真正动手
        // （见 resolvePendingGift）—— 东西现在仍在玩家身上，什么都没发生。
        vState.setPendingGift(player.getUniqueId(), mat, count);
        String zh = com.howard.mcai.ai.PromptBuilder.materialToZh(mat);
        plugin.getLogger().info("§b[MC-AI] 送礼待确认: " + player.getName() + " → "
                + com.howard.mcai.ai.Smalltalk.nameOf(vState) + " " + mat.name() + " x" + count + "（尚未收取）");

        return base + "\n[Gift note: the player seems to be OFFERING you " + count + "×" + zh
                + " (" + mat.name() + "), but you have NOT taken anything — it is still in their hands. "
                + "Do NOT claim you already received it. Instead ask them to "
                + "confirm in ONE short Chinese line (e.g. 真要给俺？/ 真给俺？/ 可不兴反悔啊). "
                + "The item will only be handed over if they then say yes.]";
    }

    /**
     * 玩家确认收口 —— 上一轮 {@link #annotateGift} 登记的那笔"送礼待确认"在这里落地。
     * <p>
     * 只有玩家这一句<b>整句就是个短确认</b>（"嗯/好/给吧/拿去"…）才真的从他背包扣货；
     * 其他任何回复一律视为"他其实没打算给" —— 直接作废，什么都不发生。
     * 这道闸门是刻意的：宁可多问一句，也不能靠句式猜测去没收玩家的东西。
     */
    private String resolvePendingGift(VillagerState vState, Player player, String message, String base) {
        VillagerState.PendingGift pending = vState.pendingGift(player.getUniqueId());
        if (pending == null) return base;
        if (!isGiftConfirm(message)) {
            vState.clearPendingGift(player.getUniqueId());   // 没答应 → 作废（东西一直没被碰过）
            return base;
        }
        vState.clearPendingGift(player.getUniqueId());
        int taken = plugin.getWorldInteraction().takeFromPlayer(vState, player, pending.mat(), pending.count());
        String zh = com.howard.mcai.ai.PromptBuilder.materialToZh(pending.mat());
        if (taken <= 0) {
            return base + "\n[Gift note: the player said yes, but they no longer have any " + zh
                    + " to hand over. Just respond naturally in one short Chinese line.]";
        }
        vState.markGiftedFrom(player.getUniqueId(), pending.mat());    // 3 分钟内玩家可以要回去
        plugin.getLogger().info("§b[MC-AI] 玩家确认送礼: " + player.getName() + " → "
                + com.howard.mcai.ai.Smalltalk.nameOf(vState) + " " + pending.mat().name() + " x" + taken);
        sendDebug(player, "§b[MC-AI] 已收下 " + zh + " x" + taken);
        return base + "\n[Gift system note: the player just CONFIRMED the gift, so you have now physically "
                + "received " + taken + "×" + zh + " (" + pending.mat().name() + ") into your own inventory. "
                + "Say ONE short Chinese line thanking them.]";
    }

    /** 送礼确认词 —— 只认"整句就是一个短确认"的回复，避免把"好烦啊/那你也不能拿我东西啊"当成答应 */
    private static final java.util.Set<String> GIFT_CONFIRM_WORDS = java.util.Set.of(
            "嗯", "嗯嗯", "好", "好呀", "好的", "好吧", "好嘞", "行", "成", "中", "可以", "没问题",
            "是的", "对", "真的", "确定", "确认", "给", "给你", "给吧", "给你吧", "拿去", "拿去吧",
            "收下", "送你了", "给你了", "要", "可以给");

    private static boolean isGiftConfirm(String message) {
        if (message == null) return false;
        // 去掉空白与句末标点后再整句比对 —— "嗯"、"嗯！"、"给你吧。" 都算答应
        String m = message.trim().replaceAll("[\\s。，,、！!？?~～\\.]+", "");
        return GIFT_CONFIRM_WORDS.contains(m);
    }

    /** 在某段文字里找玩家背包中真有的物品（中文名长词优先；TradeRegistry 的映射表） */
    private Material findItemInText(Player player, String text) {
        for (String zhItem : SORTED_ZH_ITEMS) {
            if (!text.contains(zhItem)) continue;
            Material m = TradeRegistry.zhToMaterial(zhItem);
            if (m != null && countInInventory(player, m) > 0) return m;
        }
        return null;
    }

    /** 回看最近几条玩家消息里点名的物品（"我手里有西瓜片"→"你拿去吃"是两句话，得连起来看） */
    private Material findItemInRecentChat(VillagerState vState, Player player, String original) {
        var hist = vState.chatHistory();
        int scanned = 0;
        for (int i = hist.size() - 1; i >= 0 && scanned < 4; i--) {
            var e = hist.get(i);
            if (!"user".equals(e.role())) continue;
            Material m = findItemInText(player, e.content());
            if (m != null) return m;
            scanned++;
        }
        return null;
    }

    /** 玩家背包里某物品的总数 */
    private static int countInInventory(Player player, Material mat) {
        int n = 0;
        for (var st : player.getInventory().getContents()) {
            if (st != null && st.getType() == mat) n += st.getAmount();
        }
        return n;
    }

    /** 玩家主手物品（空手返回 null） */
    private static Material mainHandOf(Player player) {
        var hand = player.getInventory().getItemInMainHand();
        return (hand == null || hand.getType().isAir()) ? null : hand.getType();
    }

    // ===== 聊天里"讨东西"（与 GUI 索要共用耐心，好感不够也能磨） =====

    /**
     * 讨好/讨要句式 —— 玩家在聊天里缠着 NPC 要东西的说法。
     * <p>
     * 刻意不收"讨要/要点/想要"这类含糊词（GUI 注里就有"向你讨要"，会误伤），
     * 只收"给我/送我/求你"这种明确伸手的说法；且必须同时点名了 NPC 真有的东西才成立。
     * <p>
     * 注意别把"送你一个"收进来 —— 那是玩家<b>递给</b>NPC（见 {@link #annotateGift}），不是向 NPC 要。
     */
    private static final Pattern BEG_PATTERN = Pattern.compile(
            "给我|送我|分我|求你了|求求|求你|赏我|白给|讨一个|要一个|要个|能不能给"
                    // 要回自己刚送出去的东西也走这条路 —— 回收窗（见 annotateBeg）会让它直接物归原主，
                    // 不看感情分。玩家最自然的说法就是"还给我/把我的XX还我"，不加进来回收窗根本够不着
                    + "|还给我|还我|还回来|归还给我|还回来给我"
                    + "|可以给我|给点|给我点|给我些|行行好");

    /** "都给我/全给我"的说法 → 要 NPC 这一格的全部库存 */
    private static final Pattern BEG_ALL_PATTERN = Pattern.compile(
            "都给我|全给我|全都给我|全部给我|整个给我|这些都给我");

    /** 讨要的数量："给我3个面包" → 3 */
    private static final Pattern BEG_COUNT_PATTERN = Pattern.compile("(\\d+)\\s*(?:个|块|片|瓶|把|张|根|串|条)");

    /**
     * 台词里"明确答应把东西给出去"的说法 —— 用于把口头同意落成真实交付。
     * <p>
     * 前置否定 lookbehind 是为了避开"我给你说过""我告诉你"这类不含给予含义的句子；
     * 只收带给予含义的固定说法，宁可漏判（下一轮再说一次即可），不可误判白送。
     */
    private static final Pattern GIVE_CONSENT_PATTERN = Pattern.compile(
            "(?<!说)(?<!告诉)(?<!讲)(?<!教)给你(?:吧|了|一个|个|这|那|点|些)?"
                    + "|拿去吧|拿去|送你了|送给你|送你(?:俩|两个|一个|点|些)|拿着吧|拿着|收下吧|收下|归你了");

    /** 婉拒/否定说法 —— 命中就不算同意，避免"不给你"被当成"给你" */
    private static final Pattern GIVE_REFUSAL_PATTERN = Pattern.compile(
            "不给你|不给|没给你|没给|没拿|别想|想得美|下回|下次|不能给|不送|自己留着");

    private static boolean looksLikeGivingConsent(String text) {
        if (text == null || text.isBlank()) return false;
        if (GIVE_REFUSAL_PATTERN.matcher(text).find()) return false;
        return GIVE_CONSENT_PATTERN.matcher(text).find();
    }

    /**
     * 聊天里讨东西 —— <b>与 GUI 索要同一套规则</b>：好感只决定 NPC 的"态度参考"，
     * 交付等模型表态（调用 give_item 或台词明确答应）后才由代码层执行。
     * <p>
     * 为什么不再代码层直接交付：点击/开口的瞬间就把东西塞给玩家，会出现
     * "模型嘴上婉拒、东西却已经到手"或"模型答应、代码层掷骰没中"两种打脸。
     * 现在把表态权交给模型，代码层只负责把数量钉死并执行真实的给予接口。
     *
     * @param original 玩家原始消息（判句式 + 找物品）
     * @param base     已附加其它说明的消息（本方法在末尾追加索要说明）
     */
    /** 泛称食物：玩家讨"吃的"不点名具体食物时用（"给我点吃的，我饿"是最自然的讨法） */
    private static final Pattern GENERIC_FOOD_PATTERN = Pattern.compile("吃的|食物|干粮|吃食|饭食");

    private String annotateBeg(VillagerState vState, Player player, String original, String base) {
        if (!BEG_PATTERN.matcher(original).find() && !BEG_ALL_PATTERN.matcher(original).find()) return base;
        // 必须点名一件"NPC 库存里真有"的东西，否则可能只是"能不能给我讲讲"这类非物品请求
        Material mat = findRequestedItem(vState, original);
        // 没点名但讨的是泛称食物 → 从他库存里挑一样真有的吃的顶上：
        // 否则请求登记不上，模型只能自由发挥许诺（实测："送你俩面包，管饱！"实际一个没给）
        if (mat == null && GENERIC_FOOD_PATTERN.matcher(original).find()) {
            mat = findStockedFood(vState);
        }
        if (mat == null) return base;

        int stock = vState.stockOf(mat);
        if (stock <= 0) return base;
        int want = Math.min(parseBegCount(original, stock), stock);

        double fav = vState.emotion().favor(player.getUniqueId());
        // 裁决由插件当场算死（GiftPolicy：好感门槛按物品价值分档），不再掷骰子。
        // 结果钉进索要请求：模型只有表态权——裁决不给时，它嘴上答应了也交不出货
        // （交付环节会按请求里的 pluginApproved 拦截）。
        boolean reclaim = vState.consumeReclaim(player.getUniqueId(), mat);
        boolean envoy = plugin.getPermissionManager().rankOf(vState, player.getUniqueId())
                == com.howard.mcai.security.NpcRank.ENVOY;
        boolean willing = com.howard.mcai.security.GiftPolicy.willingToGive(
                fav, com.howard.mcai.security.PermissionManager.valueOf(mat), envoy, reclaim);
        // 钉死数量+裁决：GUI 那一套复用（模型把 count 写成一整叠也不算数）
        vState.setItemRequest(player.getUniqueId(), mat, want, willing);

        String zh = com.howard.mcai.ai.PromptBuilder.materialToZh(mat);
        String attitude = reclaim ? "这件东西本来就是他刚送你的，你愿意还给他"
                : (willing ? "关系不错，你愿意给" : "关系还不到位，你不太想给");
        plugin.getLogger().info("§b[MC-AI] 聊天讨要: " + player.getName() + " → "
                + com.howard.mcai.ai.Smalltalk.nameOf(vState) + " " + mat.name() + " x" + want
                + " 好感=" + String.format("%.0f", fav)
                + " 裁决=" + (reclaim ? "物归原主" : (willing ? "给(GiftPolicy达标)" : "不给(GiftPolicy未达门槛)"))
                + " → 等模型表态后交付");
        sendDebug(player, "§b[MC-AI] 讨要态度: " + (willing ? "愿意" : "不太愿意"));

        return base + "\n[Item request note: the player is asking for " + want + "×" + zh
                + " (" + mat.name() + ") out of your own stock. Your current attitude: " + attitude + ". "
                + "If you agree to give it, you MUST call `give_item` with item=\"" + zh + "\" and count=" + want
                + " — the game hands it over for real. "
                + "If your Chinese line explicitly agrees to give (for example \"给你\"/\"拿去\"/\"送你了\"/\"拿着\"), "
                + "the game will also execute the handover based on that agreement. "
                + "If you refuse, say so in one short Chinese line and do NOT promise anything. "
                + "You must actually SAY the line — not only call tools.]";
    }

    /**
     * 找出玩家这次在讨的东西：消息里点名的中文物品名 + NPC 库存里确实有。
     * <p>
     * 两步：① 拿映射表里的中文名（长词优先）去比对消息；② 再拿 NPC 库存里每件东西的中文名反向比对，
     * 覆盖映射表里没有的物品（不然"给我一个附魔金苹果"这种就永远认不出来）。
     */
    private Material findRequestedItem(VillagerState vState, String text) {
        for (String zhItem : SORTED_ZH_ITEMS) {
            if (!text.contains(zhItem)) continue;
            Material m = TradeRegistry.zhToMaterial(zhItem);
            if (m != null && vState.stockOf(m) > 0) return m;
        }
        for (var e : vState.inventorySnapshot().entrySet()) {
            int amt = e.getValue() == null ? 0 : e.getValue();
            if (amt <= 0) continue;
            Material m = Material.matchMaterial(e.getKey());
            if (m == null || m.isAir()) continue;
            String zh = com.howard.mcai.ai.PromptBuilder.materialToZh(m);
            if (zh != null && !zh.isBlank() && text.contains(zh)) return m;
        }
        return null;
    }

    /** 从 NPC 库存里挑一样能吃的：存量最多的优先（讨"吃的"时给他最实在的那样） */
    private Material findStockedFood(VillagerState vState) {
        Material best = null;
        int bestCount = 0;
        for (var e : vState.inventorySnapshot().entrySet()) {
            int amt = e.getValue() == null ? 0 : e.getValue();
            if (amt <= bestCount) continue;
            Material m = Material.matchMaterial(e.getKey());
            if (m == null || !m.isEdible()) continue;
            best = m;
            bestCount = amt;
        }
        return best;
    }

    /** 讨要的数量：显式数字 → "都给我" → 默认 1 */
    private static int parseBegCount(String msg, int stock) {
        Matcher m = BEG_COUNT_PATTERN.matcher(msg);
        if (m.find()) {
            try { return Math.max(1, Math.min(64, Integer.parseInt(m.group(1)))); } catch (NumberFormatException ignored) { }
        }
        if (BEG_ALL_PATTERN.matcher(msg).find()) return Math.max(1, stock);
        return 1;
    }

    /**
     * learn 工具落地：好感/门槛二次把关，通过才写进永久记忆（带出处）。
     *
     * @return true = 知识真的入了记忆；false = 被世界观铁律/信任门槛拦下或内容为空（没学成）。
     *         返回值供"承诺-执行一致性守卫"用：模型嘴上说"我记住了"但这里返回 false 时，
     *         那句承诺会被剥离，不再出现"答应了却没记住"的空头支票。
     */
    /**
     * 统一柜 NpcLearnTool 的入口：与对话路径共用同一套把关
     * （世界观铁律/信任门槛/强制出处/截断），避免两条路学出两种结果。
     *
     * @return true = 知识真的入了永久记忆
     */
    public boolean learnViaTool(VillagerState vState, com.google.gson.JsonObject args, Player player) {
        return handleLearn(vState,
                new ToolCall("learn", args == null ? new com.google.gson.JsonObject() : args), player);
    }

    private boolean handleLearn(VillagerState vState, ToolCall toolCall, Player nearPlayer) {
        String content = safeGetString(toolCall.arguments(), "content", null);
        if (content == null || content.isBlank()) {
            // 模型调了 learn 但没填 content 参数 → 从最近 user 消息兜底提取
            //（量化模型常见：只调工具不填参，尤其玩家说"我名字叫lanpz"时）
            content = extractSelfIntro(vState);
            if (content != null) {
                plugin.getLogger().info("§e[MC-AI] learn 参数为空，从对话提取自我内容: " + content);
            }
        }
        if (content == null || content.isBlank()) return false;
        // 防注入：learn 的内容要短，且不能是世界观违禁词
        String shortContent = content.length() > 40 ? content.substring(0, 40) : content;
        if (shortContent.contains("金币") || shortContent.contains("铜币") || shortContent.contains("银币")) {
            plugin.getLogger().info("§c[MC-AI] 拒绝 learn: 内容违反世界观铁律 — " + shortContent);
            return false;
        }

        String sourceName = nearPlayer != null ? nearPlayer.getName() : "村民";
        // 本回合是玩家自我介绍（报名字）→ 记住名字是身份信息，不卡信任门槛；
        // 只有"教知识"类学习才按 开放度+熟悉度 vs 好感度 把关
        if (extractSelfIntro(vState) == null
                && nearPlayer != null && !vState.wouldAcceptTeaching(sourceName, nearPlayer.getUniqueId())) {
            // LLM 违背立场说明硬要学 → 代码层兜底拒绝（嘴上附和，转头就忘 —— 也是真人的行为）
            plugin.getLogger().info("§c[MC-AI] learn 被门槛拦下: " + sourceName + " 还没取得 "
                    + vState.profession().zhName + " 的信任，知识不入记忆");
            return false;
        }

        // 玩家相关记忆打 owner = 该玩家，仅当事玩家可见（防跨玩家泄露）
        vState.addFact("听旅者" + sourceName + "说，" + shortContent,
                nearPlayer != null ? nearPlayer.getUniqueId() : null);
        if (nearPlayer != null) vState.emotion().addFavor(nearPlayer.getUniqueId(), 2);  // 教会东西，好感微涨
        plugin.getMemoryStore().markDirty(vState.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] 学到新知识（来自" + sourceName + "）: " + shortContent);
        return true;
    }

    // ===== 交易意图门卫 =====

    /** 交易意图关键词 —— 玩家最近说过这些词才允许 NPC 弹交易面板 */
    private static final Pattern TRADE_INTENT_PATTERN = Pattern.compile(
            "卖|买|价格|价钱|绿宝|便宜|贵|多少钱|来点|交易|商店|店铺|摊|收购|进货|上货|好东西|看看货");

    /**
     * 玩家最近 2 条 user 消息里是否谈到了交易。
     * 注意：砍价注释里含"绿宝石"，砍价后对话期间意图保持成立 —— 合理（本来就在谈交易）。
     */
    private boolean hasRecentTradeIntent(VillagerState vState) {
        var hist = vState.chatHistory();
        int checked = 0;
        for (int i = hist.size() - 1; i >= 0 && checked < 2; i--) {
            var e = hist.get(i);
            if ("user".equals(e.role())) {
                if (TRADE_INTENT_PATTERN.matcher(e.content()).find()) return true;
                checked++;
            }
        }
        return false;
    }

    // ===== 砍价关键词拦截 =====

    /**
     * 砍价关键词正则 —— 抓"数字+绿宝/绿宝石+物品名"。
     * 例子: "3绿宝一个金胡萝卜行不行" → group(1)="3"（价格）；
     * "1绿宝石3个面包" → group(1)="1"（价格在物品前也支持）。
     * 物品名不用 group(2)（会被数字截断），用 extractBargainItem 全文扫描。
     */
    private static final Pattern BARGAIN_PATTERN = Pattern.compile(
            "(\\d+)\\s*(?:绿宝|绿宝石|块钱|颗)\\s*(?:一个|一个|个)?\\s*([\\u4e00-\\u9fa5A-Za-z]+)");

    /** 单独找"数字+绿宝"的 fallback（物品在价格前面时 BARGAIN_PATTERN 可能抓不到） */
    private static final Pattern PRICE_FALLBACK_PATTERN = Pattern.compile("(\\d+)\\s*(?:绿宝|绿宝石)");

    /**
     * 砍价物品匹配表 —— 按**长度降序**排列。
     * 否则 "铁剑多少钱" 里的 "铁" 可能先于 "铁剑" 命中，把铁锭的价格给改了。
     */
    private static final List<String> SORTED_ZH_ITEMS = TradeRegistry.zhItemKeys().stream()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList();

    /**
     * 聊天砍价识别（只识别、不改价）—— 砍价已随交易界面路线二退役：
     * 价格只在商店界面里由 BargainPolicy + ShopSession 决定，聊天里报的数字一律不生效。
     * 命中「数字+绿宝+商品名」时给消息附加事实注入，防模型在台词里许诺价格。
     *
     * @return 命中返回附加了系统说明的消息；没命中返回原消息
     */
    private String annotateBargainAttempt(VillagerState vState, String message) {
        String zhItem = extractBargainItem(message);
        Integer price = extractBargainPrice(message);
        if (zhItem == null || price == null || price <= 0) return message;

        Material mat = TradeRegistry.zhToMaterial(zhItem);
        if (mat == null || mat == Material.EMERALD) return message;
        if (!TradeRegistry.professionCanSell(vState.profession(), mat)) return message;

        plugin.getLogger().info("§7[MC-AI] 聊天砍价（已退役，不改价）: " + zhItem + " → " + price + "绿宝，仅注入事实说明");
        return message + "\n[Shop system note: the player is trying to bargain IN CHAT. "
                + "Bargaining NO LONGER works in chat — prices change ONLY inside the shop UI, "
                + "where the player picks a cut amount and the system enforces your floor price. "
                + "You have NOT agreed to any new price. The current price of " + zhItem + " is unchanged. "
                + "Respond in Chinese: do NOT promise any discount or new price; "
                + "invite the player to open your shop and use the 砍价 button there.]";
    }

    /**
     * 从消息里提取砍价商品名（长词优先）。
     * <p>
     * 关键：**跳过"绿宝石"本身** —— 它是价格单位不是商品。
     * 之前 "1绿宝石3个面包" 会先命中 3 字的"绿宝石"→ 拿到 EMERALD → 守卫跳过，
     * 真正的商品"面包"永远轮不到，砍价静默失效。
     */
    private String extractBargainItem(String message) {
        for (String zhItem : SORTED_ZH_ITEMS) {
            if (!message.contains(zhItem)) continue;
            Material m = TradeRegistry.zhToMaterial(zhItem);
            if (m == Material.EMERALD) continue;  // 货币词不算商品（铁锭/金锭/钻石是真商品，不跳）
            return zhItem;
        }
        return null;
    }

    /** 从砍价消息里提取绿宝数 */
    private Integer extractBargainPrice(String message) {
        Matcher m = BARGAIN_PATTERN.matcher(message);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
        }
        // fallback：单独找"数字+绿宝"
        Matcher m2 = PRICE_FALLBACK_PATTERN.matcher(message);
        if (m2.find()) {
            try { return Integer.parseInt(m2.group(1)); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /**
     * 立即触发 LLM —— 右键/聊天事件入口（主线程调用）。
     *
     * 并发保护：AtomicBoolean llmInFlight，tryAcquire→release。
     * release 时机（关键）：必须在主线程 executeDecision 追加完 assistant 消息**之后**，
     * 否则窗口期内新消息会在 assistant 入历史前拼出连续 user → Jinja 500。
     */
    public void triggerLLM(VillagerState vState, Player player) {
        if (!vState.tryAcquireLLM()) {
            // 理论上只在"续调与新消息同 tick 抢锁"的竞态下发生；消息已在历史里，等当前响应自然续上
            sendDebug(player, "§e[MC-AI] LLM 忙，本次触发并入当前请求（chatHistory=" + vState.chatHistory().size() + "）");
            return;
        }

        try {
            triggerLLMInner(vState, player);
        } catch (Exception e) {
            // buildMessages()/chat() 若同步抛异常（如 prompt 拼装 bug），锁必须释放，
            // 否则该 NPC 的 llmInFlight 永远 true → 永久失声，只能重启服务器
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "[MC-AI] triggerLLM 同步异常，已释放 LLM 锁", e);
            vState.releaseLLM();
        }
    }

    /**
     * triggerLLM 的真实 body —— 抽成独立方法，让外层 try/catch 兜住同步异常。
     * 注意：异步回调里的异常仍走各自的 exceptionally/runTask 分支，与这里无关。
     */
    private void triggerLLMInner(VillagerState vState, Player player) {
        int histSize = vState.chatHistory().size();
        String lastRole = vState.chatHistory().isEmpty() ? "空" : vState.chatHistory().getLast().role();
        sendDebug(player, "§b[MC-AI] → 调 LLM chatHistory=" + histSize + " 最后role=" + lastRole);

        List<Map<String, String>> messages = PromptBuilder.buildMessages(vState, player);
        dumpMessages(vState, messages);

        // 动态获取 LLMClient —— /ai reload 换后端（llama-server ↔ Ollama）无需重启服务器
        plugin.getLlmClient().chat(messages, dialogToolSchemas())
                .thenAccept(response -> {
                    try {
                        String tool = response.hasToolCall() ? response.firstToolCall().name() : "无";
                        int contentLen = response.content() != null ? response.content().length() : 0;
                        sendDebug(player, "§b[MC-AI] ← LLM 响应 tool=" + tool + " contentLen=" + contentLen);
                        // LLM 调了工具但"正文空 + 工具 message 参数也空" → 追问一次补真实台词，
                        // 避免 NPC 完全沉默；只要任一位置有台词就正常走 executeDecision（不浪费第二次调用）
                        boolean contentBlank = response.content() == null || response.content().isBlank();
                        boolean toolHasText = false;
                        if (response.hasToolCall()) {
                            String toolMsg = safeGetString(response.firstToolCall().arguments(), "message", null);
                            toolHasText = toolMsg != null && !toolMsg.isBlank();
                        }
                        if (response.hasToolCall() && contentBlank && !toolHasText) {
                            retryForSpokenLine(vState, player, messages, response.firstToolCall());
                            return;
                        }
                        // 主线程执行 executeDecision（追加 assistant + 行为），release/续调也在主线程、排在其后
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                handleResponse(vState, response, player);
                            } finally {
                                vState.releaseLLM();
                                resumeBacklog(vState, player);
                            }
                        });
                    } catch (Exception e) {
                        // 任何异常都不能让 NPC 彻底沉默 —— 记录完整堆栈后降级走 executeDecision
                        //（正文/参数为空会如实显示 "..."，但至少不丢失这次回复）
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "LLM 响应处理异常，降级走 executeDecision", e);
                        try {
                            ToolCall fallbackTool = response.hasToolCall() ? response.firstToolCall() : null;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                try {
                                    handleResponse(vState,
                                            new LLMResponse(response.content(),
                                                    fallbackTool == null ? List.of() : List.of(fallbackTool)),
                                            player);
                                } finally {
                                    vState.releaseLLM();
                                    resumeBacklog(vState, player);
                                }
                            });
                        } catch (Exception ex) {
                            // runTask 调度失败（插件正在关闭）→ 释放锁防止卡死
                            vState.releaseLLM();
                        }
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("LLM 调用失败 — " + ex.getMessage());
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            vState.releaseLLM();
                            // 失败路径不走 executeDecision，历史停在刚才那条 user 上 ——
                            // 必须补一条 assistant 占位，否则下一条玩家消息拼成连续 user，
                            // 破坏 user↔assistant 严格交替（Jinja 500）。
                            // 占位只进历史不展示；原来的"（脑子里一片混乱...）"硬编码气泡
                            // 违反"禁止硬编码兜底台词"，已移除（失败时干脆不显示气泡）
                            appendAssistantPlaceholder(vState);
                            resumeBacklog(vState, player);
                        });
                    } catch (Exception e) {
                        vState.releaseLLM();
                    }
                    return null;
                });
    }

    /** 把 LLM 忙时积压的玩家消息放回历史并续调（主线程调用） */
    private void resumeBacklog(VillagerState vState, Player player) {
        // 每次释放 LLM 后统一收口：历史必须以 assistant 结尾。成功路径 executeDecision
        // 已追加（这里幂等跳过），兜底覆盖 LLM 失败 / handleResponse 中途抛异常等
        // "没走到 executeDecision"的路径
        appendAssistantPlaceholder(vState);
        List<String> backlog = vState.drainPendingMessages();
        if (backlog.isEmpty()) return;
        for (int i = 0; i < backlog.size(); i++) {
            // 严格交替铁律：积压 ≥2 条时条与条之间必须插一条 assistant 占位，
            // 否则历史出现 user,user 连续 → Jinja 500。
            // 占位 "..." 只维护序列结构、不展示给玩家，不算硬编码兜底台词
            if (i > 0) vState.addChatEntry("assistant", "...");
            vState.addChatEntry("user", backlog.get(i));
        }
        sendDebug(player, "§e[MC-AI] 处理积压消息 " + backlog.size() + " 条，续调...");
        // 同步续调而不是再 runTask 推迟一拍：releaseLLM 到重新 tryAcquire 之间若留一个
        // tick 空窗，玩家下一条消息会插进历史并抢先触发，又拼出连续 user
        triggerLLM(vState, player);
    }

    /**
     * 交替铁律兜底：历史最后一条不是 assistant 时补一条 "..." 占位。
     * <p>
     * 为什么需要：正常路径 executeDecision 无条件追加 assistant，但 LLM 失败路径
     * （triggerLLM / retryForSpokenLine 的 exceptionally）不走 executeDecision，历史会停在
     * user 结尾 —— 下一条玩家消息就构成连续 user，违反 user↔assistant 严格交替（Jinja 500）。
     * 占位只进 chatHistory，不冒气泡不发聊天框，不属于"硬编码兜底台词"。
     */
    private void appendAssistantPlaceholder(VillagerState vState) {
        var hist = vState.chatHistory();
        if (hist.isEmpty() || !"assistant".equals(hist.getLast().role())) {
            vState.addChatEntry("assistant", "...");
        }
    }

    /**
     * 空正文追问 —— LLM 调了工具却没输出台词时，在最后一条 user 消息后补一句
     * "请补台词"提示再问一次，让 NPC 的回复始终是 AI 原声（不硬编码兜底句）。
     * <p>
     * 不改 chatHistory（保持 user↔assistant 交替铁律）：只改本次 messages 数组里
     * 最后一条 user 的文本；追问仍返回空正文才透传 "..." 如实显示。
     */
    private void retryForSpokenLine(VillagerState vState, Player player,
                                    List<Map<String, String>> baseMessages, ToolCall originalTool) {
        // PromptBuilder 用 Map.of() 建的 map 是不可变的，put 会抛 UnsupportedOperationException
        // → 必须把每条消息包成可变 HashMap 副本才能追加"补台词"提示
        List<Map<String, String>> messages = new ArrayList<>(baseMessages.size());
        for (Map<String, String> m : baseMessages) {
            messages.add(m == null ? new java.util.HashMap<>() : new java.util.HashMap<>(m));
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                String orig = messages.get(i).get("content");
                messages.get(i).put("content", orig
                        + "\n[System: You just performed an action but forgot to say anything. "
                        + "Now reply with ONE short spoken line in Chinese (中文), as your persona, "
                        + "directly responding to the player's latest message. Keep it natural.]");
                break;
            }
        }
        plugin.getLlmClient().chat(messages, dialogToolSchemas())
                .thenAccept(retryResp -> {
                    String tool2 = retryResp.hasToolCall() ? retryResp.firstToolCall().name() : "无";
                    int contentLen2 = retryResp.content() != null ? retryResp.content().length() : 0;
                    sendDebug(player, "§b[MC-AI] ← LLM 追问 tool=" + tool2 + " contentLen=" + contentLen2);
                    // 追问若改了工具就用新工具（模型再次决策）；否则沿用原工具，只取新正文当台词
                    ToolCall effectiveTool = retryResp.hasToolCall() ? retryResp.firstToolCall() : originalTool;
                    LLMResponse effective = new LLMResponse(
                            retryResp.content(),
                            effectiveTool == null ? List.of() : List.of(effectiveTool));
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            handleResponse(vState, effective, player);
                        } finally {
                            vState.releaseLLM();
                            resumeBacklog(vState, player);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("LLM 追问失败 — " + ex.getMessage());
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                // 追问失败 → 仍按原工具走 executeDecision（正文为空 → 如实显示 "..."）
                                handleResponse(vState, new LLMResponse(null, List.of(originalTool)), player);
                            } finally {
                                vState.releaseLLM();
                                resumeBacklog(vState, player);
                            }
                        });
                    } catch (Exception e) {
                        vState.releaseLLM();
                    }
                    return null;
                });
    }

    /**
     * 调试输出 —— 先无条件写控制台日志；仅当 debug.enabled=true 时才发到玩家聊天框。
     * 正式服/单机玩家默认不再被蓝色调试行刷屏（排查时 /ai reload 打开开关即可）。
     */
    private void sendDebug(Player player, String msg) {
        plugin.getLogger().info(msg);
        if (plugin.getPluginConfig().debugEnabled() && player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(msg));
        }
    }

    /**
     * 调试用：把本次请求的完整 messages（system + 合并上下文 + 对话历史）按 role 分块原样打到控制台，
     * 供排查"模型到底收到了什么"。仅在 {@code debug.enabled=true} 时输出。
     * <p>
     * 刻意不做 JSON 转义、保留多行 —— 场景 JSON 是给模型看的，人也得能直接读懂，
     * 转义成 {@code \n} 反而看不清。system 提示词很长，但排查状态/环境是否真的注入就必须看全。
     */
    private void dumpMessages(VillagerState vState, List<Map<String, String>> messages) {
        if (!plugin.getPluginConfig().debugEnabled()) return;
        String name = com.howard.mcai.ai.Smalltalk.nameOf(vState);
        StringBuilder sb = new StringBuilder();
        sb.append("\n[MC-AI][PAYLOAD] → ").append(name).append("  messages=").append(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> m = messages.get(i);
            sb.append("\n----- [").append(i).append("] ").append(m.get("role")).append(" -----\n")
                    .append(m.get("content"));
        }
        plugin.getLogger().info(sb.toString());
    }

    /**
     * 处理 LLM 响应（主线程调用 —— triggerLLM 的 runTask 里）。
     * 校验 tool_call 并同步执行 executeDecision。
     *
     * Jinja constraint：messages 严格 user↔assistant↔user↔assistant 交替。
     * 所以 **每次 LLM 调用后 chatHistory 最后一条必须是 assistant** —— 这是硬约束！
     * executeDecision 末尾会无条件追加 assistant 消息。
     */
    private void handleResponse(VillagerState vState, LLMResponse response, Player nearPlayer) {
        // 注意：调用方已在主线程 runTask 里 —— 这里同步执行 executeDecision，
        // 保证外层 finally 的 releaseLLM 一定发生在 assistant 入历史之后
        ToolCall toolCall = null;
        if (response.hasToolCall()) {
            toolCall = response.firstToolCall();
            // 旧工具集（BehaviorRegistry 白名单）与统一注册表都接受：
            // 新增工具只注册即可被对话模型调用，不必再改这里
            boolean legacy = BehaviorRegistry.isValidTool(toolCall.name());
            boolean unified = isUnifiedTool(toolCall.name());
            if (!legacy && !unified) {
                plugin.getLogger().warning("LLM 返回了未注册的 tool: " + toolCall.name()
                        + " → fallback 显示 content");
                toolCall = null;
            }
        }
        executeDecision(vState, toolCall, nearPlayer, response);
    }

    /** 名字是否命中统一工具注册表（规范名 npc.move_to 与 wire 名 npc_move_to 都算） */
    private boolean isUnifiedTool(String name) {
        LlmToolAdapter adapter = plugin.getLlmToolAdapter();
        return adapter != null && adapter.isRegisteredTool(name);
    }

    /** 该统一工具是否动作类（失败原因需要同步给玩家；查询类失败不打扰玩家） */
    private boolean isActionTool(String name) {
        if (plugin.getToolRegistry() == null) return false;
        var tool = plugin.getToolRegistry().find(name);
        return tool != null && tool.category() == com.howard.mcai.tool.NpcTool.Category.ACTION;
    }

    /**
     * 对话模型可见工具集 = 旧对话工具 + 统一注册表中的只读查询工具。
     * 只加查询不加行动：移动/改世界仍走旧白名单或行为模型，避免绕过对话站立约束。
     */
    private List<Map<String, Object>> dialogToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>(BehaviorRegistry.toolSchemas());
        LlmToolAdapter adapter = plugin.getLlmToolAdapter();
        if (adapter != null) {
            schemas.addAll(adapter.dialogSchemas());
        }
        return schemas;
    }

    /**
     * 把对话模型的统一工具调用交给 Dispatcher（主线程）。
     * <p>
     * 参数校验、权限、幂等、任务创建全部在 Dispatcher 内完成，这里只负责提供可信上下文，
     * 模型输出中的任何字段都不会变成 callerType。
     */
    private ToolResult dispatchUnifiedTool(VillagerState vState, ToolCall toolCall, Player nearPlayer) {
        LlmToolAdapter adapter = plugin.getLlmToolAdapter();
        if (adapter == null || plugin.getToolDispatcher() == null) return null;

        String callerId = nearPlayer != null ? nearPlayer.getName() : "dialog";
        ToolRequest request = adapter.toRequest(toolCall, CallerType.DIALOG_MODEL, callerId,
                vState.getUniqueId(), nearPlayer == null ? null : nearPlayer.getUniqueId());
        if (request == null) return null;

        ToolResult result = plugin.getToolDispatcher().dispatchSync(request);
        plugin.getLogger().info("§b[MC-AI] 统一工具 " + request.tool() + " → " + result.status()
                + (result.success() ? "" : " " + result.errorCode() + ": " + result.errorMessage()));
        return result;
    }

    /**
     * 给玩家看的失败原因 —— 模型传参错误（INVALID_ARGUMENT）是模型自己的事，
     * 原始校验文本（「缺少必填参数: message」这类开发术语）只进日志，绝不发玩家；
     * 玩家只得一句人话兜底。其余失败（插件规矩/权限）的原因照常透传，那是玩家该知道的事实。
     */
    private static String playerFacingReason(ToolResult result, String fallback) {
        if (result == null || ToolResult.ErrorCode.INVALID_ARGUMENT.name().equals(result.errorCode())
                || result.errorMessage() == null || result.errorMessage().isBlank()) {
            return fallback;
        }
        return result.errorMessage();
    }

    /**
     * Minecraft 世界观关键词过滤器 —— 防串戏 guardrail（硬约束）。
     * <p>
     * 四层检查：
     * 1. 货币铁律（铜币/银币/金币）—— 所有职业通用
     * 2. RPG 串戏（魔法/公会/精灵 等非 MC 元素）—— 注意牧师/龙/龙息/城堡是原版 MC 元素，不拦
     * 3. 职业身份串角 —— 只挡"自称是别的职业/幻想身份"（v2：不再拦话题）
     * 4. 轻微违规修正（AI/游戏/服务器 改成更自然的说法）
     */
    /** 幻想身份自称兜底（村民不该自称的身份）—— 不跟 rpGimmicks 重复 */
    private static final String[] FANTASY_IDENTITIES = {"冒险家", "猎人", "贵族", "佣兵", "勇士"};

    /**
     * 第四面墙词（拉丁）：必须整词匹配。
     * 旧版用 lower.contains("ai")，会误伤所有含 ai 的英文词；更糟的是
     * .replace("AI","俺") 大小写敏感地啃掉大写单词里的 AI（如 "TRAIN"→"TR俺N"）。
     * 中文词（人工智能/游戏/服务器/模组）无此问题，保持 substring 匹配。
     */
    private static final Pattern META_TERM = Pattern.compile("(?i)\\b(ai|system|bug|mod)\\b");

    /** "我是X"/"俺是X"/"我是干X"/"就是X" 自称句式判定（去空格后匹配，单纯提到不算） */
    private static boolean isProfessionClaim(String noSpaceDialogue, String name) {
        return noSpaceDialogue.contains("我是" + name) || noSpaceDialogue.contains("俺是" + name)
                || noSpaceDialogue.contains("我是干" + name) || noSpaceDialogue.contains("就是" + name);
    }

    static String filterWorldLore(String dialogue, VillagerProfession profession) {
        if (dialogue == null || dialogue.isBlank()) return dialogue;

        String lower = dialogue.toLowerCase();

        // === 层级 1：货币铁律（所有职业通用） ===
        if (lower.contains("铜币") || lower.contains("银币") || lower.contains("金币")
                || lower.contains("铜板") || lower.contains("银圆") || lower.contains("金元宝")) {
            return "啥钱不钱的！俺这只有绿宝石、铁锭、金锭、钻石！";
        }

        // === 层级 2：RPG 串戏（所有职业通用） ===
        // 注意：'牧师'（村民职业）、'龙'（末影龙）、'龙息'（MC 物品）是**原版 MC 元素**，不能拦！
        // 玩家聊末影龙/牧师职业都完全合理，整句打回 = 新鲜话题永远被"不太懂"噎死
        String[] rpGimmicks = {"魔法卷轴", "卷轴", "公会", "精灵", "矮人", "法师",
                "骑士", "盗贼", "吟游诗人", "国王", "女王",
                "法师塔", "魔法", "咒语", "施法", "魔力", "蓝条", "红条", "蓝瓶", "红瓶",
                "经验值", "等级", "升级", "技能点", "属性点"};
        for (String gimmick : rpGimmicks) {
            if (dialogue.contains(gimmick)) {
                return "这个我也不太懂，俺只是个" + profession.zhName + "。";
            }
        }

        // === 层级 3：职业身份串角（软引导，不再硬打回话题） ===
        // 旧版用职业专属 forbiddenTopics 拦截话题，NPC 变成"职业铁皮人"。
        // v2：只挡"自称是别的职业/幻想身份"的说法；正常聊别行内容交给 system prompt 引导
        // （本行精通、别行懂皮毛可以聊）。身份清单遍历所有职业枚举 + 幻想身份兜底。
        String lower2 = dialogue.replace(" ", "");
        for (VillagerProfession other : VillagerProfession.values()) {
            if (other == profession) continue;
            if (isProfessionClaim(lower2, other.zhName)) {
                return "哎你可别埋汰俺了，俺只是个" + profession.zhName + "。";
            }
        }
        for (String fantasy : FANTASY_IDENTITIES) {
            if (isProfessionClaim(lower2, fantasy)) {
                return "哎你可别埋汰俺了，俺只是个" + profession.zhName + "。";
            }
        }

        // === 层级 4：轻微违规修正（AI/游戏机制 → 改成更自然的 MC 说法） ===
        if (META_TERM.matcher(dialogue).find()
                || lower.contains("人工智能") || lower.contains("游戏") || lower.contains("版本")
                || lower.contains("服务器") || lower.contains("模组")) {
            dialogue = META_TERM.matcher(dialogue).replaceAll("俺")
                    .replace("人工智能", "俺")
                    .replace("游戏", "村里事")
                    .replace("服务器", "村子")
                    .replace("模组", "手艺");
        }

        // === 层级 5：英文 MC 术语 → 中文兜底 ===
        // prompt 里说要用中文，但 7b 小模型偶尔还是混英文，代码层做最后替换
        dialogue = dialogue
                .replaceAll("(?i)emerald", "绿宝石")
                .replaceAll("(?i)diamond", "钻石")
                .replaceAll("(?i)iron ingot", "铁锭")
                .replaceAll("(?i)gold ingot", "金锭")
                .replaceAll("(?i)iron block", "铁块")
                .replaceAll("(?i)gold block", "金块")
                .replaceAll("(?i)diamond block", "钻石块")
                .replaceAll("(?i)emerald block", "绿宝石块")
                .replaceAll("(?i)creeper", "苦力怕")
                .replaceAll("(?i)skeleton", "骷髅")
                .replaceAll("(?i)zombie", "僵尸")
                .replaceAll("(?i)enderman", "末影人")
                .replaceAll("(?i)spider", "蜘蛛")
                .replaceAll("(?i)villager", "村民")
                .replaceAll("(?i)player", "玩家")
                // 必须匹配完整单词 "minecraft" —— 之前用 "minecraf" 前缀匹配，
                // "Minecraft" 被替换后残留尾部的 "t"（日志里的"我的世界t"就是这么来的）
                .replaceAll("(?i)minecraft", "我的世界");

        return dialogue;
    }

    /**
     * 肢体语言 —— 工具动作 → 可见的身体姿态。
     * 村民没有手部动画 API，用 setVelocity 的物理小动作模拟：
     * wave=起跳（挥手兴奋感）、nod=朝前小跳（点头）、shake_head=右→左小跳（甩头）。
     * 对话期间 AI 被 setAI(false) 冻结，但 setVelocity 是纯物理，依然生效。
     */
    private void playGesture(Villager v, String action) {
        if (v == null || !v.isValid()) return;
        switch (action) {
            case "wave" -> v.setVelocity(new Vector(0, 0.45, 0));
            case "nod" -> {
                var dir = v.getLocation().getDirection().normalize().multiply(0.3);
                v.setVelocity(new Vector(dir.getX(), 0.25, dir.getZ()));
            }
            case "shake_head" -> {
                // 先右后左，模拟左右甩头（第二跳延迟 3 tick，动作连贯）
                v.setVelocity(new Vector(0.25, 0.2, 0));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (v.isValid()) v.setVelocity(new Vector(-0.25, 0.2, 0));
                }, 3L);
            }
            default -> { /* say/idle/move 等无肢体动作 */ }
        }
    }

    /**
     * 台词统一清洗 —— 世界观 guardrail + 长度收敛。
     * executeDecision 与 NeedsLayer（自主行动）共用，保证所有对外台词同一套过滤规则。
     */
    static String sanitizeDialogue(String dialogue, VillagerProfession profession) {
        // 先统一走 LLMClient 的剥离逻辑 —— 覆盖 [动作:…] / tool_call XML / 裸 JSON /
        // Python 调用 / **action**: "say" 这类伪工具调用，防任何调用语法漏进气泡和聊天框。
        if (dialogue != null) dialogue = LLMClient.stripToolCalls(dialogue);
        // 再兜一层 [动作:<摘要>]（stripToolCalls 已剥，这里保留是为了下面对 null 的处理顺序不变）
        if (dialogue != null) dialogue = dialogue.replaceAll("\\[动作:[^\\]]*\\]", "");
        // 「（未成功）」是写给历史摘要的内部成败标记，绝不能出现在台词里 ——
        // 模型偶尔会把历史里的标记回声成台词（实测村民直接回一句「（未成功）」），出口再兜一层
        if (dialogue != null) dialogue = dialogue.replace("（未成功）", "").trim();
        String cleaned = filterWorldLore(dialogue, profession);
        if (cleaned == null || cleaned.isBlank()) return cleaned;
        // Markdown 加粗 **xxx** → MC 的加粗色码：模型爱用 ** 强调，原样显示就是一串裸星号，
        // 气泡里看着像乱码。转成 §l，结尾补回 §f 白色（正文默认色），不影响后续文字
        cleaned = cleaned.replaceAll("\\*\\*([^*]+)\\*\\*", "§l$1§f");
        // 长度收敛：110 字 ≈ 气泡最长 40 秒能读完的量（3 + 每 3 字 1 秒）。
        // 超过 110 字才收敛，且**只在句末标点处切** —— 绝不能腰斩半句话。
        // 旧版是在 80 字硬切，模型写了 96 字就会被砍掉后半句，玩家看到的就是"话没说完就没了"。
        if (cleaned.length() <= MAX_DIALOGUE_CHARS) return cleaned;
        String cut = cleaned.substring(0, MAX_DIALOGUE_CHARS);
        int last = Math.max(Math.max(cut.lastIndexOf('。'), cut.lastIndexOf('！')),
                Math.max(cut.lastIndexOf('？'), cut.lastIndexOf('…')));
        return last > 20 ? cut.substring(0, last + 1) : cut + "…";
    }

    /** 台词长度上限 —— 与气泡 40 秒显示上限配套（约 3 字/秒的可读量） */
    private static final int MAX_DIALOGUE_CHARS = 110;

    // ===== 承诺-执行一致性守卫（②）=====

    /** 移动类工具 —— 对话中 NPC 的腿被锁住，这几个工具即使被调用也不会真正执行 */
    private static final Set<String> MOVEMENT_TOOLS = Set.of("flee", "move_to", "follow_player", "find_shelter");

    /** 按句末标点切句（保留标点）—— 承诺守卫逐句判定，只删中招的那一句，不腰斩半句话 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[^。！？；…!?;]*[。！？；…!?;]");

    /** 承诺交付物件："我这就给你X" / "你拿去" / "送你了" */
    private static final Pattern PROMISE_GIVE = Pattern.compile(
            // 跨度放宽到 14 字且允许中间一个逗号：实测台词"俺这儿有现成的面包，送你俩"
            // 中间隔着 8 字 + 逗号，旧版 {0,8} 且禁逗号的窗口正好卡死 → 空头承诺漏过守卫
            "(?:我|俺|咱)[^。！？；]{0,14}(?:给你|送你|拿给你|递给你)"
                    + "|(?:你拿去|你拿着|拿去用|送你了|归你了)");

    /**
     * 承诺马上动身："我这就过去" / "我马上过来" / "我这就去找你"。
     * <p>
     * 尾部的动词必须真的是"朝玩家移动"——旧版把裸 "去/走" 也算承诺，导致
     * "我这就去把炉子灭了""我马上走去拿料"这类**和玩家无关**的句子被整句删掉，
     * 若整条回复只有这一句，就会静默变成 "..."（实测玩家收到空回复的根因）。
     */
    private static final Pattern PROMISE_MOVE = Pattern.compile(
            "(?:我|俺|咱)[^，。！？；]{0,6}(?:这就|马上|立刻|待会儿|等下|回头|一会儿)"
                    + "[^，。！？；]{0,6}(?:过去|过来|去找你|找你|陪你|跟着你|跟你走|去你那|到你那儿|到你那)");

    /** 承诺记住："我记住了" / "我记下了" */
    private static final Pattern PROMISE_REMEMBER = Pattern.compile(
            "(?:我|俺|咱)[^，。！？；]{0,4}(?:记住|记住了|记下了|记着了|记牢了)");

    /** 价格让步用语 —— "给你便宜点""给你打个折"谈的是价格不是交付实物，不归承诺守卫管 */
    private static final Pattern PRICE_TALK = Pattern.compile("便宜|打折|优惠|少收|降价|算你");

    /**
     * 承诺-执行一致性守卫 —— 台词里的"第一人称承诺"必须由本轮真实 tool_call 兑现。
     * <p>
     * 为什么需要：模型很爱说"行，这袋面包你拿去""我这就过去""我记住了"，但这些多数只是
     * **纯文本**。没有对应工具调用时它们就是空头支票 —— 玩家看到 NPC 亲口保证了却什么都没发生，
     * 这正是"AI 光说不做"最主要的来源（比模型编造世界设定/天气更常见）。
     * <p>
     * 处理方式：按句末标点切句，命中"未兑现的承诺句"就**整句删掉**。绝不做任何文本替换 ——
     * 项目硬约束禁止硬编码兜底台词，脚本全被删空时由调用方透传为 "..."。
     * <p>
     * 特别地：**对话中的移动承诺永远算未兑现**。代码层此时冻结了 NPC 的移动，
     * 连 move_to 也不会真的走（见 executeDecision 里的"站立不动"约束）。
     *
     * @param vState        当前村民状态（用来判定"是否正在对话"）
     * @param dialogue      已过 sanitizeDialogue 的台词
     * @param toolCall      本轮真实执行的工具调用（null = 本轮什么都没执行）
     * @param reallyGave    本轮 give_item 是否**真的把东西交出去了**（被好感/库存门槛拒 = false）
     * @param reallyLearned 本轮 learn 是否**真的写进了永久记忆**（被信任门槛拒 = false）
     * @return 剥掉空头承诺后的台词（可能为空串）
     */
    private String stripUnbackedPromises(VillagerState vState, String dialogue, ToolCall toolCall,
                                         boolean reallyGave, boolean reallyLearned) {
        if (dialogue == null || dialogue.isBlank()) return dialogue;
        String tool = toolCall == null ? null : toolCall.name();
        // 关键：reallyGave/reallyLearned 一律由调用方按**真实执行结果**传入 ——
        // 绝不能按"模型调过这个工具"就当作兑现了。give_item 会被好感/库存门槛拒、
        // learn 会被信任门槛拒，模型调了 ≠ 事情做成了（实测：好感 1 < 门槛 40 被拒，台词却照样承诺给苹果）。
        // 对话中"腿被锁着"，移动永远没兑现；只有非对话状态下的移动类工具才算真动过。
        boolean reallyMoved = vState.interactingPlayer() == null && tool != null
                && ("move_to".equals(tool) || "follow_player".equals(tool) || "flee".equals(tool)
                    || "teleport".equals(tool) || "teleport_to".equals(tool));

        StringBuilder kept = new StringBuilder(dialogue.length());
        Matcher m = SENTENCE_SPLIT.matcher(dialogue);
        int cursor = 0;
        while (m.find()) {
            String sentence = dialogue.substring(cursor, m.end());
            cursor = m.end();
            if (isUnbackedPromise(sentence, reallyGave, reallyMoved, reallyLearned)) continue;
            kept.append(sentence);
        }
        // 最后一段可能没有句末标点（模型常以"…"或直接收尾），单独判定，不能漏掉
        if (cursor < dialogue.length()) {
            String tail = dialogue.substring(cursor);
            if (!isUnbackedPromise(tail, reallyGave, reallyMoved, reallyLearned)) kept.append(tail);
        }
        return kept.toString().trim();
    }

    /** 单句判定：命中"未兑现的承诺"返回 true */
    private static boolean isUnbackedPromise(String sentence, boolean reallyGave,
                                             boolean reallyMoved, boolean reallyLearned) {
        if (!reallyGave && !PRICE_TALK.matcher(sentence).find()
                && PROMISE_GIVE.matcher(sentence).find()) {
            return true;
        }
        if (!reallyMoved && PROMISE_MOVE.matcher(sentence).find()) {
            return true;
        }
        if (!reallyLearned && PROMISE_REMEMBER.matcher(sentence).find()) {
            return true;
        }
        return false;
    }

    /**
     * 执行决策（主线程）。
     */
    private void executeDecision(VillagerState vState, ToolCall toolCall, Player nearPlayer, LLMResponse response) {
        Villager villager = vState.entity();

        // 见到玩家即登记"见过"（结构化 metPlayers，替代旧的子串匹配判定）——
        // needs 驱动的自主行动也会走到这里，补上 onPlayerMessage 之外的登记点
        if (nearPlayer != null) {
            vState.recordMeeting(nearPlayer.getUniqueId(), nearPlayer.getName());
        }

        // === 第一步：提取台词 ===
        // 优先级：tool 的 message 参数 → response.content。
        // LLM 一句话没说 → 透传 "..." 如实展示，绝不拿硬编码兜底句顶替 AI 回复
        //（空正文场景 triggerLLM 已追问过一次让 AI 补台词，能到这里还是空的概率很低）
        String dialogue = null;
        if (toolCall != null) {
            dialogue = safeGetString(toolCall.arguments(), "message", null);
        }
        if (dialogue == null || dialogue.isBlank()) {
            dialogue = (response.content() != null && !response.content().isBlank())
                    ? response.content()
                    : null;
        }
        if (dialogue == null || dialogue.isBlank()) {
            dialogue = "...";
        }

        // === 世界观 guardrail（硬约束！） ===
        // 防止 LLM 幻觉出 RPG 元素/职业串戏，命中就替换成 MC 合规台词
        String originalDialogue = dialogue;
        dialogue = sanitizeDialogue(dialogue, vState.profession());
        if (!dialogue.equals(originalDialogue)) {
            plugin.getLogger().info("世界观修正 [" + vState.profession().zhName + "]: ["
                    + originalDialogue + "] → [" + dialogue + "]");
        }

        // === 第二步半：先把"带门槛的动作"真做掉，拿到真实成败 ===
        // 为什么必须提到台词守卫之前：模型"调了工具"和"事情真做成了"是两回事 ——
        // give_item 会被好感/库存门槛拒（实测日志：好感 1 < 门槛 40 → 拒绝），learn 会被信任门槛拒。
        // 若先按"模型调过工具"就放行台词，就会出现"嘴上说'给，这是俺存的最后一个好苹果，你拿着'，
        // 玩家背包里却啥也没有"。提前执行 → 拿到真实结果 → 承诺守卫按结果剥离空头支票 → 最后才开口。
        String gatedReject = null;      // 非 null = 该动作被门槛拒了，值是给玩家看的原因
        boolean reallyGave = false;     // give_item 是否真的把东西交出去了
        boolean reallyLearned = false;  // learn 是否真的写进了永久记忆
        ToolResult preUnifiedResult = null;  // give_item 在第二步半经统一分发的结果（第三步复用，禁止重复执行）
        if (toolCall != null) {
            switch (toolCall.name()) {
                case "give_item" -> {
                    // give_item 已迁入统一柜：经 ToolDispatcher 执行（GiftPolicy 确定性裁决），
                    // 结果带回给承诺守卫；注册表不可用时才回退老后门（迁移期保险丝，正常不会触发）
                    if (isUnifiedTool("give_item")) {
                        preUnifiedResult = dispatchUnifiedTool(vState, toolCall, nearPlayer);
                        reallyGave = preUnifiedResult != null && preUnifiedResult.success();
                        gatedReject = reallyGave ? null
                                : "§e" + playerFacingReason(preUnifiedResult, "这次没能把东西给你");
                    } else {
                        // 统一柜不可用（注册表未就绪）：宁可明确拒绝，也绝不假报"已送出"
                        //（老后门 execute 入口已退役，再走它只会拿到 null 被误判成成功）
                        gatedReject = "§e系统繁忙，这次没能把东西给你";
                        reallyGave = false;
                    }
                }
                case "accept_swap" -> {
                    // 玩家在背包界面右键发起的交换：双方物品/数量在点击时已由代码层登记。
                    // 已迁入统一柜：值不值由 SwapPolicy 裁决，模型只有表态权；结果带回给承诺守卫。
                    if (isUnifiedTool("accept_swap")) {
                        preUnifiedResult = dispatchUnifiedTool(vState, toolCall, nearPlayer);
                        reallyGave = preUnifiedResult != null && preUnifiedResult.success()
                                && preUnifiedResult.data() != null
                                && preUnifiedResult.data().has("swapped")
                                && preUnifiedResult.data().get("swapped").getAsBoolean();
                        gatedReject = (preUnifiedResult != null && !preUnifiedResult.success())
                                ? "§e" + playerFacingReason(preUnifiedResult, "交换没能完成")
                                : null;
                    } else {
                        var swap = plugin.getWorldInteraction().swapWithPlayer(vState, nearPlayer);
                        reallyGave = swap.success();
                        gatedReject = swap.error();
                    }
                }
                case "learn" -> {
                    // learn 已迁入统一柜：在第二步半经统一分发执行（把关与旧路径同一套，
                    // 见 learnViaTool），结果带回给承诺守卫；注册表不可用时回退旧路径
                    if (isUnifiedTool("learn")) {
                        preUnifiedResult = dispatchUnifiedTool(vState, toolCall, nearPlayer);
                        reallyLearned = preUnifiedResult != null && preUnifiedResult.success()
                                && preUnifiedResult.data() != null
                                && preUnifiedResult.data().has("learned")
                                && preUnifiedResult.data().get("learned").getAsBoolean();
                    } else {
                        reallyLearned = handleLearn(vState, toolCall, nearPlayer);
                    }
                }
                default -> { /* 其余工具没有"会被静默拒绝"的门槛，仍走第三步正常执行 */ }
            }
            if (gatedReject != null) {
                // 与第三步的反馈口径一致：只进玩家聊天框 + 控制台（系统事实，不冒 NPC 气泡）
                if (nearPlayer != null && nearPlayer.isOnline()) {
                    nearPlayer.sendMessage(gatedReject);
                }
                Bukkit.getConsoleSender().sendMessage(gatedReject);
            }
        }

        // === 索要交付：模型台词明确答应给 → 执行给予接口 ===
        // 索要发起时不再当场出货（见 GUI requestItem / annotateBeg），交付交给模型表态：
        // 模型调了 give_item 上面已经真给了；没调但台词明确答应（"给你/拿去/送你了/拿着"），
        // 在这里替它把东西真正交出去，避免"嘴上答应、背包里却没有"。
        if (!reallyGave && nearPlayer != null && looksLikeGivingConsent(dialogue)) {
            var request = vState.peekItemRequest(nearPlayer.getUniqueId());
            // 插件裁决不给（登记索要时已算死）：模型嘴上答应了也不交货——结果归插件，模型只有表达权
            if (request != null && !request.pluginApproved()) {
                plugin.getLogger().info("§c[MC-AI] 索要交付拦截: 插件裁决为不给（GiftPolicy），模型台词答应也不算数 — "
                        + com.howard.mcai.ai.Smalltalk.nameOf(vState) + " / " + request.mat().name());
                request = null;
            }
            if (request != null) {
                String fail = plugin.getWorldInteraction()
                        .handOverFromStock(vState, nearPlayer, request.mat(), request.count());
                if (fail == null) {
                    reallyGave = true;
                    vState.clearItemRequest(nearPlayer.getUniqueId());
                } else {
                    if (nearPlayer.isOnline()) nearPlayer.sendMessage(fail);
                    Bukkit.getConsoleSender().sendMessage(fail);
                }
            }
        }

        // === 承诺-执行一致性守卫（硬约束！）===
        // 台词里的"我这就过去""给你""我记住了"若没有本轮**真实做成**的动作兑现，就是空头支票。
        // 只删那一句，不做任何替换（硬约束：禁止兜底台词）—— 全被删空时下面透传为 "..."。
        String beforePromise = dialogue;
        dialogue = stripUnbackedPromises(vState, dialogue, toolCall, reallyGave, reallyLearned);
        if (dialogue == null || dialogue.isBlank()) {
            // 整条回复都被承诺守卫删空。若只是"这就过去/马上过来"这类**移动措辞**误判，
            // 不能给玩家回 "..."（实测玩家收到空回复的直接原因）—— 退回原台词并留日志；
            // 但如果原文确实只有"给你/拿去/我记住了"这类实物/记忆承诺（没有对应工具兑现），
            // 仍按硬约束剥空，绝不展示空头支票。
            boolean realGiveOrRememberPromise = beforePromise != null
                    && (PROMISE_GIVE.matcher(beforePromise).find()
                        || PROMISE_REMEMBER.matcher(beforePromise).find());
            if (beforePromise != null && !beforePromise.isBlank() && !realGiveOrRememberPromise) {
                plugin.getLogger().info("[MC-AI] 承诺守卫把整条台词删空，已退回原台词（疑似移动措辞误判）["
                        + vState.profession().zhName + "]: [" + beforePromise + "]");
                dialogue = beforePromise;
            } else {
                if (beforePromise != null && !beforePromise.isBlank()) {
                    plugin.getLogger().info("[MC-AI] 空头承诺已整句剥离（无对应工具兑现）["
                            + vState.profession().zhName + "]: [" + beforePromise + "]");
                }
                dialogue = "...";
            }
        } else if (!dialogue.equals(beforePromise)) {
            plugin.getLogger().info("§7[MC-AI] 空头承诺已剥离 [" + vState.profession().zhName + "]: ["
                    + beforePromise + "] → [" + dialogue + "]");
        }

        // === 台词硬性长度上限已并入 sanitizeDialogue（80 字截断），此处无需重复处理 ===

        // === 第二步：Facts 自动积累（SPEC 3.4） ===
        // 关键事件自动进永久记忆 —— 让 NPC 下次见到玩家还能想起发生过什么
        accumulateFacts(vState, nearPlayer, toolCall, dialogue);

        // === 第二步半：肢体语言（动作类型 → 可见姿态） ===
        // wave/nod/shake_head 等动作先做物理小动作，再执行对应行为
        if (toolCall != null) {
            playGesture(villager, toolCall.name());
        }

        // === 第三步：执行行为 ===
        // 统一注册表工具的真实结果（null = 本轮不是统一工具）—— 用于历史摘要如实标注成败
        ToolResult unifiedResult = preUnifiedResult;
        if (toolCall != null && isUnifiedTool(toolCall.name())) {
            // 新工具一律走 ToolDispatcher：参数校验、权限、幂等、任务生命周期都在里面。
            // give_item 已在第二步半分发过（为了赶在承诺守卫之前拿到真实成败），这里只复用结果，绝不执行第二遍。
            // 即使被拒/失败也要照常说话 —— 模型选了工具不等于台词可以吞掉。
            if (unifiedResult == null) {
                unifiedResult = dispatchUnifiedTool(vState, toolCall, nearPlayer);
                // 本轮新分发的动作类工具若失败，把原因同步给玩家（与老后门口径一致：
                // 权限拒绝这类系统事实进玩家聊天框 + 控制台，不冒 NPC 气泡）。
                // 例外：移动类（move_to/follow_player 等）在对话中被策略层拦下、或已有任务在跑，
                // 旧口径只记日志不打扰玩家——保持一致，避免聊天时刷系统提示。
                if (unifiedResult != null && !unifiedResult.success()
                        && unifiedResult.errorMessage() != null && isActionTool(toolCall.name())
                        && !ToolResult.ErrorCode.INVALID_ARGUMENT.name().equals(unifiedResult.errorCode())
                        && !MOVEMENT_TOOLS.contains(toolCall.name())) {
                    String feedback = "§e" + unifiedResult.errorMessage();
                    if (nearPlayer != null && nearPlayer.isOnline()) {
                        nearPlayer.sendMessage(feedback);
                    }
                    Bukkit.getConsoleSender().sendMessage(feedback);
                }
                // 工具成功但带系统提示（如传送待确认「请回复好」）→ 同样原样转达玩家
                if (unifiedResult != null && unifiedResult.success() && unifiedResult.data() != null
                        && unifiedResult.data().has("notice")) {
                    String notice = unifiedResult.data().get("notice").getAsString();
                    if (nearPlayer != null && nearPlayer.isOnline()) {
                        nearPlayer.sendMessage(notice);
                    }
                    Bukkit.getConsoleSender().sendMessage(notice);
                }
            }
            speak(vState, nearPlayer, dialogue);
        } else if (toolCall != null) {
            // 对话中"腿被锁住" —— 移动类动作一律不执行（"站立不动 + 持续直视玩家"是项目硬约束）。
            // 显式记一笔，绝不静默跳过：模型难免会承诺"我这就过去"，日志留下证据便于排查；
            // 台词里的这类承诺已由上面的 stripUnbackedPromises 挡在气泡之外。
            if (vState.interactingPlayer() != null && MOVEMENT_TOOLS.contains(toolCall.name())) {
                plugin.getLogger().info("§7[MC-AI] 对话中忽略移动类动作: " + toolCall.name()
                        + "（站立不动约束·" + vState.profession().zhName + "）");
            }
            switch (toolCall.name()) {
                // say / flee / find_shelter / look_at / move_to / follow_player 均已迁入统一柜
                // （走上面的统一分发分支，本 switch 不再处理）：
                // move_to=寻路任务、follow_player=跟随任务、flee/find_shelter/look_at 各有
                // 自己的统一工具（内部仍守对话站立约束），say 是登记标记（台词由 speak 发出）。
                case "offer_trade" -> {
                    if (nearPlayer != null) {
                        // === 动态交易：LLM 参数驱动增删改配方 ===
                        // buildCurrentRecipes() 返回深拷贝（含砍价后的动态价格 + 上架商品），
                        // 改这份拷贝不会污染 TradeRegistry 全局缓存
                        List<org.bukkit.inventory.MerchantRecipe> recipes = vState.buildCurrentRecipes();

                        String action = safeGetString(toolCall.arguments(), "action", "open");
                        String itemZh = safeGetString(toolCall.arguments(), "item", null);
                        String enchantZh = safeGetString(toolCall.arguments(), "enchant", null);
                        int price = (int) safeGetDouble(toolCall.arguments(), "price", -1);
                        int count = (int) safeGetDouble(toolCall.arguments(), "count", 1);

                        // 附魔商品支持：item 名带"附魔"前缀（如"附魔钻石剑"）或单独传 enchant 参数。
                        // 基础名去掉前缀再去查 Material —— 否则"附魔钻石剑"解析不到 Material，
                        // 上架分支被静默跳过，就会出现"NPC 说好但面板里没有"。
                        String baseZh = TradeRegistry.stripEnchantedPrefix(itemZh);
                        boolean wantEnchant = TradeRegistry.isEnchantedName(itemZh)
                                || (enchantZh != null && !enchantZh.isBlank());
                        // 存到动态上架表里的附魔名（null = 普通物品；wantEnchant 但没指定 → 随机附魔）
                        String enchantStore = (enchantZh != null && !enchantZh.isBlank()) ? enchantZh.trim() : null;

                        switch (action) {
                            case "offer" -> {
                                // 免费赠送（price=0）—— 交易 GUI 无法表达 0 绿宝的配方，
                                // 直接把东西塞进玩家背包才是真正的"免费给你"。
                                // 止血（2026-10-21）：必须出 NPC 真实库存，禁止凭空造物 ——
                                // 统一走 WorldInteractionService.deliverFreeGift（神使凭空补足须审计）。
                                // 没给成（库存为空）时台词里的"我送你"会被后续承诺守卫按未兑现处理。
                                if (baseZh != null && price == 0) {
                                    Material mat = TradeRegistry.zhToMaterial(baseZh);
                                    if (mat != null && mat != Material.EMERALD) {
                                        int delivered = plugin.getWorldInteraction()
                                                .deliverFreeGift(vState, nearPlayer, mat, count);
                                        if (delivered > 0) {
                                            sendDebug(nearPlayer, "§b[MC-AI] " + vState.profession().zhName
                                                    + "送了你 " + itemZh + " x" + delivered);
                                        }
                                    }
                                }
                                // 上架新商品（price>0）—— 写入 offeredItems 持久化，下次开 GUI 仍在
                                else if (baseZh != null && price > 0) {
                                    Material mat = TradeRegistry.zhToMaterial(baseZh);
                                    if (mat == Material.EMERALD) {
                                        plugin.getLogger().info("§c[MC-AI] 拒绝上架 '" + baseZh + "' — 绿宝石是货币不是商品");
                                    } else if (mat != null) {
                                        // 上架价 = 商品标价；玩家谈成的价只记在各自的 ShopSession 里，
                                        // 重新上架不会冲掉谁的成交价（不再需要旧的砍价锁）
                                        if (!TradeRegistry.professionCanSell(vState.profession(), mat)) {
                                            plugin.getLogger().info("§c[MC-AI] 拒绝上架 '" + baseZh + "' — " + vState.profession().zhName + "不卖这个");
                                        } else {
                                            vState.addOfferedItem(mat.name(), price, count, wantEnchant ? enchantStore : null);
                                            recipes = vState.buildCurrentRecipes();  // 重建（含新上架 + 动态价）
                                            plugin.getLogger().info("§b[MC-AI] 上架: " + baseZh
                                                    + (wantEnchant ? "（附魔" + (enchantStore != null ? enchantStore : "随机") + "）" : "")
                                                    + " x" + count + " = " + price + "绿宝（已持久化）");
                                        }
                                    }
                                }
                            }
                            case "discount" -> {
                                // 已退役（2026-10-21，交易界面路线二）：模型无权改价。
                                // 砍价只在商店界面里发生：玩家选砍幅、BargainPolicy 守底线、结果记 ShopSession。
                                // 模型再调 discount 只记日志，价格纹丝不动，台词里许诺的折扣不会被兑现。
                                plugin.getLogger().info("§7[MC-AI] 忽略 discount（模型改价已退役）: "
                                        + itemZh + " → " + price + "绿宝");
                            }
                            case "remove" -> {
                                if (baseZh != null) {
                                    Material mat = TradeRegistry.zhToMaterial(baseZh);
                                    if (mat != null) {
                                        vState.removeOffered(mat.name());
                                        recipes.removeIf(r -> TradeRegistry.recipeSells(r, mat));
                                        plugin.getLogger().info("§b[MC-AI] 下架: " + itemZh);
                                    }
                                }
                            }
                            default -> { /* open — 直接用 buildCurrentRecipes() 即可 */ }
                        }

                        // === 交易意图门卫（代码层兜底） ===
                        // Uncensored 模型聊天聊着就要开店。只有玩家最近真的谈到交易才准弹 GUI；
                        // offer/remove 是管理流程不受此限。
                        boolean isPlainOpen = action.equals("open");
                        if (isPlainOpen && !hasRecentTradeIntent(vState)) {
                            plugin.getLogger().info("§c[MC-AI] 拦截 offer_trade(open): 玩家最近没聊到交易，降级为 say");
                            sendDebug(nearPlayer, "§e[MC-AI] NPC 想开商店，但你没在谈交易，已拦下");
                        } else if (isPlainOpen) {
                            // 开店改走自家商店界面（路线二）：原版交易窗退役。
                            // 模型这句台词写进会话台词栏，界面一打开村民就在「说着话」；
                            // 上架/下架动作不开店（商品表已更新，下次开店自然刷新）。
                            var session = plugin.getShopSessions()
                                    .getOrCreate(vState.getUniqueId(), nearPlayer.getUniqueId());
                            if (dialogue != null && !dialogue.isBlank()) {
                                session.setNpcLine(dialogue);
                            }
                            com.howard.mcai.gui.ShopGui.open(plugin, nearPlayer, vState);
                        }
                    }
                    speak(vState, nearPlayer, dialogue);
                }
                // learn / wave / shake_head / nod / idle 也已迁入统一柜：
                // learn 在"第二步半"经统一分发提前执行（是否真记住决定"我记住了"能不能留）；
                // 手势的可见动作仍由 playGesture 按工具名统一触发，统一工具只做登记。
                case "give_item" -> {
                    // 给物品已在"第二步半"提前执行过（门槛放不放行直接决定台词里能不能许诺），
                    // 这里绝不能再调一次 execute —— 否则同一件东西会被送出两份
                    speak(vState, nearPlayer, dialogue);
                }
                case "accept_swap" -> {
                    // 交换也已在"第二步半"执行完（双方物品一次性转移），这里只说话
                    speak(vState, nearPlayer, dialogue);
                }
                // equip 已迁入统一柜（走上面的统一分发分支），老后门世界交互到此全部退役。
                // 本 switch 如今只剩 offer_trade（2026-10-21 已改道自家商店界面，见该分支）与兜底。
                default -> {
                    // 未知 tool → 只显示台词（不要崩溃）
                    speak(vState, nearPlayer, dialogue);
                }
            }
        } else {
            speak(vState, nearPlayer, dialogue);
        }

        // 本轮结束：模型既没给也没换 → 作废待处理请求，避免旧请求被下一轮无关的话触发
        if (nearPlayer != null) {
            vState.clearItemRequest(nearPlayer.getUniqueId());
            vState.clearPendingSwap(nearPlayer.getUniqueId());
        }

        // === 第四步：无论如何追加 assistant（Jinja 交替硬约束） ===
        // 历史行附带 [动作:摘要] —— 旧版只存台词，模型回读时"不知道自己上一步做了什么"，
        // 会重复动作或忘记刚给过东西。摘要 ≤20 字，只记工具名 + 关键参数，不含台词（台词已在正文）。
        // sanitizeDialogue 统一剥离该标记，防模型回读进台词或气泡泄漏。
        // 摘要必须如实标注成败：被门槛拒、或对话中被"腿锁住"拦下的动作打上"（未成功）"，
        // 否则模型回读历史时会以为自己真给过了，下一轮继续重复"给你"这类没兑现的承诺。
        boolean actionFailed = toolCall != null
                && (gatedReject != null
                    || (unifiedResult != null && !unifiedResult.success())
                    || (vState.interactingPlayer() != null && MOVEMENT_TOOLS.contains(toolCall.name())));
        String historyLine = (toolCall == null)
                ? dialogue
                : dialogue + "\n[动作:" + summarizeAction(toolCall) + (actionFailed ? "（未成功）" : "") + "]";
        int before = vState.chatHistory().size();
        vState.addChatEntry("assistant", historyLine);
        int after = vState.chatHistory().size();
        String lastRole = vState.chatHistory().getLast().role();
        sendDebug(nearPlayer, "§b[MC-AI] addChatEntry assistant " + before + "→" + after + " 最后role=" + lastRole
                + " dialogue='" + dialogue.substring(0, Math.min(dialogue.length(), 20)) + "'");

        // === 第五步：记忆标脏 → 防抖落盘（对话/事实/价格/上架变更都要持久化） ===
        plugin.getMemoryStore().markDirty(vState.getUniqueId());
    }

    /**
     * NPC 说话的统一出口 —— 三路分发：
     * <ol>
     *   <li>头顶**会话框**：金色名字头 + 深色半透明底板，按字数自动计时消失
     *       （旧版裸白字 + 固定 6 秒，长回复没看完就没了）—— <b>这是"公开"的</b>，
     *       附近任何玩家（64 格视距内）都能看到</li>
     *   <li>**聊天框完整台词**：<b>只发给正在互动的那个玩家</b>（他才是"聊天者"），
     *       聊天 HUD 自动换行，再长的回复也显示得全；其他玩家的聊天框不会收到任何文字</li>
     *   <li>控制台留档（排查对话问题时用）</li>
     * </ol>
     */
    private void speak(VillagerState vState, Player nearPlayer, String dialogue) {
        Villager villager = vState.entity();
        String customName = villager.getCustomName();
        String name = (customName != null && !customName.isBlank())
                ? customName.replaceAll("§.", "")
                : vState.profession().zhName;

        // 听者名字：聊天框/气泡/控制台统一用 NPC>玩家 标注"谁对谁说"
        String listenerName = (nearPlayer != null && nearPlayer.isOnline()) ? nearPlayer.getName() : null;

        // 1. 头顶会话框（按字数自动计时）+ 控制台 [chat] 留档（由 BubbleManager 统一输出）
        //    控制台日志已收口到 BubbleManager，这里不再重复打印，避免一行台词出现两次
        bubbleManager.showDialog(villager, name, listenerName, dialogue);

        // 2. 聊天框完整台词（只发给互动玩家）；前缀同样带 说话者>听者
        if (listenerName != null) {
            nearPlayer.sendMessage("§6[" + name + ">" + listenerName + "] §f" + dialogue);
        }
    }

    /**
     * Facts 自动积累（SPEC 3.4）—— 从对话内容和 tool_call 里提取关键事件，
     * 塞进永久记忆（上限 20 条）。让 NPC 下次见到玩家还能想起发生过什么。
     *
     * <p>检测维度：
     * <ul>
     *   <li>情绪阈值：fear/favor 超过某个值 → 记录这个玩家做了什么</li>
     *   <li>关键词命中：台词里提到"送"、"下次"、"保证" → 承诺/赠礼事件</li>
     *   <li>tool_call 类型：flee/follow_player/offer_trade → 交互事件</li>
     * </ul>
     * 这是简化版——完整 LLM 语义抽取留作 M3+（额外一次 LLM 调用，TTFT 翻倍）。
     */
    private static void accumulateFacts(VillagerState vState, Player player, ToolCall toolCall, String dialogue) {
        if (player == null) return;
        String playerName = player.getName();
        java.util.UUID pid = player.getUniqueId();  // 玩家相关记忆全部打 owner，仅该玩家可见

        // === 情绪驱动的 facts（favor 按玩家独立，判定当前这位玩家） ===
        if (vState.emotion().fear() > 50) {
            // 恐惧值很高 → 这个玩家做了吓人的事
            vState.addFact("旅者 " + playerName + " 让我很害怕", pid);
        }
        if (vState.emotion().favor(pid) > 70) {
            // 好感度很高 → 这个玩家对我很好
            vState.addFact("旅者 " + playerName + " 对我特别好", pid);
        }
        if (vState.emotion().favor(pid) < -30) {
            // 好感度为负 → 这个玩家得罪过我
            vState.addFact("旅者 " + playerName + " 得罪过我", pid);
        }

        // === tool_call 驱动的 facts ===
        if (toolCall != null) {
            switch (toolCall.name()) {
                case "flee" -> vState.addFact("旅者 " + playerName + " 追过我，我逃跑了", pid);
                case "offer_trade" -> vState.addFact("我给旅者 " + playerName + " 开过店", pid);
                case "follow_player" -> vState.addFact("我跟着旅者 " + playerName + " 走过", pid);
            }
        }

        // === 台词关键词驱动的 facts（简单字符串匹配） ===
        if (dialogue == null) return;
        String d = dialogue.toLowerCase();
        if (d.contains("送") || d.contains("给你") || d.contains("拿着")) {
            vState.addFact("旅者 " + playerName + " 提到要给我东西", pid);
        }
        if (d.contains("下次") || d.contains("改天") || d.contains("再来")) {
            vState.addFact("旅者 " + playerName + " 说会再回来", pid);
        }
        if (d.contains("朋友") || d.contains("兄弟") || d.contains("熟人")) {
            vState.addFact("我和旅者 " + playerName + " 算得上熟人", pid);
        }
        if (d.contains("谢谢") || d.contains("多谢") || d.contains("感谢")) {
            vState.addFact("我谢过旅者 " + playerName + "", pid);
        }
        if (d.contains("回来") && d.contains("看")) {
            vState.addFact("旅者 " + playerName + " 说会回来看我", pid);
        }
    }

    // ===== 工具方法 =====

    /** 找最近的玩家（Paper 的 getNearbyEntities 里找 LivingEntity 类型） */
    private Player findNearestPlayer(Villager villager, double radius) {
        Player nearest = null;
        double nearestDist = radius + 1;
        for (Player p : villager.getWorld().getPlayers()) {
            double d = villager.getLocation().distance(p.getLocation());
            if (d < nearestDist) {
                nearestDist = d;
                nearest = p;
            }
        }
        return nearest;
    }

    /** 安全地从 JsonObject 里取 string 字段 */
    private static String safeGetString(com.google.gson.JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        return obj.get(key).getAsString();
    }

    /**
     * 把一次 tool_call 压缩成 ≤20 字的动作摘要（写进 assistant 历史行的 {@code [动作:…]}）。
     * <p>
     * 只取"工具名 + 第一个命中的关键参数"，**不含 message 台词** —— 台词已在正文里，
     * 重复塞会让 assistant 历史行膨胀、也会诱导模型复读。摘要只是让模型回读时知道
     * "我上一步做了什么"，例如 {@code give_item iron_sword}、{@code move_to 120}。
     */
    private static String summarizeAction(ToolCall toolCall) {
        if (toolCall == null) return "";
        StringBuilder sb = new StringBuilder(toolCall.name() == null ? "?" : toolCall.name());
        com.google.gson.JsonObject args = toolCall.arguments();
        // 按语义优先级探测参数名，取第一个存在且非空的作为"宾语"
        String[] keys = {"item", "material", "action", "target", "block",
                "price", "content", "count", "x", "y", "z"};
        if (args != null) {
            for (String k : keys) {
                if (args.has(k) && !args.get(k).isJsonNull()) {
                    String v = args.get(k).getAsString();
                    if (v != null && !v.isBlank()) {
                        sb.append(' ').append(v);
                        break;
                    }
                }
            }
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.length() <= 20 ? s : s.substring(0, 20);
    }

    /** 安全地从 JsonObject 里取 double 字段 */
    private static double safeGetDouble(com.google.gson.JsonObject obj, String key, double def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 让生物朝目标方向走几步（Paper 1.21.1 没有 pathfind() API）。
     * 用 setVelocity 给一个水平方向的速度向量，Y 分量给个小正数防止卡地面。
     * 包内可见：PresentationLayer 的动物靠近逻辑复用；工具层（npc.move_to）也需要复用同一套移动方式。
     *
     * @param entity 要移动的实体
     * @param target 目标位置
     * @param speed  速度倍率（1.0 = 正常走路，2.0 = 跑）
     */
    public static void moveEntityTowards(org.bukkit.entity.LivingEntity entity, org.bukkit.Location target, double speed) {
        org.bukkit.Location from = entity.getLocation();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) return;  // 已经到了
        double vx = dx / dist * speed;
        double vz = dz / dist * speed;
        double vy = 0.05;  // 轻微向上，防止卡半格
        entity.setVelocity(new org.bukkit.util.Vector(vx, vy, vz));
    }

    /**
     * 找最近的遮雨处 —— 3 个条件：
     * 1. 上方 2-3 格有 solid block（不会被雨淋）
     * 2. 地面是 solid（能站）
     * 3. 离起点在 radius 格内
     *
     * 扫描策略：以起点为中心，半径递增（1,2,3...）查每个水平位置，
     * 找到第一个满足条件的就返回。O(radius²)，20 格内≈1200 次 Block 检查，很快。
     */
    /** 找最近的遮雨处（统一柜 NpcFindShelterTool 共用同一份实现） */
    public org.bukkit.Location findNearestShelter(org.bukkit.Location start, int radius) {
        org.bukkit.World world = start.getWorld();
        int sx = start.getBlockX();
        int sy = start.getBlockY();
        int sz = start.getBlockZ();

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;  // 只看边缘圈
                    int bx = sx + dx;
                    int bz = sz + dz;
                    // 找 Y：从起点高度往下搜 5 格找 solid 地面
                    for (int dy = 3; dy >= -3; dy--) {
                        int by = sy + dy;
                        org.bukkit.block.Block ground = world.getBlockAt(bx, by, bz);
                        org.bukkit.block.Block roof1 = world.getBlockAt(bx, by + 1, bz);
                        org.bukkit.block.Block roof2 = world.getBlockAt(bx, by + 2, bz);
                        if (ground.getType().isSolid() && !ground.isPassable()
                                && (roof1.getType().isSolid() || roof2.getType().isSolid())) {
                            // 找到！返回地面上方 1 格（站的位置）
                            return new org.bukkit.Location(world, bx + 0.5, by + 1, bz + 0.5);
                        }
                    }
                }
            }
        }
        return null;  // 没找到
    }
}
