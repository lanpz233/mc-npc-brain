package com.howard.mcai.bot;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 右键假人 = 开始/结束对话（Fong 要求，与村民同制式，不再靠凑近说话触发）。
 * 事件取消掉，防止 Leaves 的"打开假人背包"抢先弹背包界面。
 */
public final class BotInteractListener implements Listener {

    private final BotService svc;

    public BotInteractListener(BotService svc) { this.svc = svc; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // 主副手各触发一次，只认主手
        if (!(e.getRightClicked() instanceof Player target)) return;
        if (!BotService.isBotPlayer(target)) return;
        BotAgent a = svc.agentByUuid(target.getUniqueId());
        if (a == null) { // 还没被认领扫描扫到：现场认领
            svc.adoptPlayer(target);
            a = svc.agentByUuid(target.getUniqueId());
        }
        if (a == null) return;
        e.setCancelled(true);
        svc.chat().toggleConversation(e.getPlayer(), a);
    }
}
