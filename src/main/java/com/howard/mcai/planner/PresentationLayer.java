package com.howard.mcai.planner;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.mob.MobSpecies;
import com.howard.mcai.mob.MobState;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 表现层（SPEC 2.5）—— 玩家 10 格内、高频（默认 3 秒/轮）。中央计划器的核心执行层。
 * <p>
 * 职责：
 * <ol>
 *   <li>现有村民维护：清理死实体 / 对话超时 / 恐惧衰减</li>
 *   <li>唤醒扫描：玩家 10 格内的动物/怪物 → MobState（懒创建，SPEC 2.1 "空间裁剪"）</li>
 *   <li>规则行为树（SPEC 2.2 行为树打底，**不调 LLM**，只处理"非社交时刻"）：
 *     <ul>
 *       <li>动物：fear 高 → 逃跑 + 叙述体气泡（翻译官模式）；玩家持食物 → 靠近</li>
 *       <li>僵尸：记仇 → setTarget 追击 + 红字情绪气泡</li>
 *     </ul>
 *   </li>
 *   <li>每轮状态衰减（fear/aggression 消退，模拟"气消了"）</li>
 * </ol>
 * <p>
 * LLM 不参与本层 —— 村民对话仍走事件驱动（PlayerChatListener/InteractListener），
 * 符合 SPEC 5 "事件驱动：无事件不决策，闲着发呆零调用"。
 */
public final class PresentationLayer extends TickLayer {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final Map<UUID, MobState> mobs;
    private final BubbleManager bubbleManager;

    /** 僵尸追击半径上限（16 格外就不追了，等它自己走回来） */
    private static final double ZOMBIE_CHASE_RADIUS = 16.0;

    /** 僵尸追击红字气泡概率（每轮）—— 制造持续压迫感 */
    private static final double ZOMBIE_GROWL_CHANCE = 0.35;

    /** 僵尸夜间环境吼叫概率（每轮）—— 没被打也有存在感 */
    private static final double ZOMBIE_AMBIENT_CHANCE = 0.10;

    /** 动物空闲叙述概率（每轮）—— 氛围感，让世界"活着" */
    private static final double IDLE_CHANCE = 0.04;

    /** 动物逃跑触发距离（fear 高且玩家 6 格内） */
    private static final double FLEE_TRIGGER_DISTANCE = 6.0;

    /** 动物靠近玩家的目标距离 */
    private static final double APPROACH_TARGET_DISTANCE = 2.5;

    /** 逃跑气泡冷却（毫秒）—— 连续逃跑也别每轮刷屏 */
    private static final long SOUND_COOLDOWN_FLEE = 5000L;

    /** 空闲/吃食气泡冷却（毫秒） */
    private static final long SOUND_COOLDOWN_IDLE = 8000L;

    /** 僵尸咆哮气泡冷却（毫秒） */
    private static final long SOUND_COOLDOWN_ZOMBIE = 6000L;

    // ===== NPC 自动闲聊（LLM 驱动，默认 30s 一轮） =====

    /** 闲聊累计 tick（累计到配置的间隔才触发一轮，防止每 3s 都开群） */
    private int ambientAccumulatorTicks = 0;

    /** 每个村民上次参与闲聊的时间戳（毫秒，冷却防同一人不停说话） */
    private final Map<UUID, Long> ambientCooldown = new HashMap<>();

    /** 正在参与某群闲聊的村民 —— 防同一人同时进多个群（LLM 串行也防混淆上下文） */
    private final Set<UUID> ambientBusy = new HashSet<>();

    /** 闲聊簇玩家可见距离 —— 32 格内有在线玩家才开聊（够近看得见气泡才烧本地模型） */
    private static final double AMBIENT_PLAYER_VISIBILITY = 32.0;
    /** 自动聊天参与者之间最远距离（格）—— 用户约束：两个或多个 AI 闲聊距离最远不能超过 3 格，太远就"各聊各的"不真实 */
    private static final double AMBIENT_MAX_PAIR_DISTANCE = 3.0;
    /** 闲聊最大轮数：每轮 = 起话头 + 接话（4 轮 ≈ 8 句，约 30-40 秒；玩家在场才会续满） */
    private static final int AMBIENT_MAX_ROUNDS = 4;

    /** 闲聊气泡固定显示时长 —— 15 秒，保证玩家从远处跑过来时还能看清内容 */
    private static final long AMBIENT_BUBBLE_TICKS = 300L;

