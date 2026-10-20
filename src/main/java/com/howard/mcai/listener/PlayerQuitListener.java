package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家下线监听 —— 落地硬约束"玩家下线时全量整理记忆"。
 * <p>
 * 整理内容（VillagerState.consolidateForPlayerQuit）：
 * <ul>
 *   <li>全部 recent（chatHistory）一条不留并入 summary</li>
 *   <li>追加"昨日小结"（日期 + 谁来过 + 最近聊了什么的浓缩版）</li>
 *   <li>顺带执行 facts 的 7 天遗忘（大嘴巴豁免的不丢）</li>
 * </ul>
 * 下次玩家上线右键 NPC 时，PromptBuilder 的 memory block 会带上这段 summary，
 * NPC 开场白自然引用昨日小结（"你回来啦，昨天咱聊到..."）。
 * <p>
 * 线程：PlayerQuitEvent 在主线程触发，整理只动 VillagerState 内存字段（synchronized），
 * markDirty 走既有防抖落盘，无需切异步，也不碰 Bukkit API 之外的东西。
 */
public final class PlayerQuitListener implements Listener {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;

    public PlayerQuitListener(McaiPlugin plugin, Map<UUID, VillagerState> villagers) {
        this.plugin = plugin;
        this.villagers = villagers;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID pid = player.getUniqueId();
        String dateLabel = LocalDate.now().toString();
        int organized = 0;

        for (VillagerState state : villagers.values()) {
            UUID inter = state.interactingPlayer();

            // 正在跟**别的在线玩家**对话 → 整个跳过：不能为了下线整理打断别人的会话
            if (inter != null && !inter.equals(pid)) {
                Player other = Bukkit.getPlayer(inter);
                if (other != null && other.isOnline()) continue;
                // 对话对象早已离线的兜底清理 —— 不清的话村民一直 setAI(false) 冻在原地
                state.endConversation();
            }
            // 跟下线玩家的对话 → 立刻结束并恢复原版 AI（否则要等 30s 超时才解冻）
            if (pid.equals(inter)) {
                state.endConversation();
            }

            // 全量整理（LLM 响应在飞时内部会自行跳过，返回 false 不落盘）
            if (state.consolidateForPlayerQuit(player.getName(), dateLabel)) {
                plugin.getMemoryStore().markDirty(state.getUniqueId());
                organized++;
            }
        }

        plugin.getLogger().info("§b[MC-AI] 玩家 " + player.getName()
                + " 下线，全量整理了 " + organized + " 个村民的记忆（recent→summary + 昨日小结）");
    }
}
