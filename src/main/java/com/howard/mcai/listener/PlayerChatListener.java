package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.planner.CentralPlanner;
import com.howard.mcai.planner.GroupChatCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 玩家聊天监听 —— 对话模式后打字立刻触发 LLM；未对话时自动匹配附近 AI 村民。
 * <p>
 * 线程安全（关键！）：
 * AsyncPlayerChatEvent 在 **Paper 异步线程** 触发，但：
 * - chatHistory.append 可以在任何线程（synchronized 保护）
 * - **所有 PAPER API 调用**（getNearbyLivingEntities / world.getTime / entity.getLocation 等）
 *   必须切主线程！所以 triggerLLM（内部调 buildSceneContext）必须 runTask 后再调。
 *   否则抛 IllegalStateException: Asynchronous getNearbyEntities!
 *
 * <p>砍价关键词拦截：已移到 CentralPlanner.onPlayerMessage 统一处理，
 * 对话模式和自动匹配两条路径都能触发，且在 LLM 之前生效。
 */
public final class PlayerChatListener implements Listener {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final CentralPlanner planner;
    private final GroupChatCoordinator groupChat;

    /** 自动接入的扫描半径（格） */
    private static final double AUTO_MATCH_RADIUS = 10.0;

    public PlayerChatListener(McaiPlugin plugin, Map<UUID, VillagerState> villagers,
                              CentralPlanner planner, GroupChatCoordinator groupChat) {
        this.plugin = plugin;
        this.villagers = villagers;
        this.planner = planner;
        this.groupChat = groupChat;
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        UUID playerUuid = player.getUniqueId();

        // === 第一步：找这个玩家正在对话的村民 ===
        // 只读内存字段（interactingPlayer），无 Paper API，异步线程安全；
        // villagers 是 ConcurrentHashMap（McaiPlugin 里声明），主线程同时可能 put 新注册的村民，
        // 弱一致迭代也不会像普通 HashMap 那样抛 CME/读到撕裂状态 —— 所以这里直接遍历是安全的
        VillagerState talkingTo = null;
        for (VillagerState state : villagers.values()) {
            if (playerUuid.equals(state.interactingPlayer())) {
                talkingTo = state;
                break;
            }
        }

        VillagerState captured = talkingTo;
        Bukkit.getScheduler().runTask(plugin, () -> {
            // === 第零步：神使传送确认（SPEC 2.14）===
            // 神使 NPC 要传送该玩家时，玩家须在聊天里回复"好"。确认词命中 → 执行传送，
            // 该消息不再进对话流程（防止"好"被当成聊天内容触发 LLM）。
            // 没有待确认请求 / 回复的不是确认词 → 照常往下走，pending 留给下次或超时。
            if (plugin.getWorldInteraction() != null
                    && plugin.getWorldInteraction().consumeTeleportConfirm(player, message)) {
                return;
            }

            if (captured != null) {
                // 消息入历史 + 触发 LLM 统一走 planner（主线程）：
                // - 砍价关键词拦截在 planner.onPlayerMessage 里做（自动匹配路径同样生效）
                // - LLM 忙时自动积压，等当前响应后再入历史+续调，保证 user↔assistant 严格交替
                planner.onPlayerMessage(captured, player, message);
                // 群体聊天：附近其他 NPC 规则附和，点名才单独 LLM 回复
                groupChat.handleGroupChat(player, message, captured);
                return;
            }

            // === 第二步：没在对话 → 自动接入最近的 AI 村民 ===
            // findNearestAIVillager 用到 entity.isValid()/getLocation() 等 Paper API，必须切主线程
            VillagerState autoMatch = findNearestAIVillager(player);
            if (autoMatch != null) {
                // 多 NPC：说话直接切给最近的那个，其他人"闭嘴"
                planner.switchConversation(player, autoMatch);
                planner.onPlayerMessage(autoMatch, player,
                        "Player " + player.getName() + ": " + message);
                // 群体聊天：主 NPC 之外的邻居也参与群聊反应
                groupChat.handleGroupChat(player, message, autoMatch);
            }
        });
    }

    /**
     * 找玩家附近最近的 AI 村民（10 格内）。
     * <p>
     * v2 多 NPC：原来要求"恰好 1 个"才响应，2 个以上就歧义拒绝 ——
     * 这导致玩家只能跟唯一的 NPC 聊天。改成"最近优先"：
     * 想跟谁聊就走近谁，右键可精确指定，说话会切走当前 NPC 的对话状态。
     */
    private VillagerState findNearestAIVillager(Player player) {
        VillagerState nearest = null;
        double best = AUTO_MATCH_RADIUS;
        for (VillagerState state : villagers.values()) {
            if (!state.entity().isValid()) continue;
            double dist = state.entity().getLocation().distance(player.getLocation());
            if (dist <= best) {
                best = dist;
                nearest = state;
            }
        }
        return nearest;
    }
}