    // ===== 玩家走近时 NPC 主动打招呼 =====

    /** 打招呼的触发距离（格）—— 必须贴到跟前（3 格内）才开口，避免"隔老远就喊"的突兀感 */
    private static final double GREET_RADIUS = 3.0;
    /** 同一 NPC 对同一玩家的招呼冷却 —— 免得玩家在附近来回走动就被反复招呼 */
    private static final long GREET_PAIR_COOLDOWN_MS = 300_000L;   // 5 分钟
    /** 同一玩家的招呼总冷却 —— 走过整条村不至于被连续招呼 */
    private static final long GREET_PLAYER_COOLDOWN_MS = 60_000L;
    /** 冷却表清理门槛（超过 2 倍 pair 冷却的条目直接丢弃，防 map 无界增长） */
    private static final long GREET_PRUNE_MS = GREET_PAIR_COOLDOWN_MS * 2;

    /**
     * 玩家这次"走近"已经被招呼过的 NPC（key = 玩家 UUID）。
     * 玩家走远后从集合里移除，下次再走近可以重新招呼一次 —— 否则会被 pair 冷却锁死。
     */
    private final Map<UUID, Set<UUID>> greetedThisVisit = new HashMap<>();

    /** (玩家 UUID + "|" + NPC UUID) → 上次招呼的时刻（毫秒） */
    private final Map<String, Long> greetCooldownAtMs = new HashMap<>();

    /** 玩家上次被招呼的时刻（毫秒）—— 限制"走过整条村"被连续招呼的密度 */
    private final Map<UUID, Long> greetPlayerCooldownAtMs = new HashMap<>();

    // ===== 僵尸威胁 → 村民恐惧（纯规则，无 LLM） =====

    /** 村民"察觉"到僵尸的距离（格）—— 与场景上下文里 nearby_mobs 的 8 格口径一致 */
    private static final double ZOMBIE_PANIC_RADIUS = 8.0;
    /** 僵尸在附近时每轮（3 秒）上涨的恐惧值 —— 要压过 decayFear 的 -1，僵尸不走就持续惊恐 */
    private static final int ZOMBIE_PANIC_FEAR_PER_ROUND = 8;
    /** 同一村民写"闹僵尸"关键事实的冷却 —— 僵尸赖着不走也别每 3 秒刷一条 */
    private static final long ZOMBIE_FEAR_FACT_COOLDOWN_MS = 60_000L;

    /** 村民 UUID → 上次写"闹僵尸"恐惧事实的时刻（毫秒）。重启即清空，只是节流用 */
    private final Map<UUID, Long> zombieFearFactAtMs = new HashMap<>();

    public PresentationLayer(McaiPlugin plugin,
                             Map<UUID, VillagerState> villagers,
                             BubbleManager bubbleManager,
                             Map<UUID, MobState> mobs) {
        super("presentation", plugin.getPluginConfig().tickIntervalTicks());
        this.plugin = plugin;
        this.villagers = villagers;
        this.bubbleManager = bubbleManager;
        this.mobs = mobs;
    }

