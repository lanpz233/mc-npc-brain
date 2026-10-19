package com.howard.mcai.planner;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.entity.Villager;

import java.util.Map;
import java.util.UUID;

/**
 * 社交层（SPEC 2.5）—— 全村范围、低频（默认 60 秒/轮）。
 * <p>
 * 只更新关系、记忆、八卦传播，**不产生气泡、不做行为** —— 玩家不在场也照常跑。
 * 纯规则 roll 点，不调 LLM，性能开销忽略不计。
 *
 * 本轮职责：
 * 1. 村民间八卦传话（原 CentralPlanner.maybeGossip 迁移）—— 见闻在 NPC 间慢慢扩散
 * 2. favor 缓慢衰减（SPEC 3.2 遗忘曲线：很久没见 → 慢慢归零）
 */
public final class SocialLayer extends TickLayer {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final BubbleManager bubbleManager;

    /** 村民闲聊冷却（UUID → 上次闲聊时间戳），防止高频刷屏 */
    private final Map<UUID, Long> lastGossipAt = new java.util.HashMap<>();

    /** 每个村民最多 2 分钟传一次话 */
    private static final long GOSSIP_COOLDOWN_MS = 120_000L;

    /** 每轮传话基础概率 2% —— 实际概率 = 2% + gossipProne()*10%（大嘴巴 0.5 → 7%） */
    private static final double GOSSIP_CHANCE = 0.02;

    /** 8 格内才有传话对象 */
    private static final double GOSSIP_RADIUS = 8.0;

    /** 每轮（60 秒）favor 向 0 收敛 0.5 —— 很久没见慢慢淡忘 */
    private static final double FAVOR_DECAY_PER_ROUND = 0.5;

    public SocialLayer(McaiPlugin plugin, Map<UUID, VillagerState> villagers, BubbleManager bubbleManager) {
        super("social", plugin.getPluginConfig().socialIntervalTicks());
        this.plugin = plugin;
        this.villagers = villagers;
        this.bubbleManager = bubbleManager;
    }

    /** 上次补货的"天数"（getFullTime()/24000），跨天触发一次 */
    private long lastRestockDay = -1;

    @Override
    protected void onTick(CentralPlanner planner) {
        for (VillagerState vState : villagers.values()) {
            // 职业刷新：村民刚加载时职业常为 none，认领工作站后才真的上工 ——
            // 每 60 秒全村重读一次，把"有职业却显示无业"纠正过来（职业没变则零开销）
            plugin.refreshVillagerProfession(vState);
            maybeGossip(vState);
            decayFavor(vState);
        }
        dailyRestock();
    }

    /**
     * 每日补货（SPEC 2.13"NPC 每天按职业补少量基础物资"）：
     * 商店卖空不能永久空店。每天（游戏时间）一次：
     * <ul>
     *   <li>口粮保底：面包少于 4 → 补到 8（别让村民饿死）</li>
     *   <li>职业招牌：seedProfessionPool 补 2-3 种职业物品（小量）</li>
     *   <li>囤货超 96 件的不补（防背包无限膨胀）</li>
     * </ul>
     */
    private void dailyRestock() {
        long day = -1;
        for (VillagerState vs : villagers.values()) {
            if (vs.entity().isValid()) {
                day = vs.entity().getWorld().getFullTime() / 24000L;
                break;
            }
        }
        if (day < 0 || day == lastRestockDay) return;
        lastRestockDay = day;
        int n = 0;
        for (VillagerState vs : java.util.List.copyOf(villagers.values())) {
            if (!vs.entity().isValid() || vs.entity().isDead()) continue;
            int total = vs.inventorySnapshot().values().stream()
                    .mapToInt(i -> i == null ? 0 : i).sum();
            if (total > 96) continue;
            int bread = vs.stockOf(org.bukkit.Material.BREAD);
            if (bread < 8) {
                vs.addToInventory(org.bukkit.Material.BREAD, 8 - bread);
            }
            vs.seedProfessionPool();
            plugin.getMemoryStore().markDirty(vs.getUniqueId());
            n++;
        }
        if (n > 0) {
            plugin.getLogger().info("§b[MC-AI] 每日补货: " + n + " 个村民（第 " + day + " 天）");
        }
    }

