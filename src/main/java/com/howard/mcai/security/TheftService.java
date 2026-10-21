package com.howard.mcai.security;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.VillageTrust;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 偷窃系统（Fong 2026-10-22 设计）—— 玩家翻村民背包时“拿走东西”走这里，
 * 是唯一的出货口（背包 GUI 本身永远 cancel 真实拖拽）。
 * <p>
 * 玩法口径（服主定版）：
 * <ul>
 *   <li>能下手的时机：村民睡着，或者醒着但<b>没看着你</b>（你在他视野锥外）。
 *       正盯着你时只能看不能拿。</li>
 *   <li>点一件东西 = 一次下手，东西先进<b>2 秒托管</b>（不进玩家背包，
 *       防“先扔地上/塞箱子躲没收”），2 秒到点才结算下发。</li>
 *   <li>结算时没跑掉（7 格内、有视线接触；醒着的还要在他视野里）→
 *       85% 惊醒没收 + 人赃并获全套后果（骂人、掉好感、记村庄信任账）；
 *       剩下 15% 算命大，东西归你。</li>
 *   <li>跑掉了 → 只有 10% 被没收，而且他没看清是谁：不扣好感不记账。</li>
 *   <li>翻包过程中他转身正看见你 → 立刻关窗，托管中的按“没跑掉”结算。</li>
 * </ul>
 * 全部主线程（GUI 点击与调度任务都在主线程）。
 */
public final class TheftService {

    /** 托管时长：随机 20~40 tick（1~2 秒），每次下手在 beginSteal 里掷 */
    /** 结算时“没跑掉”的距离界 */
    public static final double CAUGHT_RADIUS = 7.0;
    /** “看着你”的判定半径与视野半角 */
    public static final double WATCH_RADIUS = 8.0;
    public static final double VIEW_HALF_ANGLE_DEG = 55.0;
    /** 没跑掉时被抓概率 / 跑掉后仍被没收概率 */
    public static final double CAUGHT_CHANCE = 0.85;
    public static final double CONFISCATE_AFTER_ESCAPE_CHANCE = 0.10;

    private final McaiPlugin plugin;
    private final List<Pending> pendings = new ArrayList<>();
    /** 正在被翻包的会话（玩家 → 目标村民），守望任务用它查“他转身看见你了” */
    private final Map<UUID, VillagerState> openSessions = new ConcurrentHashMap<>();

    private static final class Pending {
        final UUID playerUuid;
        final UUID npcUuid;
        final Material mat;
        final Runnable onSettled;
        boolean settled;

        Pending(UUID playerUuid, UUID npcUuid, Material mat, Runnable onSettled) {
            this.playerUuid = playerUuid;
            this.npcUuid = npcUuid;
            this.mat = mat;
            this.onSettled = onSettled;
        }
    }

    public TheftService(McaiPlugin plugin) {
        this.plugin = plugin;
        // 守望：每 5 tick 扫一遍正开着的偷窃背包——村民转身正看见玩家就当场收网
        Bukkit.getScheduler().runTaskTimer(plugin, this::watchSessions, 5L, 5L);
    }

    // ===== 时机判定 =====

    /** 他现在是否正看着这个玩家（醒着 + 距离内 + 在视野锥内 + 无遮挡） */
    public static boolean observed(Player player, Villager villager) {
        if (player == null || villager == null || villager.isSleeping()) return false;
        if (!player.getWorld().equals(villager.getWorld())) return false;
        Location vl = villager.getLocation();
        Location pl = player.getLocation();
        if (vl.distanceSquared(pl) > WATCH_RADIUS * WATCH_RADIUS) return false;
        Vector facing = vl.getDirection().setY(0);
        Vector toPlayer = pl.toVector().subtract(vl.toVector()).setY(0);
        if (facing.lengthSquared() < 1e-6 || toPlayer.lengthSquared() < 1e-6) return true;
        double cos = facing.normalize().dot(toPlayer.normalize());
        if (cos < Math.cos(Math.toRadians(VIEW_HALF_ANGLE_DEG))) return false;
        return villager.hasLineOfSight(player);
    }

