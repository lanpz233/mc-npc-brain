package com.howard.mcai.listener;

import com.howard.mcai.gui.VillagerInventoryGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * NPC 背包 GUI 的交互拦截。
 * <p>
 * 铁律：背包里的物品**不能**被玩家直接拿走 —— 所有点击/拖拽都 cancel，
 * 只有点击"真货格"时才转发给 {@link VillagerInventoryGui#handleClick} 走偷窃流程（TheftService 托管结算）。这样"拿走"路径永远只有一条，不会漏。
 */
public final class VillagerInventoryListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VillagerInventoryGui gui)) return;

        // 无论点的是上半（NPC 背包）还是下半（玩家背包）、shift 点击、数字键换位 —— 全 cancel
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        // 只处理"点在上半部分真货格"的点击；玩家自己背包格的点击只是被拦下
        if (event.getClickedInventory() != top) return;
        // 点真货格 = 试图偷走 1 个（左右键同效，TheftService 裁决）
        gui.handleClick(player, event.getSlot(), event.isRightClick());
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VillagerInventoryGui gui)) return;
        if (event.getPlayer() instanceof Player player) gui.onClosed(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VillagerInventoryGui)) return;
        // 拖拽也可能把物品拖出去 —— 一律 cancel
        event.setCancelled(true);
    }
}
