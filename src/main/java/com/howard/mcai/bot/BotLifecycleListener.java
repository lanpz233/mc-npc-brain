package com.howard.mcai.bot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 假人生死/离场登记：死了记一笔（档案不删，同名再招募算本人回来），
 * 离场摘掉活体引用。掉落由 Leaves 自己处理（假人死了掉光，这是它的规矩）。
 */
public final class BotLifecycleListener implements Listener {

    private final BotService svc;

    public BotLifecycleListener(BotService svc) { this.svc = svc; }

    /** 通用实体死亡（Leaves 假人死亡不走 PlayerDeathEvent，实测；这条是正路）。 */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player p)) return;
        if (!BotService.isBotPlayer(p)) return;
        recordDeath(p);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!BotService.isBotPlayer(event.getEntity())) return;
        recordDeath(event.getEntity());
    }

    private void recordDeath(org.bukkit.entity.Player p) {
        BotAgent a = svc.agentByUuid(p.getUniqueId());
        if (a == null || a.deadRecordedAt > System.currentTimeMillis() - 5_000) return; // 双事件去重
        a.deadRecordedAt = System.currentTimeMillis();
        a.state.deaths++;
        a.state.addFact("死过一次");
        a.state.fear = Math.min(100, a.state.fear + 30);
        svc.plugin().getLogger().info("[MC-AI] 假人 " + a.state.chineseName + "（" + a.state.accountName
                + "）死亡，累计 " + a.state.deaths + " 次");
        svc.storeDirty();
        svc.onBotGone(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!BotService.isBotPlayer(event.getPlayer())) return;
        svc.onBotGone(event.getPlayer());
    }
}
