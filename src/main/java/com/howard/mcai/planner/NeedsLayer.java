package com.howard.mcai.planner;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.BehaviorRegistry;
import com.howard.mcai.ai.LLMResponse;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.mob.NeedsState;
import com.howard.mcai.mob.VillagerState;
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

/**
 * 需求层（SPEC 2.14 需求系统）—— 全村范围、默认 30 秒/轮。
 * <p>
 * 主动性引擎，三件事：
 * <ol>
 *   <li><b>需求增长</b>：五项需求按时间 + 情境自然增长（性格标签修正涨速 ——
 *       吃货饿得快、社牛耐不住寂寞），睡觉/移动/聊天中的回落也在这一步做</li>
 *   <li><b>规则满足</b>（不调 LLM）：饿了且库存有食物 → 直接吃（清 hunger）；
 *       睡觉时回精力。动物式基础动作走规则，符合"规则打底，LLM 做社交时刻"的分层原则</li>
 *   <li><b>LLM 自主行动</b>：需求越过阈值（默认 80）且 NPC 空闲 → 触发一次"目标决策"
 *       调用，模型按需求 + 性格选一个工具（找吃/闲逛/找人聊/探索），行动后削减对应需求。
 *       有内在驱动，不等玩家指令 —— 这是"放一个 NPC 不管 10 分钟自己活起来"的来源</li>
 * </ol>
 * <p>
 * 成本护栏（本地模型不烧穿）：
 * <ul>
 *   <li>自主行动只在 32 格内有在线玩家时触发（没人看不动脑，与闲聊同一约束）</li>
 *   <li>每个 NPC 独立冷却（默认 120 秒），且每轮 tick 全村最多触发 1 次决策调用</li>
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

            // === 2.5 真睡觉：疲惫 ≥70 + 天黑 → 找床真睡（不是干站着）
            // 之前 autonomy 只会 move_to"走回家"，没有真正上床，疲惫永远下不来
            if (!sleeping && night && needs.get(NeedsState.ENERGY) >= 70) {
                trySleep(vs);
            }

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

    // ===== 规则满足：真睡觉 =====

    /**
     * 真睡觉（规则动作，不调 LLM）：
     * 疲惫 ≥70 + 天黑 → 找 16 格内的床；走到床边（2.5 格内）就上床睡。
     * 睡着后本层每轮按 ENERGY_SLEEP 回疲惫，天亮原版 AI 叫醒。
     * 没床 → 不瞎跑（原地待着），等天亮或玩家搭话。
     */
    private void trySleep(VillagerState vs) {
        Villager v = vs.entity();
        if (v.isSleeping() || v.isDead()) return;
        Location bed = findNearbyBed(v, 16);
        if (bed == null) return;  // 没床睡不了，别梦游
        if (v.getLocation().distanceSquared(bed) <= 2.5 * 2.5) {
            if (v.sleep(bed)) {
                plugin.getLogger().info("§b[MC-AI] 睡觉: " + Smalltalk.nameOf(vs)
                        + " 上床了（疲惫 " + (int) vs.needs().get(NeedsState.ENERGY) + "）");
            }
            return;
        }
        // 还没走到：挪过去（walkTowards 每 20 tick 走一步，对话中自动取消；下几轮 tick 走到就睡）
        walkTowards(vs, bed.clone().add(0.5, 0, 0.5));
    }

    /** 16 格内找最近的床（y 上下 2 格），没有返回 null */
    private Location findNearbyBed(Villager v, int radius) {
        org.bukkit.World w = v.getWorld();
        Location c = v.getLocation();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        Location best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - 2; y <= cy + 2; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().name().endsWith("_BED")) continue;
                    double d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = b.getLocation();
                    }
                }
            }
        }
        return best;
    }

    // ===== LLM 自主行动 =====

    /**
     * 需求越阈值 → 触发一次"目标决策"LLM 调用。
     * 护栏逐条：配置开关 / 阈值 / 对话中 / LLM 忙 / 冷却 / 32 格内有观众。
     */
    private void maybeAutonomousAction(VillagerState vs, long cooldownMs) {
        var cfg = plugin.getPluginConfig();
        if (vs.interactingPlayer() != null || vs.llmBusy()) return;
        // 睡着了别折腾：疲惫 ≥70 睡觉时会触发 trySleep 规则，LLM 再插一脚只会把人叫醒
        if (vs.entity().isSleeping()) return;
        // 下雨天不瞎逛：村民躲雨是常识，vanilla AI 会找遮蔽；需求等雨停再说
        if (vs.entity().getWorld().hasStorm()) return;
        String dominant = vs.needs().dominantUrgent(cfg.needsThreshold());
        if (dominant == null) return;
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
        triggerAutonomyLLM(vs, dominant);
    }

    /**
     * 发起自主决策调用 —— 一次性 prompt（不写 chatHistory），
     * 工具面收窄为安全集合（say/move_to/follow_player/look_at/wave/nod/idle），
     * 需求驱动的自主行动不该开店/拆家。
     */
    private void triggerAutonomyLLM(VillagerState vs, String dominant) {
        NeedsState needs = vs.needs();
        StringBuilder needsJson = new StringBuilder("{");
        var snap = needs.snapshot();
        boolean first = true;
        for (var e : snap.entrySet()) {
            if (!first) needsJson.append(", ");
            needsJson.append("\"").append(e.getKey()).append("\": ").append(e.getValue().intValue());
            first = false;
        }
        needsJson.append("}");

        // 按最迫切需求给一条"人话"行动提示（模型按需选工具，不做选择题之外的发散）
        String hint = switch (dominant) {
            case NeedsState.HUNGER -> "你饿了。可以 move_to 去田里/铺子找吃的，或 say 嘀咕一句肚子饿";
            case NeedsState.ENERGY -> "你困得不行。move_to 走回家睡觉";
            case NeedsState.BOREDOM -> "闲得发慌。move_to 去别处转转，或 say 嘀咕一句想找点乐子";
            case NeedsState.CURIOSITY -> "好奇心作祟。move_to 去围观让你好奇的东西";
            case NeedsState.SOCIAL -> "想找人说说话。say 喊一嗓子，或 move_to 凑到最近的人旁边";
            default -> "按你的需求选个行动";
        };

        String userContent = "[自主行动] 没有玩家跟你说话 —— 你在过自己的日子。\n"
                + "当前需求: " + needsJson + "\n"
                + "[场景] " + PromptBuilder.compactScene(vs) + "。\n"
                + "最迫切的需求是 " + NeedsState.zhName(dominant) + "（数值 " + (int) needs.get(dominant) + "）。"
                + hint + "。只调用一个工具，message 参数里说一句中文（≤20字）。\n"
                + "（注：message 里的内容玩家听得见，但这段行动不会进入你的正式记忆）";

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content",
                        PromptBuilder.buildVillagerSystemPrompt(vs.profession(),
                                Smalltalk.nameOf(vs), vs.personalityTagIds(), vs)),
                Map.of("role", "user", "content", userContent));

        plugin.getLogger().info("§b[MC-AI] 需求驱动: " + Smalltalk.nameOf(vs)
                + " " + NeedsState.zhName(dominant) + "=" + (int) needs.get(dominant) + " → 触发自主行动");

        plugin.getLlmClient().chat(messages, BehaviorRegistry.autonomyToolSchemas())
                .thenAccept(response -> Bukkit.getScheduler().runTask(plugin,
                        () -> executeAutonomy(vs, response, dominant)))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("自主行动 LLM 失败: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * 执行自主行动结果（主线程）—— 轻量执行器：
     * 台词冒气泡 + 附近玩家聊天框（不写 chatHistory），动作按工具类型落地，
     * 行动完成后削减触发需求（需求满足后降低）。
     */
    private void executeAutonomy(VillagerState vs, LLMResponse response, String dominant) {
        Villager v = vs.entity();
        if (!v.isValid() || v.isDead()) return;

        var toolCall = response.hasToolCall() ? response.firstToolCall() : null;
        String toolName = toolCall != null && BehaviorRegistry.isValidTool(toolCall.name())
                ? toolCall.name() : null;

        // 台词：tool message 优先，正文兜底；空 → "..." 透传（禁止硬编码兜底台词）
        String dialogue = toolCall != null ? jsonStr(toolCall, "message") : null;
        if (dialogue == null || dialogue.isBlank()) {
            dialogue = response.content();
        }
        dialogue = Smalltalk.stripToolCall(dialogue);
        if (dialogue == null || dialogue.isBlank()) dialogue = "...";
        String finalDialogue = CentralPlanner.sanitizeDialogue(dialogue, vs.profession());

        // 动作落地
        Player audience = nearestPlayer(v, plugin.getPluginConfig().needsVisibilityRadius());
        if (toolName != null) {
            switch (toolName) {
                case "move_to" -> {
                    double tx = jsonNum(toolCall, "x", v.getLocation().getX());
                    double ty = jsonNum(toolCall, "y", v.getLocation().getY());
                    double tz = jsonNum(toolCall, "z", v.getLocation().getZ());
                    walkTowards(vs, new Location(v.getWorld(), tx, ty, tz));
                }
                case "follow_player" -> {
                    if (audience != null) walkTowards(vs, audience.getLocation());
                }
                case "wave", "nod" -> playGesture(v, toolName);
                case "look_at" -> {
                    if (audience != null) CentralPlanner.faceTowards(v, audience.getLocation());
                }
                // say / idle：无额外动作
                default -> { }
            }
        }

        // 台词展示：**只冒气泡**（15 秒固定时长，同闲聊）+ 控制台留档。
        // 规则：聊天框文字只发给"正在跟该 NPC 对话的那个玩家"（CentralPlanner.speak 负责），
        // 其他玩家一律只看到头顶气泡。自主行动时根本没有人在跟它聊天，
        // 所以不往任何人的聊天框写字 —— 否则最近的玩家会莫名收到一段没头没尾的台词。
        String name = Smalltalk.nameOf(vs);
        if (!"...".equals(finalDialogue)) {
            bubbleManager.showDialog(v, name, finalDialogue, 300L);
            Bukkit.getConsoleSender().sendMessage("§6[" + name + "] §f" + finalDialogue);
            // 写入近期社交上下文（顺带缓解 social/boredom —— 说话本身就是社交满足）
            vs.addRecentSocialLine(finalDialogue);
        }

        // 需求满足：行动完成后削减触发需求
        vs.needs().satisfy(dominant, NeedsState.SATISFY_ON_ACTION);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] 自主行动完成: " + name + " " + toolName
                + " → " + NeedsState.zhName(dominant) + " 降至 " + (int) vs.needs().get(dominant));
    }

    /** 朝目标连续走几步（setVelocity 单次走不远，每 20 tick 补一步共 5 步，对话中取消） */
    private void walkTowards(VillagerState vs, Location target) {
        Villager v = vs.entity();
        for (int i = 0; i < 5; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!v.isValid() || v.isDead() || vs.interactingPlayer() != null) return;
                CentralPlanner.moveEntityTowards(v, target, 1.0);
            }, 20L * i);
        }
    }

    /** 肢体动作（同 CentralPlanner.playGesture：wave=起跳 / nod=前跳） */
    private void playGesture(Villager v, String action) {
        if (action.equals("wave")) {
            v.setVelocity(new org.bukkit.util.Vector(0, 0.45, 0));
        } else if (action.equals("nod")) {
            var dir = v.getLocation().getDirection().normalize().multiply(0.3);
            v.setVelocity(new org.bukkit.util.Vector(dir.getX(), 0.25, dir.getZ()));
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

    private static String jsonStr(com.howard.mcai.ai.ToolCall tool, String key) {
        var obj = tool.arguments();
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static double jsonNum(com.howard.mcai.ai.ToolCall tool, String key, double def) {
        var obj = tool.arguments();
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }
}
