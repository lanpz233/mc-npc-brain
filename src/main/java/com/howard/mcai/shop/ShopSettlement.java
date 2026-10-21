package com.howard.mcai.shop;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.TradeRegistry;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 商店结算 —— 界面点下「买/卖」之后唯一算数的地方。
 * <p>
 * 规矩：先复核（钱够不够、原料带没带、背包塞不塞得下、村民钱够不够付收购款），
 * 全部通过才一次性转移，绝不半执行。玩家付的绿宝石真扣、真进村民背包；
 * 收购的钱真从村民背包出（他没钱就收不了）。成交给好感 +1（与原版交易窗时代一致）。
 * 卖货价以会话里的成交价为准（砍价结果），不是界面显示什么就信什么 ——
 * 结算时用 ShopSession.effectivePrice 重算，界面只是皮。
 */
public final class ShopSettlement {

    private ShopSettlement() {
    }

    public record Result(boolean ok, String line) {
    }

    // ===== 卖货：玩家买 =====

    public static Result buy(McaiPlugin plugin, Player player, VillagerState state,
                             ShopCatalog.Offer offer, ShopSession session) {
        PlayerInventory inv = player.getInventory();
        int price = session.effectivePrice(offer.id(), offer.emeraldPrice());

        // 复核 1：绿宝石（价以会话成交价为准，替换配方里的绿宝石部分）
        int have = countItem(inv, Material.EMERALD);
        if (have < price) {
            return fail(session, "你绿宝石不够啊，还差 " + (price - have) + " 块。");
        }
        // 复核 2：附带原料（加工类商品要玩家自带的那份）
        for (ItemStack cost : offer.cost()) {
            if (cost.getType() == Material.EMERALD) continue;
            if (!inv.containsAtLeast(cost, cost.getAmount())) {
                return fail(session, "还差 " + TradeRegistry.materialToZh(cost.getType())
                        + " 呢，你没带够，我没法给你做。");
            }
        }
        // 复核 3：玩家背包塞得下
        if (!hasSpaceFor(inv, offer.result())) {
            return fail(session, "你包里塞不下了，先腾点地方再来吧。");
        }

        // 执行（一次性）：扣钱 → 扣原料 → 交货 → 村民入账
        inv.removeItem(new ItemStack(Material.EMERALD, price));
        for (ItemStack cost : offer.cost()) {
            if (cost.getType() == Material.EMERALD) continue;
            inv.removeItem(cost.clone());
        }
        inv.addItem(offer.result().clone());
        if (price > 0) state.addToInventory(Material.EMERALD, price);

        afterTrade(plugin, player, state);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
        plugin.getAuditLog().log(npcName(state), state.getUniqueId(), "shop_buy",
                offer.id() + " x" + offer.result().getAmount(), price + " emeralds");
        return ok(session, "好嘞，成交！" + TradeRegistry.materialToZh(offer.result().getType())
                + "拿好，一共 " + price + " 块绿宝石。");
    }

    // ===== 收购：玩家卖 =====

    public static Result buyBack(McaiPlugin plugin, Player player, VillagerState state,
                                 ShopCatalog.Offer offer, ShopSession session) {
        PlayerInventory inv = player.getInventory();
        int reward = session.effectivePay(offer.id(), offer.emeraldPrice());   // 抬过价按谈成价付，没抬按标价

        // 复核 1：玩家真有那么多货
        for (ItemStack cost : offer.cost()) {
            if (!inv.containsAtLeast(cost, cost.getAmount())) {
                return fail(session, "你身上没那么多 "
                        + TradeRegistry.materialToZh(cost.getType()) + " 啊。");
            }
        }
        // 复核 2：村民背包真付得出（钱要真实存在，不凭空付）
        if (state.stockOf(Material.EMERALD) < reward) {
            return fail(session, "我手头绿宝石不够，收不了你这货，等我做成几笔买卖再来吧。");
        }
        // 复核 3：玩家背包塞得下绿宝石（按下单要付的数算，不是标价）
        ItemStack payStack = new ItemStack(Material.EMERALD, reward);
        if (!hasSpaceFor(inv, payStack)) {
            return fail(session, "你包里塞不下了，先腾点地方再来吧。");
        }

        // 执行：扣货 → 村民出钱 → 玩家收钱 → 货进村民背包
        for (ItemStack cost : offer.cost()) {
            inv.removeItem(cost.clone());
            state.addToInventory(cost.getType(), cost.getAmount());
        }
        state.removeFromInventory(Material.EMERALD, reward);
        inv.addItem(payStack);

        afterTrade(plugin, player, state);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
        plugin.getAuditLog().log(npcName(state), state.getUniqueId(), "shop_buyback",
                offer.id(), reward + " emeralds");
        return ok(session, "这货我收了！" + reward + " 块绿宝石拿好。");
    }

    // ===== 共用小件 =====

    private static Result ok(ShopSession session, String line) {
        session.setNpcLine(line);
        return new Result(true, line);
    }

    private static Result fail(ShopSession session, String line) {
        session.setNpcLine(line);
        return new Result(false, line);
    }

    /** 成交善后：记见面 + 好感 +1 + 标脏落盘（与原版交易窗时代的 VillagerTradeListener 一致） */
    private static void afterTrade(McaiPlugin plugin, Player player, VillagerState state) {
        state.recordMeeting(player.getUniqueId(), player.getName());
        state.emotion().addFavor(player.getUniqueId(), 1.0);
        plugin.getMemoryStore().markDirty(state.getUniqueId());
    }

    public static int countItem(PlayerInventory inv, Material mat) {
        int total = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && s.getType() == mat) total += s.getAmount();
        }
        return total;
    }

    /** 玩家储物格（0–35）能否完整塞下这叠东西（空位 + 同类半叠都算） */
    static boolean hasSpaceFor(PlayerInventory inv, ItemStack stack) {
        int needed = stack.getAmount();
        int max = stack.getMaxStackSize();
        for (ItemStack s : inv.getStorageContents()) {
            if (needed <= 0) return true;
            if (s == null || s.getType() == Material.AIR) {
                needed -= max;
            } else if (s.isSimilar(stack)) {
                needed -= (max - s.getAmount());
            }
        }
        return needed <= 0;
    }

    private static String npcName(VillagerState state) {
        var e = state.entity();
        String cn = (e != null) ? e.getCustomName() : null;
        if (cn != null && !cn.isBlank()) return cn.replaceAll("§.", "");
        return state.profession().zhName;
    }
}
