package com.howard.mcai.planner;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.LlmToolAdapter;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.ai.ToolCall;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.mob.NeedsState;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.NpcTask;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolRequest;
import com.howard.mcai.tool.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 需求层（SPEC 2.14 需求系统）—— 全村范围、默认 30 秒/轮。
 * <p>
 * 主动性引擎，三件事：
 * <ol>
 *   <li><b>需求增长</b>：五项需求按时间 + 情境自然增长（性格标签修正涨速 ——
 *       吃货饿得快、社牛耐不住寂寞），睡觉/移动/聊天中的回落也在这一步做</li>
 *   <li><b>规则满足</b>（不调 LLM）：饿了且库存有食物 → 直接吃（清 hunger）；
 *       睡觉时回精力。动物式基础动作走规则，符合"规则打底，LLM 做社交时刻"的分层原则</li>
 *   <li><b>插件自主行动</b>：需求越过阈值（默认 80）且 NPC 空闲 → 触发一次"目标决策"
 *       插件按需求紧迫度 + 距离 + 性格给候选动作打分选一个（找吃/闲逛/找人聊/探索），行动后削减对应需求。
 *       有内在驱动，不等玩家指令 —— 这是"放一个 NPC 不管 10 分钟自己活起来"的来源</li>
 * </ol>
 * <p>
 * 成本护栏（本地模型不烧穿）：
 * <ul>
 *   <li>自主行动只在 32 格内有在线玩家时触发（没人看不动脑，与闲聊同一约束）</li>
 *   <li>每个 NPC 独立冷却（默认 120 秒），且每轮 tick 全村最多触发 1 次自主决策</li>
 *   <li>对话中 / LLM 忙 / 正在闲聊占位的 NPC 一律跳过</li>
 * </ul>
 * 自主行动的台词<b>不写入 chatHistory</b>（同闲聊：背景生活不污染正式记忆），
 * 走 addRecentSocialLine —— 玩家随后搭话时模型能自然衔接"刚才我正说着饿呢"。
 */
public final class NeedsLayer extends TickLayer {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final BubbleManager bubbleManager;

    /** 每个村民的自主行动冷却（UUID → 上次触发毫秒） */
    private final Map<UUID, Long> autonomyCooldown = new HashMap<>();

    /** 上一轮每村民附近的生物数（好奇心事件源：数量变多 = 看到新东西） */
    private final Map<UUID, Integer> prevNearbyCount = new HashMap<>();

    /** 每轮 tick 全村最多触发 1 次自主决策调用（硬性限流） */
    private boolean autonomyTriggeredThisRound;

    /** onTick 轮次计数 —— 需求渐变落盘节流用（每 10 轮补一次，不用每轮全量写盘） */
    private long tickCount = 0;

    // ===== 增长速率（每分钟基准值，config 未覆盖时用默认） =====
    private static final double HUNGER_RATE = 10.0;   // 0→80 约 8 分钟（验收：10 分钟内出 3 种行为）
    private static final double BOREDOM_IDLE = 9.0;   // 无所事事时
    private static final double BOREDOM_BUSY = 2.0;   // 有事做时
    private static final double BOREDOM_MOVE = 4.0;   // 走动时回落
    private static final double CURIOSITY_RATE = 3.0;
    private static final double CURIOSITY_BUMP = 8.0; // 看到新东西的脉冲
    private static final double CURIOSITY_MOVE = 6.0; // 探索途中自然满足
    private static final double SOCIAL_ALONE = 8.0;   // 独处涨
    private static final double SOCIAL_COMPANY = 2.0; // 有伴涨得慢
    private static final double SOCIAL_CHAT = 20.0;   // 跟玩家对话时大幅回落
    private static final double ENERGY_SLEEP = 25.0;  // 睡觉恢复（疲惫下降）
    private static final double ENERGY_NIGHT = 8.0;   // 夜间熬夜涨疲惫
    private static final double ENERGY_DAY = 4.0;     // 白天干活涨疲惫

    public NeedsLayer(McaiPlugin plugin, Map<UUID, VillagerState> villagers, BubbleManager bubbleManager) {
        super("needs", plugin.getPluginConfig().needsIntervalSeconds() * 20);
        this.plugin = plugin;
        this.villagers = villagers;
        this.bubbleManager = bubbleManager;
    }