    /** 现在能不能对这个村民下手：睡着了，或者醒着但没看着你 */
    public static boolean canSteal(Player player, VillagerState state) {
        Villager v = state.entity();
        if (v == null || !v.isValid() || v.isDead()) return false;
        return v.isSleeping() || !observed(player, v);
    }

    // ===== 下手与托管 =====

    /**
     * 开始偷一件（GUI 点击调用）。同一玩家对同一村民的同一种东西，
     * 托管没结算前不能重复下手。
     *
     * @return null = 已进入托管；非 null = 没下成手的原因（给玩家看）
     */
    public String beginSteal(Player player, VillagerState state, Material mat, Runnable onSettled) {
        Villager v = state.entity();
        if (v == null || !v.isValid()) return "他不在这儿了";
        if (!canSteal(player, state)) return "§e他正盯着你看呢，现在伸手准被抓";
        if (state.stockOf(mat) <= 0) return "§e他包里已经没有这个了";
        for (Pending p : pendings) {
            if (!p.settled && p.playerUuid.equals(player.getUniqueId())
                    && p.npcUuid.equals(state.getUniqueId()) && p.mat == mat) {
                return null;   // 已经在偷这一件了，安静等结算
            }
        }
        Pending p = new Pending(player.getUniqueId(), state.getUniqueId(), mat, onSettled);
        pendings.add(p);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.3f, 0.7f);
        // 托管时长随机 1~2 秒（v0.4.1 服主定：固定 2 秒太可预测，玩家能掐着点跑）
        long ticks = 20L + ThreadLocalRandom.current().nextInt(21);
        Bukkit.getScheduler().runTaskLater(plugin, () -> resolve(p, false), ticks);
        return null;
    }

    /** 这个玩家对这个村民有没有未结算的托管（守望收网的触发条件） */
    public boolean hasPendingPair(UUID playerUuid, UUID npcUuid) {
        for (Pending p : pendings) {
            if (!p.settled && p.playerUuid.equals(playerUuid) && p.npcUuid.equals(npcUuid)) return true;
        }
        return false;
    }

    /** 这个玩家对这个村民的这种东西是否正在托管中（GUI 显示“正在下手”用） */
    public boolean isPending(UUID playerUuid, UUID npcUuid, Material mat) {
        for (Pending p : pendings) {
            if (!p.settled && p.playerUuid.equals(playerUuid)
                    && p.npcUuid.equals(npcUuid) && p.mat == mat) return true;
        }
        return false;
    }

    // ===== 会话守望 =====

    /** 背包 GUI 打开时登记（关窗时注销）；守望只管“偷”的窗口，普通查看无所谓 */
    public void openSession(Player player, VillagerState state) {
        openSessions.put(player.getUniqueId(), state);
    }

    public void closeSession(Player player) {
        if (player != null) openSessions.remove(player.getUniqueId());
    }

    private void watchSessions() {
        if (openSessions.isEmpty()) return;
        Iterator<Map.Entry<UUID, VillagerState>> it = openSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, VillagerState> e = it.next();
            Player player = Bukkit.getPlayer(e.getKey());
            VillagerState state = e.getValue();
            Villager v = state.entity();
            if (player == null || !player.isOnline() || v == null || !v.isValid()) {
                it.remove();
                continue;
            }
            // 他醒着且正看见你、且你手上正有没结算的赃 → 收网：关窗 + 按被抓结算。
            // 没在偷（只是开着界面）时不收网——v0.4.0 实测教训：一开界面就被关，
            // 玩家连看都不行；能不能下手由 canSteal 在点击时把关。
            if (!v.isSleeping() && observed(player, v) && hasPendingPair(player.getUniqueId(), state.getUniqueId())) {
                it.remove();
                player.closeInventory();
                player.sendMessage("§c" + npcName(state) + " 转过身来，正看见你翻他的包！");
                settlePairAsCaught(player, state);
            }
        }
    }

    /** 收网时把这一对（玩家×村民）所有未结算托管按“被抓”处理（只产生一次骂与罚，在 resolve 里合并） */
    private void settlePairAsCaught(Player player, VillagerState state) {
        boolean any = false;
        for (Pending p : pendings) {
            if (!p.settled && p.playerUuid.equals(player.getUniqueId())
                    && p.npcUuid.equals(state.getUniqueId())) {
                any = true;
                resolve(p, true);
            }
        }
        if (!any) {
            // 没在偷任何东西，只是看包被撞见 —— 不罚，只吓一跳（窗口已关）
        }
    }

    // ===== 结算 =====

    /**
     * 结算一笔托管。
     *
     * @param forcedCaught true = 守望收网（他亲眼看见），跳过“跑没跑掉”判定直接按被抓算
     */
    private void resolve(Pending p, boolean forcedCaught) {
        if (p.settled) return;
        p.settled = true;
        pendings.remove(p);
        Player player = Bukkit.getPlayer(p.playerUuid);
        VillagerState state = plugin.getVillagers().get(p.npcUuid);
        if (player == null || !player.isOnline() || state == null) {
            runCallback(p);
            return;
        }
        Villager v = state.entity();
        if (v == null || !v.isValid() || v.isDead()) {
            runCallback(p);
            return;
        }
        if (state.stockOf(p.mat) <= 0) {
            runCallback(p);   // 托管期间东西没了（被别人拿走/用掉）——偷了个空
            return;
        }

        boolean exposed = forcedCaught || isExposed(player, state, v);
        double roll = ThreadLocalRandom.current().nextDouble();
        if (exposed && roll < CAUGHT_CHANCE) {
            onCaught(player, state, v, p.mat);
        } else if (!exposed && roll < CONFISCATE_AFTER_ESCAPE_CHANCE) {
            onConfiscatedQuietly(player, state, v, p.mat);
        } else {
            onSuccess(player, state, p.mat);
        }
        runCallback(p);
    }

    private void runCallback(Pending p) {
        if (p.onSettled != null) {
            try {
                p.onSettled.run();
            } catch (RuntimeException ignored) {
                // GUI 回调异常不许连累结算
            }
        }
    }

    /** 结算时刻玩家算不算“没跑掉”：距离内 + 无遮挡；（村民醒着时）还在他视野里 */
    private boolean isExposed(Player player, VillagerState state, Villager v) {
        if (!player.getWorld().equals(v.getWorld())) return false;
        if (player.getLocation().distanceSquared(v.getLocation()) > CAUGHT_RADIUS * CAUGHT_RADIUS) {
            return false;
        }
        if (!v.hasLineOfSight(player)) return false;
        if (!v.isSleeping()) {
            // 醒着的村民：结算时你已经挪到他背后 = 他的注意力没抓住你，按跑掉算
            return observed(player, v);
        }
        return true;
    }

    /** 成功：东西真从他包里进你包里 */
    private void onSuccess(Player player, VillagerState state, Material mat) {
        if (!state.removeFromInventory(mat, 1)) return;
        var leftover = player.getInventory().addItem(new ItemStack(mat, 1));
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        plugin.getMemoryStore().markDirty(state.getUniqueId());
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.25f, 0.8f);
        plugin.getLogger().info("[偷窃] " + player.getName() + " 从 " + npcName(state)
                + " 包里偷走了 " + mat.name() + "，没被发现");
    }

    /** 跑掉了仍被没收（10%）：他惊醒/回过神把东西抓回去，但没看清是谁 —— 不罚 */
    private void onConfiscatedQuietly(Player player, VillagerState state, Villager v, Material mat) {
        if (v.isSleeping()) v.wakeup();
        String zh = PromptBuilder.materialToZh(mat);
        plugin.getBubbleManager().showDialog(v, npcName(state), "（嗯？我的" + zh + "呢……）");
        player.sendMessage("§e你刚摸到的东西被他一把抓回去了——他没看清是谁。");
        plugin.getLogger().info("[偷窃] " + player.getName() + " 偷 " + npcName(state)
                + " 的 " + mat.name() + " 被没收（跑掉后小概率），未被认出");
    }

    /** 人赃并获：惊醒 + 骂人 + 好感按赃物价值掉 + 村庄信任记一笔 */
    private void onCaught(Player player, VillagerState state, Villager v, Material mat) {
        if (v.isSleeping()) v.wakeup();
        String zh = PromptBuilder.materialToZh(mat);
        String name = npcName(state);

        // 村庄信任：找村、记账（有醒着的同村村民在场 = 传得更快）
        Location loc = v.getLocation();
        int villageId = plugin.getVillageTrust().villageIdFor(
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        boolean witnessed = hasWitness(state, v);
        int newScore = plugin.getVillageTrust().onCaught(player.getUniqueId(), villageId, witnessed);
        int caughtCount = plugin.getVillageTrust().caughtCount(player.getUniqueId(), villageId);
        plugin.saveVillageTrust();

        // 好感：按赃物贵不贵掉，贵的多掉
        int value = PermissionManager.valueOf(mat);
        double favorHit = -(4 + Math.min(16, value / 8));
        state.emotion().addFavor(player.getUniqueId(), favorHit);
        state.addFact(player.getName() + " 趁我不备偷我的" + zh + "，被我当场抓住了", player.getUniqueId());
        plugin.getMemoryStore().markDirty(state.getUniqueId());

        // 骂人：插件按“是不是惯犯”选词（模型不参与裁决，也不用等它）
        String line = scoldLine(zh, caughtCount, newScore);
        plugin.getBubbleManager().showDialog(v, name, line);
        player.sendMessage("§6[" + name + ">" + player.getName() + "] §f" + line);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.9f);
        plugin.getLogger().info("[偷窃] " + player.getName() + " 偷 " + name + " 的 " + mat.name()
                + " 被抓现行（第 " + caughtCount + " 次，信任降到 " + newScore
                + (witnessed ? "，有目击者" : "") + "）");
    }

    /** 有没有别的村民醒着在附近（12 格内）——有的话事情传得更快 */
    private boolean hasWitness(VillagerState victim, Villager v) {
        for (VillagerState other : plugin.getVillagers().values()) {
            if (other == victim) continue;
            Villager ov = other.entity();
            if (ov == null || !ov.isValid() || ov.isSleeping()) continue;
            if (!ov.getWorld().equals(v.getWorld())) continue;
            if (ov.getLocation().distanceSquared(v.getLocation()) <= 12 * 12) return true;
        }
        return false;
    }

    private static final String[] SCOLD_FIRST = {
            "哎！你干嘛呢！手往哪儿伸！",
            "好哇，偷到我头上来了？！",
            "抓住你了！大半夜翻我包干嘛！",
            "你你你……把东西放下！",
    };
    private static final String[] SCOLD_REPEAT = {
            "又是你！村里都传遍了，你还敢来！",
            "没完了是吧？这事儿我得让全村都知道！",
            "你这名声在村里算是臭了，还敢伸手！",
            "一而再再而三，当我是瞎子吗！",
    };

    private String scoldLine(String itemZh, int caughtCount, int trustScore) {
        boolean repeat = caughtCount >= 2 || trustScore < VillageTrust.CAUGHT_PENALTY + 10;
        String[] pool = repeat ? SCOLD_REPEAT : SCOLD_FIRST;
        String base = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        return base + "（指你刚摸的" + itemZh + "）";
    }

    private static String npcName(VillagerState state) {
        var e = state.entity();
        String cn = (e != null) ? e.getCustomName() : null;
        if (cn != null && !cn.isBlank()) return cn.replaceAll("§.", "");
        return state.profession().zhName;
    }
}
