package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * 交易成交监听 —— 玩家在原版交易面板里跟 AI 村民做成一笔买卖 → 交情 +1。
 * <p>
 * 为什么需要它：好感（favor）现在按玩家独立，且陌生关系从 0 起步，
 * "关系够不够好"必须真能靠互动攒起来。聊天（+1/30秒）、送礼（+2）、教学（+2）
 * 之外，**正常做买卖**是最日常的一种互动 —— 玩家照顾了 NPC 的生意，关系自然就近一点。
 * <p>
 * 用 Paper 的 {@link PlayerTradeEvent}（成交前触发、可取消）：这里只加好感、不取消，
 * 原版交易流程完全不受影响。NPC 不在 AI 管理范围内（非我们注册的村民）→ 直接跳过。
 */
public final class VillagerTradeListener implements Listener {

    private final McaiPlugin plugin;

    public VillagerTradeListener(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTrade(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager villager)) return;
        VillagerState state = plugin.getVillagers().get(villager.getUniqueId());
        if (state == null) return;          // 不是我们管理的村民，不管

        Player player = event.getPlayer();
        state.recordMeeting(player.getUniqueId(), player.getName());
        state.emotion().addFavor(player.getUniqueId(), 1.0);
        plugin.getMemoryStore().markDirty(state.getUniqueId());
    }
}