    @Override
    protected void onTick(CentralPlanner planner) {
        var cfg = plugin.getPluginConfig();
        double minutes = cfg.needsIntervalSeconds() / 60.0;
        autonomyTriggeredThisRound = false;
        tickCount++;

        for (VillagerState vs : List.copyOf(villagers.values())) {
            Villager v = vs.entity();
            if (!v.isValid() || v.isDead()) continue;
            NeedsState needs = vs.needs();

            // === 1. 情境判定（全在主线程，Paper API 安全） ===
            boolean sleeping = v.isSleeping();
            // 睡姿纠正（2026-10-21 实测：隔墙躺草地"睡"满一夜）：原版 AI 偶尔让村民在
            // 不是床的位置进入睡眠，只认 isSleeping 就回精力、从不核查躺哪。
            // 身下两层 3×3 内没有真床 = 躺地上了 → 叫醒，原版大脑会自己带他回床。
            // 注：Paper 没有通用的"进入睡眠"事件可拦（只有玩家版），
            // 巡查叫醒就是正解 —— 犯一次最多躺一个巡查间隔（约 30 秒）。
            if (sleeping && !isOnBed(v)) {
                v.wakeup();
                sleeping = false;
                plugin.getLogger().info("§b[MC-AI] 睡姿纠正: " + Smalltalk.nameOf(vs)
                        + " 没睡在床上，已叫醒");
            }
            boolean moving = v.getVelocity().lengthSquared() > 0.01;
            boolean conversing = vs.interactingPlayer() != null;
            boolean night = !v.getWorld().isDayTime();
            boolean raining = v.getWorld().hasStorm();
            int nearbyCount = countNearby(v);
            boolean sawNewThings = nearbyCount > prevNearbyCount.getOrDefault(vs.getUniqueId(), 0);
            prevNearbyCount.put(vs.getUniqueId(), nearbyCount);
            boolean alone = nearbyCount == 0;

            // === 2. 需求增长（性格标签修正涨速；睡觉时全体需求放缓 ×0.3，能量反向恢复） ===
            var tagIds = vs.personalityTagIds();
            if (!sleeping) {
                needs.add(NeedsState.HUNGER,
                        HUNGER_RATE * minutes * NeedsState.combinedMultiplier(tagIds, NeedsState.HUNGER));
            } else {
                needs.add(NeedsState.HUNGER, -HUNGER_RATE * minutes * 0.3);  // 睡觉饿了也饿得慢
            }
            // 疲惫语义（越高越累）：醒着涨疲惫（熬夜涨得快），睡觉恢复。
            // 旧版符号反了（睡觉 +25、醒着 −8），导致"睡得越香越被判定困得不行"、
            // 真累了反而永远触发不了睡觉 —— 2026-10-20 修正。
            needs.add(NeedsState.ENERGY, sleeping
                    ? -ENERGY_SLEEP * minutes
                    : (night ? ENERGY_NIGHT : ENERGY_DAY) * minutes);
            needs.add(NeedsState.BOREDOM,
                    (moving ? -BOREDOM_MOVE : (conversing ? 0 : (isIdle(vs) ? BOREDOM_IDLE : BOREDOM_BUSY))) * minutes
                            + (conversing ? -8.0 * minutes : 0));
            needs.add(NeedsState.CURIOSITY,
                    (CURIOSITY_RATE * minutes * NeedsState.combinedMultiplier(tagIds, NeedsState.CURIOSITY))
                            + (sawNewThings ? CURIOSITY_BUMP : 0)
                            - (moving ? CURIOSITY_MOVE * minutes : 0));
            needs.add(NeedsState.SOCIAL,
                    (alone ? SOCIAL_ALONE : SOCIAL_COMPANY) * minutes
                            * NeedsState.combinedMultiplier(tagIds, NeedsState.SOCIAL)
                            - (conversing ? SOCIAL_CHAT * minutes : 0));

            // === 3. 规则满足：饿了 + 库存有食物 → 直接吃（不调 LLM，谁都会吃饭） ===
            if (!sleeping && needs.get(NeedsState.HUNGER) >= 70) {
                eatFromInventory(vs);
            }

            // 睡觉时顺带缓解无聊/孤独（睡得香就不闷了）
            if (sleeping) {
                needs.satisfy(NeedsState.BOREDOM, 2.0 * minutes * 10);
            }

            // === 2.5 睡觉归原版（Fong 2026-10-23 定）：本层不再找床、不再送睡。
            // 原版大脑按作息带他回自己认领的床；这里只记账 —— 睡着时 ENERGY
            // 已在上面按 ENERGY_SLEEP 回落，没睡的人整夜涨疲惫，第二天顶着
            // 高疲惫过日子（状态档位与台词都认这个数）。

            // 需求渐变是"软状态"：离散事件（吃东西/自主行动/需求满足）各自已 markDirty，
            // 这里每 10 轮（约 5 分钟）补一次即可。之前每 30 秒全村全量写盘，
            // 写文件走主线程，村民一多就是周期性卡顿。
            if (tickCount % 10 == 0) {
                plugin.getMemoryStore().markDirty(vs.getUniqueId());
            }

            // === 4. LLM 自主行动（限流：对话中/忙/冷却/没观众/本轮已触发 → 跳过） ===
            if (cfg.needsEnabled() && !autonomyTriggeredThisRound) {
                maybeAutonomousAction(vs, (long) (minutes * 60.0 * 1000.0));
            }
        }
    }

