package com.howard.mcai.shop;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.TradeRegistry;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;

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
        // 成交轻叮（素材阶段 2）：在村民交易声之外补一声收钱的清脆感
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.3f);
        plugin.getAuditLog().log(npcName(state), state.getUniqueId(), "shop_buy",
                offer.id() + " x" + offer.result().getAmount(), price + " emeralds");
        String line = "好嘞，成交！" + TradeRegistry.materialToZh(offer.result().getType())
                + "拿好，一共 " + price + " 块绿宝石。";
        Result result = ok(session, line);
        // 记忆 + 说话（Fong 2026-10-21）：成交当场把事实记进村民记忆（以后聊天能提起）；
        // 台词再请模型按他的人设说一遍，系统台词先顶着，模型回来才换
        int list = offer.emeraldPrice();
        String itemZh = TradeRegistry.materialToZh(offer.result().getType());
        state.addFact(player.getName() + " 在我店里买走了" + itemZh + "x" + offer.result().getAmount()
                + "，付了 " + price + " 块绿宝石"
                + (price < list ? "（从 " + list + " 块砍价谈成的）" : ""), player.getUniqueId());
        ShopChatter.speak(plugin, state, player, session, line,
                "他买走了你的" + itemZh + "x" + offer.result().getAmount() + "，付了 " + price + " 块绿宝石"
                        + (price < list ? "（标价 " + list + " 块，是砍价谈成的）" : "（按标价，没砍价）")
                        + "。买卖做成了。");
        return result;
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
        // 成交轻叮（素材阶段 2）：同买方向，卖成也要有收钱感
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.3f);
        plugin.getAuditLog().log(npcName(state), state.getUniqueId(), "shop_buyback",
                offer.id(), reward + " emeralds");
        String line = "这货我收了！" + reward + " 块绿宝石拿好。";
        Result result = ok(session, line);
        // 记忆 + 说话（Fong 2026-10-21）：同买方向——事实落账，台词请模型按人设说
        int listPay = offer.emeraldPrice();
        String itemZh = TradeRegistry.materialToZh(offer.cost().get(0).getType());
        state.addFact(player.getName() + " 把" + itemZh + "x" + offer.cost().get(0).getAmount()
                + " 卖给了我，我付了 " + reward + " 块绿宝石"
                + (reward > listPay ? "（从 " + listPay + " 块抬价谈成的）" : ""), player.getUniqueId());
        ShopChatter.speak(plugin, state, player, session, line,
                "他把" + itemZh + "x" + offer.cost().get(0).getAmount() + "卖给了你，你付了 " + reward + " 块绿宝石"
                        + (reward > listPay ? "（你原出 " + listPay + " 块，是他抬价谈成的）" : "（按标价，没抬价）")
                        + "。买卖做成了。");
        return result;
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

    /** 成交善后：记见面 + 好感 +1 + 标脏落盘（与原版交易窗时代的 VillagerTradeListener 一致）；
     * 另记村庄信任赎回（v0.4.0：在村里做买卖能慢慢把信任挣回来，每天有上限）；
     * 记买卖等级熟练（Fong 2026-10-23，仿原版）：成交攒笔数，升级当场告诉玩家 */
    private static void afterTrade(McaiPlugin plugin, Player player, VillagerState state) {
        state.recordMeeting(player.getUniqueId(), player.getName());
        state.emotion().addFavor(player.getUniqueId(), 1.0);
        boolean leveledUp = state.recordTrade();
        plugin.getMemoryStore().markDirty(state.getUniqueId());
        if (leveledUp) {
            player.sendMessage("§6[买卖] " + npcName(state) + " 跟你做买卖练出来了，买卖等级升到 "
                    + state.tradeLevel() + " 级，摊上会出更好的货了。");
            plugin.getLogger().info("§b[MC-AI] 买卖升级: " + npcName(state)
                    + " → " + state.tradeLevel() + " 级（累计成交 " + state.tradeCount() + " 笔）");
        }
        var v = state.entity();
        if (v != null && v.getWorld() != null) {
            var loc = v.getLocation();
            int vid = plugin.getVillageTrust().villageIdFor(
                    loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
            plugin.getVillageTrust().onTrade(player.getUniqueId(), vid,
                    v.getWorld().getFullTime() / 24000L);
            plugin.saveVillageTrust();
        }
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
