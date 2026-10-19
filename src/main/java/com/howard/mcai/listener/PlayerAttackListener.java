package com.howard.mcai.listener;

import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.memory.MemoryStore;
import com.howard.mcai.mob.MobSpecies;
import com.howard.mcai.mob.MobState;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Projectile;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 玩家攻击生物事件 → 情绪状态更新（SPEC 2.3 事件驱动）。
 * <p>
 * v2 扩展：不只村民，动物/怪物受击也走情绪：
 * <ul>
 *   <li>村民：fear+10 + facts"打过我"（原有逻辑）</li>
 *   <li>动物：fear+20 + 灰色叙述气泡（受惊了）</li>
 *   <li>僵尸：aggression+20 + 记仇目标 + 红色情绪气泡（"它记住你了！"）</li>
 * </ul>
 * 另含"击杀僵尸 → 目击村民涨好感"：玩家在村里打死僵尸，附近的村民会当成"救了我"，
 * 对该玩家的好感上涨、恐惧消退（见 {@link #onZombieSlain}）。
 */
public final class PlayerAttackListener implements Listener {

    /** 目击者半径（格）—— 僵尸死在这个范围内的受管村民才算"亲眼看见" */
    private static final double KILL_WITNESS_RADIUS = 16.0;
    /** 目击救命涨的好感 */
    private static final double KILL_WITNESS_FAVOR = 3.0;
    /** 威胁被清掉后目击者松一口气降的恐惧 */
    private static final int KILL_WITNESS_RELIEF = 15;

    private final Map<UUID, VillagerState> villagers;
    private final Map<UUID, MobState> mobs;
    private final MemoryStore memoryStore;
    private final BubbleManager bubbleManager;

    public PlayerAttackListener(Map<UUID, VillagerState> villagers,
                                Map<UUID, MobState> mobs,
                                MemoryStore memoryStore,
                                BubbleManager bubbleManager) {
        this.villagers = villagers;
        this.mobs = mobs;
        this.memoryStore = memoryStore;
        this.bubbleManager = bubbleManager;
    }

    @EventHandler
    public void onEntityDamaged(EntityDamageByEntityEvent event) {
        Entity defender = event.getEntity();
        Player player = resolveAttackerPlayer(event.getDamager());
        if (player == null) return;

        // === 村民受击（原有逻辑） ===
        if (defender instanceof Villager villager) {
            VillagerState state = villagers.get(villager.getUniqueId());
            if (state == null) return;

            // 受击时刻 —— 场景上下文 was_hit_recently 用（Entity.getLastDamage() 是伤害数值不是时间，不能直接用）
            state.markHit();

            // 情绪更新
            state.emotion().addFear(10);

            // === 关键事件进 facts（SPEC 3.4）—— 被打是不能忘的事 ===
            // 打 owner=该玩家，仅该玩家可见（防跨玩家泄露"谁打过我"）
            state.addFact("旅者 " + player.getName() + " 打过我", player.getUniqueId());

            // 气泡反应（可选：让村民"有痛觉"的表现）
            bubbleManager.show(villager, "哎哟！别打了！", 80L);

            // 记忆标记脏 → debounce flush 落盘
            memoryStore.markDirty(villager.getUniqueId());
            return;
        }

        // === 动物/怪物受击（v2 多物种） ===
        if (!(defender instanceof LivingEntity le)) return;
        String species = MobSpecies.of(le);
        if (species == null) return;

        // 懒创建状态 —— 表现层扫描也会创建，这里是"没扫到就被打"的兜底
        MobState st = mobs.computeIfAbsent(le.getUniqueId(), k -> new MobState(le, species));
        st.markAttacked(player.getUniqueId());

        if (MobSpecies.ZOMBIE.equals(species)) {
            // 僵尸记仇：红字情绪喊话立即给（不用等表现层 3 秒的轮询）
            bubbleManager.showMobBubble(le, "§c" + MobSpecies.scaredPhrase(species));
        } else {
            // 动物受惊：灰色斜体短句（碎片化，不写长句）
            bubbleManager.showMobBubble(le, "§7§o" + MobSpecies.scaredPhrase(species));
        }
    }

    /**
     * 玩家击杀僵尸 → 附近的村民"目击"了这场救援，对该玩家的好感上涨、恐惧消退。
     * <p>
     * 为什么挂在这个类里：它已经持有 villagers / memoryStore，击杀判定和"受击情绪"同属
     * 事件驱动的情绪逻辑，不必再单独注册一个 listener。
     * <p>
     * 关键事实按玩家归属（ownerUuid）写入，只有当事玩家能在 prompt 里看到"我在他眼前打死了僵尸"，
     * 避免别的玩家莫名其妙被村民当成救命恩人。事实句子固定（只随玩家名变化），
     * {@code VillagerState.addFactInternal} 会做整句去重 —— 连杀一群僵尸也只留一条记忆，
     * 但好感照涨（每杀一只算一次救援）。
     *
     * @param event 生物死亡事件；只处理僵尸（Husk/Drowned 等变种都 instanceof Zombie），
     *              且必须有玩家凶手（近战/弓箭/三叉戟的 {@code getKiller()} 都认）
     */
    @EventHandler
    public void onZombieSlain(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        Player killer = zombie.getKiller();
        if (killer == null || !killer.isOnline()) return;

        int witnesses = 0;
        for (VillagerState vs : villagers.values()) {
            Villager v = vs.entity();
            if (v == null || !v.isValid() || v.isDead()) continue;
            if (!v.getWorld().equals(zombie.getWorld())) continue;
            if (v.getLocation().distance(zombie.getLocation()) > KILL_WITNESS_RADIUS) continue;

            // 威胁就在眼前被清掉 → 好感涨 + 松一口气；事实给这份感谢一个"记得住"的理由
            vs.emotion().addFavor(killer.getUniqueId(), KILL_WITNESS_FAVOR);
            vs.emotion().addFear(-KILL_WITNESS_RELIEF);
            vs.addFact("旅者 " + killer.getName() + " 在我眼前打死了僵尸，救了我", killer.getUniqueId());
            memoryStore.markDirty(vs.getUniqueId());
            witnesses++;
        }

        if (witnesses > 0) {
            // 不冒固定台词（无硬编码兜底）—— 只记调试日志，方便核对"好感为什么涨了"
            Bukkit.getLogger().info("[MC-AI] 击杀目击: " + killer.getName() + " 打死僵尸 @ "
                    + zombie.getWorld().getName() + " → " + witnesses
                    + " 名村民目击 +" + (int) KILL_WITNESS_FAVOR + " 好感");
        }
    }

    /**
     * 解析出"真正动手的玩家"。
     * <p>
     * 近战时 damager 就是 Player；但弓箭/三叉戟/雪球等远程武器的 damager 是
     * Projectile，扣扳机的是它的 shooter —— 只判 instanceof Player 会漏掉远程攻击，
     * 导致玩家用弓射 NPC 不写"打过我"这条关键事实。shooter 不是玩家（发射器/环境）
     * 返回 null，由调用方直接忽略。
     */
    private static Player resolveAttackerPlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
