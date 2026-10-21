package com.howard.mcai.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.function.Consumer;

/**
 * 铁砧输入框 —— 路线二里代替「界面内打字」的办法（商店窗口开着不能聊天）。
 * 放一张纸（名字预填标价），玩家把名字改成自己想出的总价，点结果格确认。
 * 结果经回调返回：整数 = 玩家填的总价；null = 关窗取消/没填明白。
 * 回调只触发一次（确认或关窗先到为准），由 ShopGuiListener 驱动。
 */
public final class AnvilPriceInput implements InventoryHolder {

    private final Player player;
    private final Consumer<Integer> callback;
    private final Inventory inventory;
    private boolean done = false;

    public AnvilPriceInput(Player player, int currentPrice, Consumer<Integer> callback) {
        this.player = player;
        this.callback = callback;
        this.inventory = Bukkit.createInventory(this, org.bukkit.event.inventory.InventoryType.ANVIL,
                "把数字改成你想出的总价");
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(String.valueOf(currentPrice));
            paper.setItemMeta(meta);
        }
        inventory.setItem(0, paper);
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** 点了结果格：读铁砧的改名文本，解析成整数总价（解析不了按取消处理） */
    public void submit() {
        if (done) return;
        done = true;
        Integer value = null;
        if (inventory instanceof org.bukkit.inventory.AnvilInventory anvil) {
            String text = anvil.getRenameText();
            if (text != null) {
                try {
                    value = Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    value = null;
                }
            }
        }
        callback.accept(value);
    }

    /** 关窗（没点确认）：按取消回调一次 */
    public void handleClose() {
        if (done) return;
        done = true;
        callback.accept(null);
    }
}
