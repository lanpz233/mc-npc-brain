package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.gui.VillagerInventoryGui;
import com.howard.mcai.mob.MobSpecies;
import com.howard.mcai.mob.MobState;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.planner.CentralPlanner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 玩家交互监听 —— 右键村民 → 立刻调 LLM 生成开场白 + 进入对话模式。
 * <p>
 * 核心设计变化（v0.1.1 之前是硬编码开场白等 tick loop）：
 * 右键事件里直接 triggerLLM，让村民"即时"回复玩家问候。
 * tick loop 只做兜底——防止 Listener 因并发问题漏触发。
 *
 * 为什么取消 Paper 原生交易界面？
 * 跟原版铁匠铺的"交易"是 MC-AI 的 say/offer_trade 流程里 LLM 决定的行为，
 * 不是玩家点右键就自动弹 GUI——我们要完全接管交互节奏。
 *
 * v2 扩展：右键动物喂食（手持对应食物）→ MobState.markFed + 叙述气泡。
 * 不 cancel 事件，原版繁殖照常（先让原版管生理，AI 管情绪表现）。
 */
public final class PlayerInteractListener implements Listener {

    private final Map<UUID, VillagerState> villagers;
    private final Map<UUID, MobState> mobs;
    private final BubbleManager bubbleManager;
    private final CentralPlanner planner;
    private final McaiPlugin plugin;

    public PlayerInteractListener(McaiPlugin plugin, Map<UUID, VillagerState> villagers,
                                  Map<UUID, MobState> mobs,
                                  BubbleManager bubbleManager,
                                  CentralPlanner planner) {
        this.plugin = plugin;
        this.villagers = villagers;
        this.mobs = mobs;
        this.bubbleManager = bubbleManager;
        this.planner = planner;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Villager villager)) return;

        // 只对我们管理的村民生效
        VillagerState state = villagers.get(villager.getUniqueId());
        if (state == null) return;

        // 防止跟交易 GUI 冲突：shift+右键 正常打开原版交易界面（保留原版行为，不动）
        Player player = event.getPlayer();
        if (player.isSneaking()) return;

        // 取消 Paper 原生交互（不让原版交易界面弹出来）
        event.setCancelled(true);

        // === toggle：同一个玩家再次右键 → 结束对话（而不是重新触发开场白） ===
        UUID playerUuid = player.getUniqueId();
        if (state.interactingPlayer() != null && state.interactingPlayer().equals(playerUuid)) {
            state.endConversation();
            // endConversation 已记下结束时间 —— 标脏落盘，供下次开场说明定档
            plugin.getMemoryStore().markDirty(state.getUniqueId());
            player.sendMessage("§7[MC-AI] 结束交谈");
            return;
        }

        // === 双重保险：右键前立刻刷新专属配方 ===
        // Paper 会偶尔重置村民配方（entity reload / level up），
        // 在玩家交互前再覆盖一次，确保玩家看到的一定是 NPC 的店
        plugin.initVillagerShop(villager, villager.getCustomName() != null
                ? villager.getCustomName().replaceAll("§.", "") : "Villager");

        // 开始对话（多 NPC：自动把玩家在其他村民身上的对话状态切走，避免两头占）
        planner.switchConversation(player, state);

        // 先发一个"思考中"占位会话框 —— 让玩家知道触发成功（LLM 回复到达后会自动替换）
        bubbleManager.showDialog(villager,
                villager.getCustomName() != null ? villager.getCustomName().replaceAll("§.", "") : "村民",
                "（思考中...）");

        // 开场说明按事实动态拼（见 ai/ConversationOpening）：见没见过、上次聊完过了多久、
        // 还有没有近期记录。不再用写死的 "just approached... Greet them warmly!" ——
        // 那句会让刚聊完又回来的玩家也被当成第一次见面重新打招呼。
        // 注意 hasMet 必须在 onPlayerMessage 记录本次见面前查（它内部会 recordMeeting）。
        long nowMs = System.currentTimeMillis();
        long lastEndMs = state.lastConversationEndMs(playerUuid);
        com.howard.mcai.ai.ConversationOpening.Tier openingTier =
                com.howard.mcai.ai.ConversationOpening.tier(state.hasMet(playerUuid), lastEndMs, nowMs);
        String openingNote = com.howard.mcai.ai.ConversationOpening.buildNote(
                player.getName(), playerUuid.toString().substring(0, 8), openingTier,
                lastEndMs > 0 ? nowMs - lastEndMs : -1L, !state.chatHistory().isEmpty());
        // 这是导演提示不是玩家发言 —— systemNote=true，跳过讨要/砍价/教学等玩家句式解析；
        // 统一走 planner.onPlayerMessage：LLM 忙时自动积压续调，保证 user↔assistant 严格交替
        planner.onPlayerMessage(state, player, openingNote, true);
    }

    /**
     * F 键（副手交换）→ 打开对准村民的背包 GUI。
     * <p>
     * 纯服务端插件没有客户端按键通道，F 键是原版就会发给服务器的少数按键之一：
     * 玩家对着 4 格内的村民按 F，我们就 cancel 掉原版换手，改为弹 NPC 背包。
     * 准星里不是我们管理的村民时**不 cancel**，保留原版换手行为，防止误伤正常操作。
     */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Entity target = player.getTargetEntity(4);   // 准星 4 格内（触及距离）
        if (!(target instanceof Villager villager)) return;
        VillagerState state = villagers.get(villager.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);                     // 不让副手真的交换
        VillagerInventoryGui.open(plugin, player, state);
    }

    /**
     * 右键动物喂食（v2 多物种）—— 手持对应食物右键牛/羊/猪/鸡。
     * 更新 MobState（favor+5、hunger 回满）+ 翻译官叙述气泡。
     * 不 cancel：原版繁殖照常进行，"原版管生理，AI 管情绪表现"。
     */
    @EventHandler
    public void onFeedAnimal(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof LivingEntity le)) return;
        String species = MobSpecies.of(le);
        if (species == null || !MobSpecies.isAnimal(species)) return;

        Player player = event.getPlayer();
        // 手持的不是这动物的食物 → 不管（可能是拿剑右键等原版行为）
        if (!MobSpecies.isFood(species, player.getInventory().getItemInMainHand().getType())) return;

        // 懒创建状态 + 喂食记情绪
        MobState st = mobs.computeIfAbsent(le.getUniqueId(), k -> new MobState(le, species));
        st.markFed();

        // 翻译官叙述气泡：动物不说人话，喂食就冒短句（碎片化，不写长句）
        bubbleManager.showMobBubble(le, "§7§o" + MobSpecies.pleasedPhrase(species));
    }
}
