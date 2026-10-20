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

import java.util.Map;
import java.util.UUID;

/**
 * AI 村民死亡处理 —— "和现实一样"：死人是大事，不能悄无声息。
 * <ul>
 *   <li><b>销户</b>：从 villagers map 移除 —— NeedsLayer/PresentationLayer 不再每轮扫到
 *       无效实体，记忆文件保留为档案（不删，UUID 不会重用）</li>
 *   <li><b>掉落库存</b>：独立库存按堆叠拆散落在死亡点，能捡 —— 不是凭空消失</li>
 *   <li><b>亲友反应</b>：配偶/子女/兄弟姐妹/父母记一条公开 facts"XXX 没了"；
 *       16 格内在场的亲友冒悲伤气泡</li>
 *   <li><b>记仇</b>：凶手是玩家 → 所有亲友对该玩家好感暴跌、恐惧上升，
 *       并记一条该玩家可见的"杀了XXX"事实（对峙/报复的引子）</li>
 *   <li><b>清气泡</b>：死者的头顶气泡立即清理，不等自然过期</li>
 * </ul>
 * <p>
 * 和 {@link PlayerAttackListener#onZombieSlain} 的区别：那个只处理"玩家打死僵尸
 * → 村民感激"；这里处理"村民死了 → 世界少了一个人"。
 */
public final class VillagerDeathListener implements Listener {

    /** 亲友目击半径（格）—— 在这个范围内才冒悲伤气泡，太远的只记事实 */
    private static final double GRIEF_RADIUS = 16.0;

    /** 亲人被杀，好感惩罚（一次记仇记到底，得靠长期行善慢慢刷回来） */
    private static final double KILLER_FAVOR_HIT = -30.0;

    /** 亲人被杀，恐惧上升 */
    private static final int KILLER_FEAR_HIT = 25;

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
        Location deathLoc = v.getLocation();

        // 1. 库存掉出来（能捡）
        dropInventory(vs, deathLoc);

        // 2. 头顶气泡立即清
        bubbleManager.clearFor(v.getUniqueId());

        // 3. 亲友反应
        int mourners = 0;
        for (VillagerState.Relationship rel : vs.relationships()) {
            UUID kinId;
            try {
                kinId = UUID.fromString(rel.targetUuid());
            } catch (Exception e) {
                continue;  // 脏数据跳过
            }
            VillagerState kin = villagers.get(kinId);
            if (kin == null || !kin.entity().isValid() || kin.entity().isDead()) continue;

            // 公开事实：死人是全村都会知道的事，不设 owner
            kin.addFact(deadName + "没了", null);
            if (killer != null) {
                kin.emotion().addFavor(killer.getUniqueId(), KILLER_FAVOR_HIT);
                kin.emotion().addFear(KILLER_FEAR_HIT);
                kin.addFact("旅者 " + killer.getName() + " 杀了" + deadName, killer.getUniqueId());
            }
            // 在场的亲友当场悲伤（动作式气泡，不编台词）
            if (kin.entity().getWorld().equals(deathLoc.getWorld())
                    && kin.entity().getLocation().distance(deathLoc) <= GRIEF_RADIUS) {
                bubbleManager.showDialog(kin.entity(), Smalltalk.nameOf(kin), "（跪在地上，抱住头）");
            }
            memoryStore.markDirty(kinId);
            mourners++;
        }

        plugin.getLogger().info("§c[MC-AI] 村民死亡: " + deadName
                + "（亲友 " + mourners + " 人知晓，库存已掉落）"
                + (killer != null ? " 凶手=" + killer.getName() + "（亲友记仇）" : ""));
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
