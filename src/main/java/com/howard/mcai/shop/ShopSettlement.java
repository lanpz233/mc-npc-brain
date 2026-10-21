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

    // ===== 买货 + 以物抵价（v0.4.0：以物换物搬进商店） =====

    /**
     * 买下商品，货款一部分用玩家篮子里的东西抵（按村民收购牌价整捆计价，
     * 见 {@link BarterPlan}），差价用绿宝石补付/找零。其余复核与 {@link #buy} 同口径。
     */
    public static Result buyWithBarter(McaiPlugin plugin, Player player, VillagerState state,
                                       ShopCatalog.Offer offer, ShopSession session) {
        PlayerInventory inv = player.getInventory();
        int price = session.effectivePrice(offer.id(), offer.emeraldPrice());

        // 抵价条款与计划（以结算时刻的目录/钱包为准，不信界面预览）
        var offers = ShopCatalog.fromRecipes(state.buildCurrentRecipes());
        var terms = BarterPlan.termsFrom(offers);
        Map<String, Integer> basketNames = session.barterBasket();
        Map<Material, Integer> basket = new java.util.LinkedHashMap<>();
        for (var e : basketNames.entrySet()) {
            Material m = Material.matchMaterial(e.getKey());
            if (m != null && e.getValue() > 0) basket.put(m, e.getValue());
        }
        BarterPlan.Plan plan = BarterPlan.plan(basket, terms, price, state.stockOf(Material.EMERALD));
        if (plan.consumed().isEmpty()) {
            return fail(session, "你选的东西还凑不成一捆，抵不了价——看看收购清单，论捆才算数。");
        }
        if (plan.changeUnaffordable()) {
            return fail(session, "你抵的货超了价，我兜里绿宝石不够找零——少抵一件吧。");
        }
        // 复核 1：要抵的货真在玩家包里
        for (var e : plan.consumed().entrySet()) {
            if (!inv.containsAtLeast(new ItemStack(e.getKey()), e.getValue())) {
                return fail(session, "你包里没有那么多 " + TradeRegistry.materialToZh(e.getKey()) + " 了。");
            }
        }
        // 复核 2：补付的绿宝石够不够
        int have = countItem(inv, Material.EMERALD);
        if (have < plan.emeraldDue()) {
            return fail(session, "抵完还差 " + plan.emeraldDue() + " 块绿宝石，你身上不够。");
        }
        // 复核 3：附带原料（与 buy 同规）
        for (ItemStack cost : offer.cost()) {
            if (cost.getType() == Material.EMERALD) continue;
            if (!inv.containsAtLeast(cost, cost.getAmount())) {
                return fail(session, "还差 " + TradeRegistry.materialToZh(cost.getType())
                        + " 呢，你没带够，我没法给你做。");
            }
        }
        // 复核 4：玩家背包塞得下商品（和可能找回的零钱）
        if (!hasSpaceFor(inv, offer.result())) {
            return fail(session, "你包里塞不下了，先腾点地方再来吧。");
        }
        if (plan.change() > 0 && !hasSpaceFor(inv, new ItemStack(Material.EMERALD, plan.change()))) {
            return fail(session, "你包里塞不下找零了，先腾点地方吧。");
        }

        // 执行（一次性）：收抵价货 → 补付/找零 → 扣原料 → 交货
        StringBuilder goodsDesc = new StringBuilder();
        for (var e : plan.consumed().entrySet()) {
            inv.removeItem(new ItemStack(e.getKey(), e.getValue()));
            state.addToInventory(e.getKey(), e.getValue());
            if (goodsDesc.length() > 0) goodsDesc.append("、");
            goodsDesc.append(TradeRegistry.materialToZh(e.getKey())).append("x").append(e.getValue());
        }
        if (plan.emeraldDue() > 0) {
            inv.removeItem(new ItemStack(Material.EMERALD, plan.emeraldDue()));
            state.addToInventory(Material.EMERALD, plan.emeraldDue());
        }
        if (plan.change() > 0) {
            state.removeFromInventory(Material.EMERALD, plan.change());
            inv.addItem(new ItemStack(Material.EMERALD, plan.change()));
        }
        for (ItemStack cost : offer.cost()) {
            if (cost.getType() == Material.EMERALD) continue;
            inv.removeItem(cost.clone());
        }
        inv.addItem(offer.result().clone());
        session.clearBarter();

        afterTrade(plugin, player, state);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.3f);
        plugin.getAuditLog().log(npcName(state), state.getUniqueId(), "shop_buy_barter",
                offer.id() + " x" + offer.result().getAmount(),
                "goods=" + plan.goodsValue() + " due=" + plan.emeraldDue() + " change=" + plan.change());
        String itemZh = TradeRegistry.materialToZh(offer.result().getType());
        String line = "成交！" + itemZh + "拿好。你拿" + goodsDesc + "抵了 " + plan.goodsValue() + " 块"
                + (plan.emeraldDue() > 0 ? "，又补了 " + plan.emeraldDue() + " 块绿宝石"
                        : plan.change() > 0 ? "，我找你 " + plan.change() + " 块绿宝石" : "")
                + "。";
        Result result = ok(session, line);
        int list = offer.emeraldPrice();
        state.addFact(player.getName() + " 在我店里买走了" + itemZh + "x" + offer.result().getAmount()
                + "，拿" + goodsDesc + "抵了 " + plan.goodsValue() + " 块绿宝石"
                + (plan.emeraldDue() > 0 ? "、补付 " + plan.emeraldDue() + " 块" : "")
                + (price < list ? "（成交价 " + price + "，是砍价谈成的）" : ""), player.getUniqueId());
        ShopChatter.speak(plugin, state, player, session, line,
                "他买走了你的" + itemZh + "，拿" + goodsDesc + "抵了 " + plan.goodsValue() + " 块绿宝石"
                        + (plan.emeraldDue() > 0 ? "，还补了 " + plan.emeraldDue() + " 块现金" : "")
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
     * 另记村庄信任赎回（v0.4.0：在村里做买卖能慢慢把信任挣回来，每天有上限） */
    private static void afterTrade(McaiPlugin plugin, Player player, VillagerState state) {
        state.recordMeeting(player.getUniqueId(), player.getName());
        state.emotion().addFavor(player.getUniqueId(), 1.0);
        plugin.getMemoryStore().markDirty(state.getUniqueId());
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
