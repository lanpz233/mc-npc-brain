package com.howard.mcai.listener;

import com.howard.mcai.gui.AnvilPriceInput;
import com.howard.mcai.gui.ShopGui;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 商店界面事件 —— 与 VillagerInventoryListener 同一套路：
 * 顶层窗口是商店/出价输入框时，一切点击拖拽都拦下，只把「点了哪一格」转给 GUI 自己处理，
 * 防玩家把展示品真拿走（格子里的都是样品，结算在插件侧）。
 */
public final class ShopGuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        var top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ShopGui gui) {
            event.setCancelled(true);
            // 只认点在顶层窗口里的格子（点自己背包只拦不处理）
            if (event.getClickedInventory() == top) {
                gui.handleClick(event.getSlot(), event.isRightClick());
            }
            return;
        }
        if (top.getHolder() instanceof AnvilPriceInput input) {
            event.setCancelled(true);
            // 铁砧结果格（raw slot 2）= 确认出价；其余格子只拦不处理
            if (event.getRawSlot() == 2) {
                input.submit();
                event.getWhoClicked().closeInventory();
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        var holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ShopGui || holder instanceof AnvilPriceInput) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AnvilPriceInput input) {
            input.handleClose();
        }
        // 商店窗口关掉不需要清理：进度在 ShopSession 里，回来接着用
    }
}
