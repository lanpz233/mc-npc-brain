package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.memory.MemoryStore;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 村民死亡处理 —— "和现实一样"：死人是大事，不能悄无声息。
 * <ul>
 *   <li><b>销户</b>：从 villagers map 移除 —— NeedsLayer/PresentationLayer 不再每轮扫到
 *       无效实体，记忆文件保留为档案（不删，UUID 不会重用）</li>
 *   <li><b>掉落库存</b>：独立库存按堆叠拆散落在死亡点，能捡 —— 不是凭空消失</li>
 *   <li><b>亲友反应</b>：配偶/子女/兄弟姐妹/父母记一条公开 facts"XXX 没了"；
 *       16 格内在场的亲友冒悲伤气泡</li>
 *   <li><b>全村知情 + 记仇</b>：凶手是玩家 → 亲友无论远近都记恨，
 *       32 格内的其他村民也算当场亲眼看见；知情者对该玩家好感大幅暴跌、恐惧上升，
 *       并记一条该玩家可见的"杀了XXX"事实（对峙/报复的引子）</li>
 *   <li><b>清气泡</b>：死者的头顶气泡立即清理，不等自然过期</li>
 * </ul>
 * <p>
 * 和 {@link PlayerAttackListener#onZombieSlain} 的区别：那个只处理"玩家打死僵尸
 * → 村民感激"；这里处理"村民死了 → 世界少了一个人"。
 */
public final class VillagerDeathListener implements Listener {

    /** 亲友反应的悲伤气泡半径（格）—— 在这个范围内才冒悲伤气泡，太远的只记事实 */
    private static final double GRIEF_RADIUS = 16.0;

    /**
     * 目击半径（格）—— 死在这个范围内、又不是亲友的村民，也会当场知情并忌惮凶手。
     * 村里当街杀了人，旁边的住户是"亲眼看见"，不是听八卦 —— 所以半径内一律按知情处理。
     */
    private static final double WITNESS_RADIUS = 32.0;

    /** 凶手是玩家时，知情者对该玩家的好感打击（大幅暴跌，得靠长期行善慢慢刷回来） */
    private static final double KILL_REACTION_FAVOR = -40.0;

    /** 凶手是玩家时，知情者的恐惧上升 */
    private static final int KILL_REACTION_FEAR = 25;

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final MemoryStore memoryStore;
    private final BubbleManager bubbleManager;

    public VillagerDeathListener(McaiPlugin plugin, Map<UUID, VillagerState> villagers,
                                 MemoryStore memoryStore, BubbleManager bubbleManager) {
        this.plugin = plugin;
        this.villagers = villagers;
        this.memoryStore = memoryStore;
        this.bubbleManager = bubbleManager;
    }

    @EventHandler
    public void onVillagerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager v)) return;
        // 销户：不是 AI 管理的村民直接返回（原版村民不归我们管）
        VillagerState vs = villagers.remove(v.getUniqueId());
        if (vs == null) return;

        String deadName = Smalltalk.nameOf(vs);
        Player killer = v.getKiller();
        UUID killerId = killer == null ? null : killer.getUniqueId();
        String killerName = killer == null ? null : killer.getName();
        Location deathLoc = v.getLocation();

        // 1. 库存掉出来（能捡）
        dropInventory(vs, deathLoc);

        // 2. 头顶气泡立即清
        bubbleManager.clearFor(v.getUniqueId());

        // 3. 先认出死者的亲友（配偶/子女/兄弟姐妹/父母）—— 亲疏不同，处理也不同
        Set<UUID> kinIds = new HashSet<>();
        for (VillagerState.Relationship rel : vs.relationships()) {
            try {
                kinIds.add(UUID.fromString(rel.targetUuid()));
            } catch (Exception ignored) {
                // 脏数据跳过，不影响其它亲友
            }
        }

        // 4. 全村知情：亲友无论在哪儿都会知道（家人的事瞒不住）；
        //    其余村民只要在目击半径内，也算当场亲眼看见 —— 于是同样忌惮凶手。
        //    为什么不做成"只有亲友才反应"：村里当街杀人，旁边的住户一样会害怕、会记恨。
        int kinCount = 0;
        int witnessCount = 0;
        for (VillagerState other : villagers.values()) {
            if (other == null) continue;
            Villager oe = other.entity();
            if (oe == null || !oe.isValid() || oe.isDead()) continue;

            UUID otherId = other.getUniqueId();
            boolean kin = kinIds.contains(otherId);
            boolean near = oe.getWorld().equals(deathLoc.getWorld())
                    && oe.getLocation().distance(deathLoc) <= WITNESS_RADIUS;
            if (!kin && !near) continue;   // 既不是亲友、又没看见 → 不参与

            // 公开事实：死人是全村都会知道的事，不设 owner
            if (kin) other.addFact(deadName + "没了", null);

            if (killerId != null) {
                // 凶手是玩家 → 知情者一律忌惮：好感大幅暴跌 + 恐惧上升
                other.emotion().addFavor(killerId, KILL_REACTION_FAVOR);
                other.emotion().addFear(KILL_REACTION_FEAR);
                other.addFact("旅者 " + killerName + " 杀了" + deadName, killerId);
            }

            // 在场的亲友当场悲伤（动作式气泡，不编台词）
            if (kin && oe.getWorld().equals(deathLoc.getWorld())
                    && oe.getLocation().distance(deathLoc) <= GRIEF_RADIUS) {
                bubbleManager.showDialog(oe, Smalltalk.nameOf(other), "（跪在地上，抱住头）");
            }
            memoryStore.markDirty(otherId);
            if (kin) kinCount++;
            else witnessCount++;
        }

        plugin.getLogger().info("§c[MC-AI] 村民死亡: " + deadName
                + "（亲友 " + kinCount + " 人 / 目击 " + witnessCount + " 人知情，库存已掉落）"
                + (killer != null ? " 凶手=" + killer.getName() + "（知情者记仇）" : ""));
    }

    /**
     * 独立库存掉出来 —— 按物品最大堆叠拆分，自然散落。
     * 现实逻辑：人没了，东西不会凭空消失。
     */
    private void dropInventory(VillagerState vs, Location loc) {
        if (loc.getWorld() == null) return;
        for (var e : vs.inventorySnapshot().entrySet()) {
            Material mat;
            try {
                mat = Material.matchMaterial(e.getKey());
            } catch (Exception ex) {
                continue;
            }
            if (mat == null || mat.isAir()) continue;
            int left = e.getValue() == null ? 0 : Math.max(0, e.getValue());
            while (left > 0) {
                int stack = Math.min(left, mat.getMaxStackSize());
                loc.getWorld().dropItemNaturally(loc, new ItemStack(mat, stack));
                left -= stack;
            }
        }
    }
}