    @Override
    protected void onTick(CentralPlanner planner) {
        // === 1. 村民维护 ===
        villagers.values().removeIf(s -> s.entity().isDead());
        for (VillagerState vState : villagers.values()) {
            if (!vState.entity().isValid()) continue;
            // 对话超时按真实游戏 tick 累计（每轮 +intervalTicks，而不是按运行次数 +1）
            vState.tickConversationTimeout(plugin.getPluginConfig().tickIntervalTicks());
            // 恐惧随时间缓慢消退（-1/轮≈-1/3秒，100 降到 0 约需 5 分钟）
            vState.emotion().decayFear();
            // 附近有僵尸 → 恐惧上涨 + 记一条"害怕"心情（僵尸不走就持续压过衰减）
            tickVillagerSafety(vState);
        }
        // 已不在册的村民，恐惧事实冷却记录一并清掉，防 map 无界增长
        zombieFearFactAtMs.keySet().retainAll(villagers.keySet());

        // === 2. 唤醒扫描 + 规则行为树（多物种） ===
        int radius = plugin.getPluginConfig().playerScanRadius();
        // 同一生物在多个玩家的扫描范围内只 tick 一次（避免双重衰减/重复气泡）
        Set<UUID> tickedThisRound = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) continue;
            Collection<Entity> nearby = player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius);
            for (Entity e : nearby) {
                if (!(e instanceof LivingEntity le)) continue;
                String species = MobSpecies.of(le);
                if (species == null || !tickedThisRound.add(le.getUniqueId())) continue;
                MobState st = mobs.computeIfAbsent(le.getUniqueId(), k -> new MobState(le, species));
                tickMob(player, st);
            }
        }

        // === 3. 清理已死/卸载的实体状态，防止 map 无限膨胀 ===
        mobs.entrySet().removeIf(en -> {
            MobState st = en.getValue();
            return st.entity().isDead() || !st.entity().isValid();
        });

        // === 4. NPC 自动闲聊（LLM 驱动真实对话，默认 30s 一轮） ===
        // 放在村民维护之后：超时结束的村民已恢复 AI，可以参与闲聊
        var cfg = plugin.getPluginConfig();
        ambientAccumulatorTicks += cfg.tickIntervalTicks();
        int ambientIntervalTicks = cfg.ambientChatIntervalSeconds() * 20;  // 秒 → tick
        if (cfg.ambientChatEnabled() && ambientAccumulatorTicks >= ambientIntervalTicks) {
            ambientAccumulatorTicks = 0;
            maybeAmbientChat();
        }

        // === 5. 主动打招呼：玩家走近时，随机挑一个附近 NPC 先开口 ===
        maybeProactiveGreeting(planner);
    }

    /**
     * 村民安全感 tick（每 3 秒，纯规则无 LLM）—— 附近有僵尸就涨恐惧 + 记一条"害怕"心情。
     * <p>
     * 恐惧值会被 {@code PromptBuilder.buildSceneContext} 注入场景上下文（{@code fear} 字段），
     * system prompt 里也有 {@code fear>50}/{@code fear>70} 的语气示例，所以一旦抬上去，
     * NPC 后续开口自然就是害怕的腔调；关键事实再给这份恐惧一个"记得住"的理由
     * （下次上线还能想起村里闹过僵尸）。写事实按 {@link #ZOMBIE_FEAR_FACT_COOLDOWN_MS}
     * 节流，防僵尸赖着不走时每 3 秒刷一条。
     * <p>
     * Husk / Drowned 等变种都继承 {@link Zombie}，instanceof 一并覆盖。
     */
    private void tickVillagerSafety(VillagerState vState) {
        Villager v = vState.entity();
        if (v == null || !v.isValid() || v.isDead()) return;

        boolean zombieNear = false;
        for (Entity e : v.getWorld().getNearbyEntities(
                v.getLocation(), ZOMBIE_PANIC_RADIUS, ZOMBIE_PANIC_RADIUS, ZOMBIE_PANIC_RADIUS)) {
            if (e instanceof Zombie z && !z.isDead()) {
                zombieNear = true;
                break;
            }
        }
        if (!zombieNear) return;

        // 附近有僵尸 → 恐惧上涨（clamp 到 100，僵尸蹲旁边时村民持续处于惊恐）
        vState.emotion().addFear(ZOMBIE_PANIC_FEAR_PER_ROUND);

        long now = System.currentTimeMillis();
        Long last = zombieFearFactAtMs.get(vState.getUniqueId());
        if (last == null || now - last >= ZOMBIE_FEAR_FACT_COOLDOWN_MS) {
            zombieFearFactAtMs.put(vState.getUniqueId(), now);
            vState.addFact("村里闹僵尸了，就蹲在我附近，吓得我心惊肉跳");
            plugin.getMemoryStore().markDirty(vState.getUniqueId());
        }
    }

    /** 单只生物的规则行为决策（纯规则，无 LLM） */
    private void tickMob(Player player, MobState st) {
        LivingEntity e = st.entity();
        double dist = e.getLocation().distance(player.getLocation());

        if (MobSpecies.isAnimal(st.species())) {
            tickAnimal(st, player, dist);
        } else {
            tickZombie(st);
        }
        st.decay();
    }

    /** 动物行为树：逃跑 > 空闲叙述 > 靠近 > 吃食 */
    private void tickAnimal(MobState st, Player player, double dist) {
        LivingEntity e = st.entity();
        boolean food = holdsFoodFor(player, st.species());

        // 1. 恐惧 → 逃跑（fear > 50 且玩家 6 格内）：每次逃跑都冒短句（冷却节流防刷屏）
        if (st.fear() > 50 && dist < FLEE_TRIGGER_DISTANCE) {
            if (st.trySound(SOUND_COOLDOWN_FLEE)) {
                bubbleManager.showMobBubble(e, "§7§o" + MobSpecies.scaredPhrase(st.species()));
            }
            fleeFrom(e, player.getLocation());
            return;
        }

        // 2. 空闲叙述（氛围感）：fear 低、玩家在场但没喂 → 偶尔冒一句小动作
        if (st.fear() <= 30 && !food && dist < 8 && Math.random() < IDLE_CHANCE) {
            if (st.trySound(SOUND_COOLDOWN_IDLE)) {
                bubbleManager.showMobBubble(e, "§7§o" + MobSpecies.idlePhrase(st.species()));
            }
        }

        // 3. 玩家手持它爱吃的食物 → 靠近（原版 AI 也会，这里推一把让它明显走过来）
        if (food && st.fear() <= 40 && dist > APPROACH_TARGET_DISTANCE && dist < 10) {
            CentralPlanner.moveEntityTowards(e, player.getLocation(), 0.6);
            return;
        }

        // 4. 贴脸且手持食物 → 偶尔冒"吃食"短句（favor 由交互监听在喂食事件里加）
        if (food && dist <= APPROACH_TARGET_DISTANCE && Math.random() < 0.12) {
            if (st.trySound(SOUND_COOLDOWN_IDLE)) {
                bubbleManager.showMobBubble(e, "§7§o" + MobSpecies.pleasedPhrase(st.species()));
            }
        }
    }

    /** 僵尸行为树：记仇追击（夜间环境吼叫兜底） */
    private void tickZombie(MobState st) {
        LivingEntity e = st.entity();
        UUID grudge = st.grudgeTarget();
        Player target = grudge == null ? null : plugin.getServer().getPlayer(grudge);
        // 跨世界 distance 会抛 IllegalArgumentException（玩家进了地狱/末地而僵尸在主世界），
        // 先比对世界：不同世界按"目标不在追击范围"处理
        boolean chasing = target != null && target.isOnline()
                && target.getWorld().equals(e.getWorld())
                && e.getLocation().distance(target.getLocation()) <= ZOMBIE_CHASE_RADIUS;

        if (chasing) {
            // setTarget 让原版寻路接管追击（比 setVelocity 稳，能跳能爬）
            if (e instanceof Mob m) {
                m.setTarget(target);
            }
            // 追击压迫感：红字短句，比之前(15%)更常冒
            if (st.aggression() > 20 && Math.random() < ZOMBIE_GROWL_CHANCE
                    && st.trySound(SOUND_COOLDOWN_ZOMBIE)) {
                bubbleManager.showMobBubble(e, "§c" + MobSpecies.scaredPhrase(st.species()));
            }
            return;
        }

        // 没在记仇/目标不在附近：夜里也冒环境吼叫，让僵尸有存在感（白天交给原版 AI）
        if (!e.getWorld().isDayTime() && Math.random() < ZOMBIE_AMBIENT_CHANCE
                && st.trySound(SOUND_COOLDOWN_IDLE)) {
            bubbleManager.showMobBubble(e, "§c" + MobSpecies.idlePhrase(st.species()));
        }
    }

    /** 玩家主手拿的是不是该动物的食物 */
    private static boolean holdsFoodFor(Player p, String species) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        return hand != null && MobSpecies.isFood(species, hand.getType());
    }

    /** 朝玩家反方向逃（setVelocity，Y 分量给个小正数防止卡地面） */
    private static void fleeFrom(LivingEntity e, Location threat) {
        Location from = e.getLocation();
        double dx = from.getX() - threat.getX();
        double dz = from.getZ() - threat.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) return;
        double vx = dx / dist * 2.0;
        double vz = dz / dist * 2.0;
        e.setVelocity(new org.bukkit.util.Vector(vx, 0.3, vz));
    }

    // ===== NPC 自动闲聊（LLM 驱动真实对话） =====

    /**
     * 玩家走近时，随机挑一个附近 NPC 主动搭话。
     * <p>
     * 规则：
     * <ul>
     *   <li>触发半径 {@link #GREET_RADIUS} 格；<b>随机挑一个</b>刚进入范围的村民开口，
     *       不是附近所有 NPC 一起喊 —— 那样像市场不是村子。</li>
     *   <li>同一次"走近"每人只招呼一次：玩家走远后标记清除，下次再走近可再招呼。</li>
     *   <li>双重冷却：同一 NPC 对同一玩家 {@link #GREET_PAIR_COOLDOWN_MS}，
     *       同一玩家整体 {@link #GREET_PLAYER_COOLDOWN_MS}。</li>
     *   <li>跳过正忙的村民（在跟人对话 / LLM 请求在飞 / 正在自动闲聊），也跳过正在对话的玩家 —— 
     *       否则招呼会把玩家从当前对话对象那里"抢"过来。</li>
     * </ul>
     * 台词由 LLM 生成（无硬编码兜底台词），走和右键搭话相同的链路。
     */
    private void maybeProactiveGreeting(CentralPlanner planner) {
        long now = System.currentTimeMillis();
        Set<UUID> onlineIds = new HashSet<>();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) continue;
            onlineIds.add(player.getUniqueId());
            // 玩家正在跟某个 NPC 对话 → 不去打扰，免得招呼把对话对象"抢"走
            if (playerInConversation(player)) continue;

            Set<UUID> greeted = greetedThisVisit.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
            Set<UUID> inRangeNow = new HashSet<>();
            List<VillagerState> candidates = new ArrayList<>();

            for (VillagerState vs : villagers.values()) {
                Villager v = vs.entity();
                if (v == null || !v.isValid() || v.isDead()) continue;
                if (!v.getWorld().equals(player.getWorld())) continue;
                if (v.getLocation().distance(player.getLocation()) > GREET_RADIUS) continue;
                inRangeNow.add(vs.getUniqueId());
                if (greeted.contains(vs.getUniqueId())) continue;          // 这次走近已经招呼过
                if (vs.interactingPlayer() != null || vs.llmBusy()) continue;
                if (ambientBusy.contains(vs.getUniqueId())) continue;
                Long last = greetCooldownAtMs.get(pairKey(player.getUniqueId(), vs.getUniqueId()));
                if (last != null && now - last < GREET_PAIR_COOLDOWN_MS) continue;
                candidates.add(vs);
            }

            // 走远的从"这次走近"集合里移除 —— 下次再走近有机会重新被招呼
            greeted.retainAll(inRangeNow);
            if (candidates.isEmpty()) continue;

            // 玩家自己刚被招呼过（30s 内）→ 这一轮先放过，避免走过村庄时被连续招呼
            Long playerLast = greetPlayerCooldownAtMs.get(player.getUniqueId());
            if (playerLast != null && now - playerLast < GREET_PLAYER_COOLDOWN_MS) continue;

            VillagerState pick = candidates.get(
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
            Villager pv = pick.entity();
            if (pv == null || !pv.isValid()) continue;

            // 整批标记：这次走近范围内的人都算"招呼过"，避免 3 秒后另一个 NPC 又凑上来
            greeted.addAll(inRangeNow);
            greetCooldownAtMs.put(pairKey(player.getUniqueId(), pick.getUniqueId()), now);
            greetPlayerCooldownAtMs.put(player.getUniqueId(), now);

            // 进入对话状态：冻结原版 AI + 持续转向玩家（和右键搭话同一套），玩家可以直接接着聊
            planner.switchConversation(player, pick);
            String npcName = com.howard.mcai.ai.Smalltalk.nameOf(pick);
            plugin.getLogger().info("§b[MC-AI] 主动招呼: " + npcName + " → " + player.getName()
                    + "（距离 " + String.format("%.1f", pv.getLocation().distance(player.getLocation())) + " 格）");
            // 用 user 角色把"有人走近"告诉模型（system 只能有一条且在首位 —— Jinja 铁律）。
            // 内容中文、system prompt 英文，台词照样是中文；不调用任何工具，直接开口说话。
            // 反重复 + 情境化的关键：把该 NPC"此刻的环境 + 手头在干什么"用大白话喂进去，
            // 逼模型抓住一个具体锚点（原来只给 compactScene 的模糊摘要，模型会编造昼夜/天气）。
            String scene = PromptBuilder.envBrief(pick);
            planner.onPlayerMessage(pick, player,
                    "（玩家 " + player.getName() + " 刚走到你跟前不到 3 格，你注意到了他。你主动先开口招呼。"
                            + "你此刻的情况：" + scene + "。"
                            + "要求：① 抓一件你此刻真实的具体事来说（手头在忙的活、现在的时辰/天气、"
                            + "你正缺的东西、身边有什么人），别只干巴巴喊'你好'；"
                            + "② 换着花样说 —— 不要重复你之前已经说过的招呼语，每次都要不一样；"
                            + "③ 保持你的人格与职业说话口吻，只说一两句短句；"
                            + "④ 不要调用任何工具，直接开口说话就行。）");
        }

        // 离线玩家的记录清掉，防止长期运行后 map 无界增长
        greetedThisVisit.keySet().retainAll(onlineIds);
        greetPlayerCooldownAtMs.keySet().retainAll(onlineIds);
        greetCooldownAtMs.entrySet().removeIf(e -> now - e.getValue() > GREET_PRUNE_MS);
    }

    private static String pairKey(UUID playerUuid, UUID npcUuid) {
        return playerUuid + "|" + npcUuid;
    }

    /** 玩家当前是否正在跟某个 NPC 对话（招呼前先看这个，别把对话对象抢走） */
    private boolean playerInConversation(Player player) {
        UUID pid = player.getUniqueId();
        for (VillagerState vs : villagers.values()) {
            if (pid.equals(vs.interactingPlayer())) return true;
        }
        return false;
    }

    /**
     * 每轮（默认 30s）找一个"32 格内 ≥2 个 AI 村民"的簇，发起真实闲聊。
     * <ul>
     *   <li>簇内村民必须空闲（不在玩家对话、不在别的群）、冷却已过、有玩家在 48 格内可见</li>
     *   <li>每轮只开一个群（ambientBusy 守卫），LLM 串行逐句返回</li>
     *   <li>全程不写入 chatHistory —— 闲聊是"背景生活"，不污染三层记忆</li>
     * </ul>
     */
    private void maybeAmbientChat() {
        // 全局守卫 1：刚发生过群聊回复（15s 内）→ 让位，防止闲聊气泡覆盖群聊回复气泡
        if (plugin.recentlyGroupReplied()) return;
        // 全局守卫 2：有 NPC 正在跟玩家对话 → 让位，别在旁边起哄抢玩家注意力
        for (VillagerState vs : villagers.values()) {
            if (vs.interactingPlayer() != null) return;
        }
        // 已有群在聊（最多 3 人）→ 这轮不再开新群，等下一轮
        if (ambientBusy.size() >= 3) return;
        double radius = plugin.getPluginConfig().ambientChatRadius();

        for (VillagerState vs : villagers.values()) {
            // 说话者条件：不在玩家对话、不在别的群、冷却已过
            if (vs.interactingPlayer() != null || ambientBusy.contains(vs.getUniqueId())) continue;
            Villager v = vs.entity();
            if (!v.isValid()) continue;
            long now = System.currentTimeMillis();
            Long cd = ambientCooldown.get(vs.getUniqueId());
            if (cd != null && now - cd < 60_000L) continue;

            // 玩家可见性：簇 48 格内有在线玩家才开聊（没人看就不烧本地模型）
            boolean playerNear = false;
            for (Player p : v.getWorld().getPlayers()) {
                if (p.isOnline() && !p.isDead() && v.getLocation().distance(p.getLocation()) <= AMBIENT_PLAYER_VISIBILITY) {
                    playerNear = true;
                    break;
                }
            }
            if (!playerNear) continue;

            // 找半径内其他空闲 AI 村民（听者），且必须与说话者相距 ≤3 格
            // —— 用户约束：自动聊天的两个或多个 AI 距离最远不能超过 3 格（远隔几十格还互相搭话太假）
            List<VillagerState> others = new ArrayList<>();
            for (Entity e : v.getWorld().getNearbyEntities(v.getLocation(), radius, radius, radius)) {
                if (e instanceof Villager ov) {
                    VillagerState os = villagers.get(ov.getUniqueId());
                    if (os != null && os != vs && !ambientBusy.contains(os.getUniqueId())
                            && os.interactingPlayer() == null
                            && v.getLocation().distance(ov.getLocation()) <= AMBIENT_MAX_PAIR_DISTANCE) {
                        others.add(os);
                    }
                }
            }
            if (others.isEmpty()) continue;

            // 就近优先取听者（最多 2 个，全部满足 ≤3 格约束）—— 两个 NPC 结对来回聊，挨得近的 3 个也能组成小群
            others.sort(Comparator.comparingDouble(o -> v.getLocation().distance(o.entity().getLocation())));
            List<VillagerState> listeners = new ArrayList<>(others.subList(0, Math.min(2, others.size())));

            // 占位 + 冷却。整群参与者（说话者+全部听者）收进一个 Set 随闲聊链路一路传递：
            // 续轮会轮换 speaker/listeners，首轮的听者会掉出"当轮参数"，若释放只按当轮
            // 参数清，掉队成员的 ambientBusy 标记永远清不掉 —— 泄漏攒到
            // ambientBusy.size()>=3 后，自动闲聊会永久停摆，所以释放必须清"整群"
            Set<UUID> group = new HashSet<>();
            group.add(vs.getUniqueId());
            for (VillagerState l : listeners) group.add(l.getUniqueId());
            ambientBusy.addAll(group);
            ambientCooldown.put(vs.getUniqueId(), now);

            startAmbientTurn(vs, listeners, 0, "", 0, group);
            return;  // 每轮只开一个群
        }
    }

    /**
     * 闲聊对话链 —— step 0=说话者起话头，step 1..n=听者接话；每轮说完自动续一轮（两人来回），
     * 直到轮数达上限 {@link #AMBIENT_MAX_ROUNDS} 或玩家离开 32 格。
     * <p>
     * 每步一次 LLM 调用（一次性 prompt，不写记忆）；上一步的台词传给下一位引用。
     *
     * @param speaker   说话者
     * @param listeners 听者（按顺序接话）
     * @param step      当前步：0=speaker，k(k≥1)=第 k 个听者
     * @param lastLine  上一位说的台词
     * @param round     当前轮数（0 起）：每轮 = 起话头 + 听者接话
     * @param group     整群参与者 UUID —— 释放时按它清整群，不依赖当轮 speaker/listeners
     */
    private void startAmbientTurn(VillagerState speaker, List<VillagerState> listeners, int step, String lastLine, int round,
                                  Set<UUID> group) {
        // 全部说完 → 尝试续一轮（两人来回），玩家已走或轮数到顶则释放
        if (step >= listeners.size() + 1) {
            continueAmbientChat(speaker, listeners, round, group);
            return;
        }
        // 硬约束：玩家离开 32 格立即停止。每一步（含 50 tick 延迟回调）开头都先查，
        // 不能只在整轮结束查 —— 否则玩家走光后，剩余步骤还会按原计划继续聊完
        if (!anyNearbyPlayerInRound(speaker, listeners)) {
            releaseAmbient(group);
            return;
        }
        VillagerState cur = step == 0 ? speaker : listeners.get(step - 1);
        String npcName = Smalltalk.nameOf(cur);

        String userContent;
        if (step == 0) {
            // 起话头：告诉说话者身边有谁，让他用自己人设起个话头
            StringBuilder neighborDesc = new StringBuilder();
            for (VillagerState l : listeners) {
                if (neighborDesc.length() > 0) neighborDesc.append("、");
                neighborDesc.append(Smalltalk.nameOf(l)).append("(").append(l.profession().zhName).append(")");
            }
            userContent = "[场景] " + PromptBuilder.compactScene(cur) + "。\n"
                    + "[村民闲聊] 你在村里遇到邻居：" + neighborDesc
                    + "。随口起个话头聊聊（家常、天气、收成、村里新鲜事都行），"
                    + "一句中文（≤24字），口语化，别用列表。";
            if (round > 0) {
                // 续轮：两人已经聊过几轮，起话头要顺着刚才的话题继续，别重开一个
                userContent += "\n（你们已经聊了 " + round + " 轮，顺着刚才的话题继续起个头，别客套别重开。）";
            }
        } else {
            // 接话：引用上一位说话人的台词，自然接一句
            // step 1 上一位是起话头者(speaker)，step≥2 上一位是第 step-2 个听者
            String lastSpeakerName = step == 1
                    ? Smalltalk.nameOf(speaker)
                    : Smalltalk.nameOf(listeners.get(step - 2));
            userContent = "[场景] " + PromptBuilder.compactScene(cur) + "。\n"
                    + "[村民闲聊] 邻居 " + lastSpeakerName
                    + " 刚才说：\"" + lastLine + "\"。你自然地接一句中文（≤24字），别复述原话。";
            if (round > 0) {
                userContent += "\n（第 " + (round + 1) + " 轮了，别复述别客套，把话题往前推一点。）";
            }
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content",
                        PromptBuilder.buildVillagerSystemPrompt(cur.profession(), npcName, cur.personalityTagIds(), cur)),
                Map.of("role", "user", "content", userContent));

        plugin.getLlmClient().chat(messages, List.of())
                .thenAccept(response -> {
                    // 硬约束：禁止硬编码兜底台词 —— 模型没给内容就透传 "..."（结构占位），
                    // 不再编"（嗯嗯）"假台词；气泡在下方按 "..." 跳过显示
                    String line = Smalltalk.stripToolCall(response.content());
                    if (line == null || line.isBlank()) line = "...";
                    if (line.length() > 50) line = line.substring(0, 50);
                    String finalLine = line;
                    int nextStep = step + 1;
                    // 主线程更新气泡 + 推进下一轮（间隔 2.5s，让观众看清对话节奏）
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (cur.entity().isValid()) {
                            // 台词写入近期社交上下文（同群聊逻辑：玩家跟它正式对话时能衔接，不当陌生人）
                            cur.addRecentSocialLine(finalLine);
                            // 模型没说话（占位 "..."）就不显示气泡 —— 禁止把占位符当台词展示
                            if (!"...".equals(finalLine)) {
                                // 闲聊气泡用固定长时长（15s）：玩家是旁观者、往往在远处，
                                // 动态计时对 24 字短句只有约 11 秒，跑近就没了
                                bubbleManager.showDialog(cur.entity(), npcName, finalLine, AMBIENT_BUBBLE_TICKS);
                            }
                        }
                        if (nextStep <= listeners.size()) {
                            Bukkit.getScheduler().runTaskLater(plugin,
                                    () -> startAmbientTurn(speaker, listeners, nextStep, finalLine, round, group), 50L);
                        } else {
                            // 本轮结束 → 尝试续下一轮
                            continueAmbientChat(speaker, listeners, round, group);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("村民闲聊 LLM 失败: " + ex.getMessage());
                    // 异常也按整群释放（group 里有全部成员），避免半数成员永久占位
                    Bukkit.getScheduler().runTask(plugin, () -> releaseAmbient(group));
                    return null;
                });
    }

    /**
     * 一轮说完后尝试续下一轮：最后接话的人起新话头、原起话头者接话，两人来回。
     * 玩家已离开 32 格或达到轮数上限则释放占位结束。
     */
    private void continueAmbientChat(VillagerState speaker, List<VillagerState> listeners, int round, Set<UUID> group) {
        int nextRound = round + 1;
        // 到达轮数上限、或参与者 32 格内都没有玩家 → 结束
        if (nextRound >= AMBIENT_MAX_ROUNDS || !anyNearbyPlayerInRound(speaker, listeners)) {
            // 按"整群 group"释放：续轮轮换过 speaker/listeners，首轮听者可能已不在
            // 当轮参数里，只按当轮参数清会漏人（漏进 ambientBusy 就永久占位）
            releaseAmbient(group);
            return;
        }
        // 续一轮：最后接话的听者起头，原起话头者接 → 两人来回
        VillagerState lastListener = listeners.get(listeners.size() - 1);
        List<VillagerState> nextListeners = List.of(speaker);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> startAmbientTurn(lastListener, nextListeners, 0, "", nextRound, group), 50L);
    }

    /** 当前轮参与者（说话者 + 听者）32 格内是否还有在线玩家 —— 每步开聊前都先过这道闸 */
    private boolean anyNearbyPlayerInRound(VillagerState speaker, List<VillagerState> listeners) {
        VillagerState[] involved = new VillagerState[listeners.size() + 1];
        involved[0] = speaker;
        for (int i = 0; i < listeners.size(); i++) {
            involved[i + 1] = listeners.get(i);
        }
        return anyNearbyPlayer(involved);
    }

    /** 任一参与闲聊的 NPC 附近 32 格内是否有在线玩家（续轮条件：有人看才继续聊） */
    private boolean anyNearbyPlayer(VillagerState... vss) {
        for (VillagerState vs : vss) {
            var e = vs.entity();
            if (e == null || !e.isValid()) continue;
            for (var p : Bukkit.getOnlinePlayers()) {
                if (p.isDead() || !p.getWorld().equals(e.getWorld())) continue;
                if (e.getLocation().distanceSquared(p.getLocation())
                        <= AMBIENT_PLAYER_VISIBILITY * AMBIENT_PLAYER_VISIBILITY) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 释放闲聊占位（异常中断/正常结束都要调，否则村民永远"忙"）。
     * 按"整群 group"清而不是按当轮 speaker/listeners 清 —— 续轮会轮换角色，
     * 只按当轮参数清会漏掉已掉出当轮的成员（如首轮听者 B），漏掉的 UUID 会永久
     * 留在 ambientBusy 里，攒到 ambientBusy.size()>=3 后自动闲聊永久停摆。
     */
    private void releaseAmbient(Set<UUID> group) {
        ambientBusy.removeAll(group);
    }
}