    /** 12 格内生物数（玩家 + 村民 + 动物，排除自己）—— 独处/好奇心的判定源 */
    private int countNearby(Villager v) {
        int n = 0;
        for (LivingEntity e : v.getWorld().getNearbyLivingEntities(v.getLocation(), 12)) {
            if (e == v) continue;
            n++;
        }
        return n;
    }

    /** 真正"无所事事"：没在对话、没在走路、没在睡觉（闲聊占位查不到，容忍误差） */
    private boolean isIdle(VillagerState vs) {
        return vs.interactingPlayer() == null;
    }

    // ===== 规则满足：吃 =====

    /**
     * 从库存找食物吃一口 —— 纯规则零成本。
     * 食物优先级：先好东西（金苹果/熟食）后普通（面包）。吃完冒一个动作气泡。
     */
    private void eatFromInventory(VillagerState vs) {
        Material[] foods = {
                Material.GOLDEN_APPLE, Material.COOKED_BEEF, Material.COOKED_PORKCHOP,
                Material.COOKED_CHICKEN, Material.COOKED_MUTTON, Material.COOKED_COD,
                Material.COOKED_SALMON, Material.BAKED_POTATO, Material.BREAD,
                Material.APPLE, Material.CARROT, Material.POTATO};
        for (Material food : foods) {
            if (vs.stockOf(food) > 0) {
                vs.removeFromInventory(food, 1);
                vs.needs().satisfy(NeedsState.HUNGER, NeedsState.SATISFY_ON_EAT);
                // 吃东西的样子：摆手 + 咀嚼音效 —— 不然就是凭空扣库存冒个气泡，像变魔术
                vs.entity().swingMainHand();
                vs.entity().getWorld().playSound(vs.entity().getLocation(),
                        org.bukkit.Sound.ENTITY_GENERIC_EAT, 1.0f, 0.9f);
                String zh = PromptBuilder.materialToZh(food);
                bubbleManager.showDialog(vs.entity(), Smalltalk.nameOf(vs), "（掏出" + zh + "啃了几口，真香）");
                plugin.getMemoryStore().markDirty(vs.getUniqueId());
                plugin.getLogger().info("§b[MC-AI] 需求规则: " + Smalltalk.nameOf(vs)
                        + " 吃掉 " + zh + "（hunger → " + (int) vs.needs().get(NeedsState.HUNGER) + "）");
                return;
            }
        }
    }

    // ===== 睡觉：交给原版（Fong 2026-10-23 定） =====

    /**
     * 睡觉不再归插件管：旧版按插件疲惫值就近找一张床（谁的都行）走过去
     * 用 v.sleep() 躺下，看着像睡了，其实床不是他认下的、作息也不是原版的，
     * Fong 实测评价「假睡觉」。现在整段删掉，让原版村民大脑按自己的作息
     * 回他认领的床真睡：插件只在旁边看 isSleeping —— 睡着时回精力、
     * 躺错地方（身下没床）照旧巡查叫醒；没床认领的人夜里就困着，
     * 不拿假躺兜底（Fong 拍板）。疲惫（ENERGY）仍由本层记账：
     * 睡着回、醒着涨，一夜没睡第二天就顶着高疲惫过日子。
     */