    /**
     * 村民闲聊传话（NPC↔NPC 学习）—— 低频，不调 LLM。
     * A 从自己的见闻（facts）里挑一条讲给 B；B 按**自己的开放度**概率决定记不记。
     * 记住了 → 进 B 的永久记忆（带出处）；没记住 → 摇摇头。都不打断玩家对话。
     */
    private void maybeGossip(VillagerState aState) {
        Villager a = aState.entity();
        // 冷却中 / 正在跟玩家对话 → 不传话
        if (System.currentTimeMillis() - lastGossipAt.getOrDefault(aState.getUniqueId(), 0L) < GOSSIP_COOLDOWN_MS) return;
        if (aState.interactingPlayer() != null) return;
        if (aState.facts().isEmpty()) return;
        // 每轮概率按大嘴巴程度动态化：普通村民 3%，抽到大嘴巴标签的更高（7%）
        if (Math.random() >= GOSSIP_CHANCE + aState.gossipProne() * 0.1) return;

        // 找 8 格内空闲的其他 AI 村民
        VillagerState bState = null;
        double bestDist = GOSSIP_RADIUS;
        for (VillagerState other : villagers.values()) {
            if (other == aState || !other.entity().isValid()) continue;
            if (other.interactingPlayer() != null) continue;
            if (!other.entity().getWorld().equals(a.getWorld())) continue;
            double d = other.entity().getLocation().distance(a.getLocation());
            if (d < bestDist) { bestDist = d; bState = other; }
        }
        if (bState == null) return;

        // A 随机挑一条见闻讲出去 —— 玩家相关的事实（前缀"旅者"）不外传，
        // 否则八卦会把"认识玩家"扩散到全村，出现"每个NPC都莫名其妙认识我"
        java.util.List<String> facts = aState.facts().stream()
                .filter(f -> !f.contains("旅者"))
                .toList();
        // 冷却必须在"确实要传话"时才写：若先写冷却、再发现没有可外传的见闻，
        // 这次 2 分钟冷却就被白白空耗，村民下次传话要多等一整轮
        if (facts.isEmpty()) return;
        lastGossipAt.put(aState.getUniqueId(), System.currentTimeMillis());
        String fact = facts.get((int) (Math.random() * facts.size()));
        String aName = a.getCustomName() != null ? a.getCustomName().replaceAll("§.", "") : aState.profession().zhName;
        String bName = bState.entity().getCustomName() != null
                ? bState.entity().getCustomName().replaceAll("§.", "") : bState.profession().zhName;

        // B 按自己的开放度决定记不记（每个 NPC 接受程度不同）
        if (Math.random() * 100 < bState.openness()) {
            String shortFact = fact.length() > 40 ? fact.substring(0, 40) : fact;
            boolean added = bState.addGossipFact("听" + aName + "说，" + shortFact);
            if (added) {
                bubbleManager.showDialog(bState.entity(), bName, "（凑近听" + aName + "讲了句悄悄话，点了点头）");
                plugin.getMemoryStore().markDirty(bState.getUniqueId());
                plugin.getLogger().info("§b[MC-AI] 村民传话: " + aName + " → " + bName + " 记住了「" + shortFact + "」");
            }
        } else {
            bubbleManager.showDialog(bState.entity(), bName, "（听完" + aName + "的话，撇撇嘴，不太信）");
            plugin.getLogger().info("§b[MC-AI] 村民传话: " + aName + " → " + bName + " 没信 (openness=" + bState.openness() + ")");
        }
        bubbleManager.showDialog(a, aName, "（凑到" + bName + "耳边小声嘀咕了几句）");
    }

    /**
     * favor 逐玩家向 0 缓慢收敛 —— 很久没见会慢慢淡忘（SPEC 3.2）。
     * 按玩家分账后需逐个衰减：正在跟某玩家对话时，跳过"当前这位玩家"（交谈中不该变淡），
     * 其余每位玩家的好感照常向 0 收敛。
     */
    private void decayFavor(VillagerState vState) {
        java.util.UUID talkingTo = vState.interactingPlayer();
        for (var e : vState.emotion().favorSnapshot().entrySet()) {
            if (talkingTo != null && talkingTo.toString().equals(e.getKey())) continue;
            double f = e.getValue();
            if (f == 0) continue;
            double next = f > 0
                    ? Math.max(0, f - FAVOR_DECAY_PER_ROUND)
                    : Math.min(0, f + FAVOR_DECAY_PER_ROUND);
            try {
                vState.emotion().setFavor(java.util.UUID.fromString(e.getKey()), next);
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID（理论上不会出现，snapshot key 来自已解析的 map）跳过
            }
        }
    }
}