    /** 睡着时核查躺的位置：同层与下一层 3×3 内有真床方块才算睡在床上（躺床时实体就贴在床面上，两层必有一层是床） */
    private boolean isOnBed(Villager v) {
        Location loc = v.getLocation();
        org.bukkit.World w = v.getWorld();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (w.getBlockAt(bx + dx, by + dy, bz + dz).getType().name().endsWith("_BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ===== LLM 自主行动 =====

    /**
     * 需求越阈值 → 触发一次"目标决策"LLM 调用。
     * 护栏逐条：配置开关 / 阈值 / 对话中 / LLM 忙 / 冷却 / 32 格内有观众。
     */
    private void maybeAutonomousAction(VillagerState vs, long cooldownMs) {
        var cfg = plugin.getPluginConfig();
        if (vs.interactingPlayer() != null || vs.llmBusy()) return;
        // 睡着了别折腾：自主行动再插一脚只会把人叫醒
        if (vs.entity().isSleeping()) return;
        // 下雨天不瞎逛：村民躲雨是常识，vanilla AI 会找遮蔽；需求等雨停再说
        if (vs.entity().getWorld().hasStorm()) return;
        String dominant = vs.needs().dominantUrgent(cfg.needsThreshold());
        if (dominant == null) return;
        // 夜里困意当头时别派活（睡觉归原版）：原版大脑正带他回床睡觉，
        // 插件这时塞一个闲逛/社交任务就是跟睡觉抢人 —— 让路，等天亮再说
        if (NeedsState.ENERGY.equals(dominant) && !vs.entity().getWorld().isDayTime()) return;
        long now = System.currentTimeMillis();
        Long cd = autonomyCooldown.get(vs.getUniqueId());
        if (cd != null && now - cd < cfg.needsAutonomyCooldownSeconds() * 1000L) return;
        // 没观众不动脑（32 格内无在线玩家 → 省 token，与闲聊触发同一约束）
        Villager v = vs.entity();
        boolean playerNear = false;
        for (Player p : v.getWorld().getPlayers()) {
            if (p.isOnline() && !p.isDead()
                    && v.getLocation().distanceSquared(p.getLocation())
                    <= cfg.needsVisibilityRadius() * cfg.needsVisibilityRadius()) {
                playerNear = true;
                break;
            }
        }
        if (!playerNear) return;

        autonomyCooldown.put(vs.getUniqueId(), now);
        autonomyTriggeredThisRound = true;
        triggerAutonomy(vs, dominant);
    }

    /**
     * 自主行动（纯插件决策，v0.1.4 起无行为模型）：
     * 候选动作由 {@link #buildCandidates} 生成（全部预先校验），
     * {@link AutonomyDecider} 按需求紧迫度/距离/任务状态/性格打分选一个，
     * 执行统一走 ToolDispatcher。纯计算、无 IO，在 tick 主线程内同步完成。
     */
    private void triggerAutonomy(VillagerState vs, String dominant) {
        NpcTask activeTask = plugin.getNpcTaskManager() == null
                ? null : plugin.getNpcTaskManager().activeFor(vs.getUniqueId());
        List<AutonomyDecider.Candidate> candidates = buildCandidates(vs, dominant, activeTask);

        plugin.getLogger().info("§b[MC-AI] 需求驱动(插件决策): " + Smalltalk.nameOf(vs)
                + " " + NeedsState.zhName(dominant) + "=" + (int) vs.needs().get(dominant)
                + " 候选: " + describeCandidates(candidates));

        AutonomyDecider.Decision decision = AutonomyDecider.decide(
                dominant, vs.needs().get(dominant), vs.openness(),
                activeTask != null, candidates, ThreadLocalRandom.current());
        if (decision.idle()) {
            plugin.getLogger().info("§b[MC-AI] 自主行动(插件决策) idle: " + Smalltalk.nameOf(vs));
            return;
        }

        AutonomyDecider.Candidate picked = candidates.get(decision.index());
        ToolRequest request = new LlmToolAdapter(plugin.getToolRegistry()).toRequest(
                new ToolCall(picked.action(), picked.args()),
                ToolContext.CallerType.AUTONOMY, "needs-autonomy", vs.getUniqueId(), null);
        if (request == null) {
            plugin.getLogger().warning("自主行动(插件决策) 候选构造请求失败: " + picked.action());
            return;
        }
        ToolResult result = plugin.getToolDispatcher().dispatchSync(request);
        plugin.getLogger().info("§b[MC-AI] 自主行动(插件决策) " + Smalltalk.nameOf(vs)
                + " 选择#" + decision.index() + " → " + request.tool() + " " + result.status()
                + (result.success() ? "" : " " + result.errorCode() + ": " + result.errorMessage()));
        if (!result.success()) return;

        // 建任务的动作（move_to 等）只是"被接受"，还没真干完：需求先挂账，
        // 等任务完成由执行方结算（NpcMoveToTool 调 consumePendingNeedSatisfy），
        // 失败就丢弃——一接受就降 35 点的话，不可达目标白折腾一趟也照样"满足"。
        if (result.data() != null && result.data().has("task_id")) {
            vs.setPendingNeedSatisfy(dominant);
            return;
        }
        vs.needs().satisfy(dominant, NeedsState.SATISFY_ON_ACTION);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
    }

    /** 候选清单的日志摘要：[0]idle [1]npc.move_to{...}，一行打完便于对照模型返回的序号 */
    private static String describeCandidates(List<AutonomyDecider.Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            AutonomyDecider.Candidate c = candidates.get(i);
            if (i > 0) sb.append(' ');
            sb.append('[').append(i).append(']').append(c.action());
            if (!c.args().isEmpty()) sb.append(c.args());
        }
        return sb.toString();
    }

    /**
     * 生成候选动作。硬性要求：每个候选都必须能通过 schema 校验并真的可执行，
     * 否则"候选模式"只是把非法输出换了个地方出现。
     */
    private List<AutonomyDecider.Candidate> buildCandidates(
            VillagerState vs, String dominant, NpcTask activeTask) {
        List<AutonomyDecider.Candidate> candidates = new ArrayList<>();
        candidates.add(new AutonomyDecider.Candidate("idle",
                "do nothing and stay where you are", new JsonObject(), 0));

        // 已有任务在跑时不能再给 move_to：NpcTaskManager 规定同一 NPC 只能有一个任务，
        // 这个候选 100% 会被拒成 NPC_BUSY，等于给模型挖坑。
        Location target = activeTask == null ? candidateTarget(vs, dominant) : null;
        if (target != null) {
            JsonObject args = new JsonObject();
            args.addProperty("x", target.getX());
            args.addProperty("y", target.getY());
            args.addProperty("z", target.getZ());
            args.addProperty("speed", 0.8);
            candidates.add(new AutonomyDecider.Candidate("npc.move_to",
                    "walk to x=" + Math.round(target.getX()) + " y=" + Math.round(target.getY())
                            + " z=" + Math.round(target.getZ()), args,
                    target.distance(vs.entity().getLocation())));
        }

        JsonObject observeArgs = new JsonObject();
        observeArgs.addProperty("radius", 16);
        observeArgs.addProperty("limit", 16);
        candidates.add(new AutonomyDecider.Candidate("world.observe.entities",
                "look around at nearby entities", observeArgs, 0));

        if (activeTask != null) {
            JsonObject cancelArgs = new JsonObject();
            cancelArgs.addProperty("task_id", activeTask.id());
            candidates.add(new AutonomyDecider.Candidate("npc.task.cancel",
                    "stop the current action (" + activeTask.toolName() + ")", cancelArgs, 0));
        }
        return candidates;
    }

    /**
     * 按主导需求挑一个可走到的目标点：社交需求走向最近的玩家，其余需求在 6-12 格内随机转转。
     * Y 固定为 NPC 当前高度，确保满足 move_to 的 3 格高度差限制。
     * <p>
     * 随机点必须先验可达（实测教训）：旧版纯几何乱扔点，扔到自家小屋墙外，
     * 她贴墙蹭了近半分钟才被判失败。现改为：最多试 3 个点，每个点先问原版导航
     * "有没有路"，再避开 15 分钟内刚失败过的区域；都不过 → 这轮不出门（只剩 idle/观察候选）。
     */
    private Location candidateTarget(VillagerState vs, String dominant) {
        Villager villager = vs.entity();
        Location here = villager.getLocation();
        if (NeedsState.SOCIAL.equals(dominant)) {
            Player nearest = nearestPlayer(villager, 16.0);
            if (nearest != null) {
                Location loc = nearest.getLocation();
                Location target = new Location(here.getWorld(), loc.getX(), here.getY(), loc.getZ());
                return reachable(villager, target) ? target : null;
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double distance = 6.0 + ThreadLocalRandom.current().nextDouble() * 6.0;
            Location target = new Location(here.getWorld(),
                    here.getX() + Math.cos(angle) * distance,
                    here.getY(),
                    here.getZ() + Math.sin(angle) * distance);
            if (vs.recentlyFailedNear(target, 6.0, 15 * 60_000L)) continue;
            if (!reachable(villager, target)) continue;
            return target;
        }
        return null;
    }

    /** 原版导航能不能算出一条到目标的路（只试算不出发）。室内的点隔着墙会直接没路，正好滤掉。 */
    private static boolean reachable(Villager villager, Location target) {
        try {
            return villager.getPathfinder().findPath(target) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private Player nearestPlayer(Villager v, double radius) {
        Player best = null;
        double bestDist = radius * radius;
        for (Player p : v.getWorld().getPlayers()) {
            if (!p.isOnline() || p.isDead()) continue;
            double d = v.getLocation().distanceSquared(p.getLocation());
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

}
