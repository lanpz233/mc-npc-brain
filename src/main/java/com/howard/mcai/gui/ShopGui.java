package com.howard.mcai.gui;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.TradeRegistry;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.BargainPolicy;
import com.howard.mcai.shop.ShopCatalog;
import com.howard.mcai.shop.ShopSession;
import com.howard.mcai.shop.ShopSettlement;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店界面（路线二）—— 村民自己的店，不再借用原版交易窗。
 * <p>
 * 三个视图：商品列表 → 商品详情（买/卖、砍价入口）→ 砍价面板（自选砍几块 / 铁砧输入总价）。
 * 价格与进度都在 {@link ShopSession} 里，关窗聊天再回来不丢；结算只认
 * {@link ShopSettlement} 的复核结果，界面显示的价格不算数。
 * 砍价底线由 {@link BargainPolicy} 守：砍超了村民还价到底线价，玩家确认才落定。
 * <p>
 * 外观先走「整齐的货架 + 价签 + 按钮」排版，资源包皮肤（商店背景图）在后续一件换上。
 */
public final class ShopGui implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 28;   // 内区 4 行 × 7 列
    private static final int[] GRID_SLOTS = buildGridSlots();

    private enum View {LIST, DETAIL, BARGAIN}

    private final McaiPlugin plugin;
    private final VillagerState state;
    private final Player viewer;
    private final ShopSession session;
    private final Inventory inventory;

    private View view = View.LIST;
    private boolean buyMode = false;   // false=商品（玩家买） true=收购（玩家卖）
    private int page = 0;
    /** 当前渲染里 槽位 → offerId（点击路由） */
    private final Map<Integer, String> slotOffers = new HashMap<>();

    private ShopGui(McaiPlugin plugin, Player viewer, VillagerState state, ShopSession session) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.state = state;
        this.session = session;
        this.inventory = Bukkit.createInventory(this, SIZE, title());
    }

    /**
     * 窗口标题：开了资源包皮肤（config shop.resource-pack-skin）→ 用负空间字 + 背景字形
     * （资源包 MCAI-Shop 的 minecraft:mcai_shop 字体），整窗是一张木柜台图、没有文字标题
     * （店名在界面里的村民名牌上）；没开 → 普通文字标题，没装包的客户端不会看到方框字。
     */
    private net.kyori.adventure.text.Component title() {
        if (plugin.getPluginConfig().shopSkinEnabled()) {
            return net.kyori.adventure.text.Component.text("\uF808\uE100")
                    .font(net.kyori.adventure.key.Key.key("minecraft", "mcai_shop"));
        }
        return net.kyori.adventure.text.Component.text("§6" + npcName() + "的小店");
    }

    /** 打开商店（主线程）。会话按（村民，玩家）取/建：关窗重开还是同一份进度 */
    public static void open(McaiPlugin plugin, Player viewer, VillagerState state) {
        if (plugin == null || viewer == null || state == null) return;
        plugin.getShopSessions().pruneExpired();
        ShopSession session = plugin.getShopSessions()
                .getOrCreate(state.getUniqueId(), viewer.getUniqueId());
        if (session.npcLine() == null) {
            session.setNpcLine("来啦？随便看，都是实在货。");
        }
        ShopGui gui = new ShopGui(plugin, viewer, state, session);
        gui.rebuild();
        viewer.openInventory(gui.inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public java.util.UUID villagerUuid() {
        return state.getUniqueId();
    }

    // ===== 渲染 =====

    /** 按会话 + 当前视图重画整个界面（复用同一 Inventory 实例，窗口不闪） */
    public void rebuild() {
        inventory.clear();
        slotOffers.clear();
        fillBackground();

        List<ShopCatalog.Offer> catalog = ShopCatalog.fromRecipes(state.buildCurrentRecipes());
        ShopCatalog.Offer selected = null;
        if (session.selectedOfferId() != null) {
            for (ShopCatalog.Offer o : catalog) {
                if (o.id().equals(session.selectedOfferId())) {
                    selected = o;
                    break;
                }
            }
            if (selected == null) {   // 商品已下架等原因消失 → 回列表
                session.select(null);
                view = View.LIST;
            }
        }

        inventory.setItem(4, npcCard());
        switch (view) {
            case LIST -> renderList(catalog);
            case DETAIL -> renderDetail(selected);
            case BARGAIN -> renderBargain(selected);
        }
    }

    private void renderList(List<ShopCatalog.Offer> catalog) {
        List<ShopCatalog.Offer> filtered = new ArrayList<>();
        boolean hasBuyBack = false;
        for (ShopCatalog.Offer o : catalog) {
            if (o.buyBack()) hasBuyBack = true;
            if (o.buyBack() == buyMode) filtered.add(o);
        }
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) page = pages - 1;
        int from = page * PAGE_SIZE;
        int to = Math.min(filtered.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            int slot = GRID_SLOTS[i - from];
            ShopCatalog.Offer o = filtered.get(i);
            inventory.setItem(slot, listItem(o));
            slotOffers.put(slot, o.id());
        }
        // 底部控制行
        if (hasBuyBack) {
            inventory.setItem(45, button(buyMode ? Material.CHEST : Material.HOPPER,
                    buyMode ? "§e看商品" : "§e收购：把你的东西卖给他",
                    List.of("§7点一下切换")));
        }
        inventory.setItem(47, balanceCard());
        if (page > 0) inventory.setItem(48, button(Material.ARROW, "§f上一页", List.of()));
        if (page < pages - 1) inventory.setItem(50, button(Material.ARROW, "§f下一页", List.of()));
        inventory.setItem(53, button(Material.BARRIER, "§c关店", List.of("§7关上窗口也能回来，进度不丢")));
    }

    private void renderDetail(ShopCatalog.Offer o) {
        if (o == null) {
            view = View.LIST;
            renderList(ShopCatalog.fromRecipes(state.buildCurrentRecipes()));
            return;
        }
        inventory.setItem(22, detailItem(o));
        Integer counter = session.pendingCounter(o.id());
        inventory.setItem(38, button(Material.ARROW, "§f回商品列表", List.of()));
        if (counter != null) {
            // 村民已还价，等玩家拍板：接受才落定，不接受就清掉还价
            inventory.setItem(40, button(Material.EMERALD_BLOCK,
                    "§a就按 " + counter + " 块成交", List.of("§7接受村民的还价，谈好的价记下")));
            inventory.setItem(42, button(Material.BARRIER,
                    "§c不接受，算了", List.of("§7不按这个价，回头再说")));
            return;
        }
        if (o.buyBack()) {
            int reward = session.effectivePay(o.id(), o.emeraldPrice());
            int funds = state.stockOf(Material.EMERALD);
            inventory.setItem(40, button(Material.GOLD_BLOCK,
                    "§a卖给他 · 得 " + reward + " 块绿宝石",
                    session.agreedPay(o.id()) != null
                            ? List.of("§7这是你们谈好的价", "§7他手头现有 " + funds + " 块绿宝石")
                            : List.of("§7他手头现有 " + funds + " 块绿宝石")));
            int cap = Math.min(BargainPolicy.maxPay(o.emeraldPrice(),
                    state.emotion().favor(viewer.getUniqueId())), funds);
            if (reward >= cap) {
                inventory.setItem(42, button(Material.GRAY_STAINED_GLASS_PANE,
                        "§7他出到顶啦",
                        List.of(funds <= reward ? "§7他兜里就这么多绿宝石了" : "§7交情到顶，他最多出这么多")));
            } else {
                inventory.setItem(42, button(Material.ANVIL,
                        "§e抬价 · 自己选抬多少",
                        session.agreedPay(o.id()) != null
                                ? List.of("§7已经谈到 " + reward + " 块，还想再抬就点这儿")
                                : List.of("§7能抬多少看交情，也看他兜里有多少")));
            }
        } else {
            int price = session.effectivePrice(o.id(), o.emeraldPrice());
            inventory.setItem(40, button(Material.EMERALD_BLOCK,
                    "§a买下它 · " + price + " 块绿宝石",
                    session.hasBargained(o.id())
                            ? List.of("§7这是你们谈好的价")
                            : List.of("§7点一下直接买（也可以先砍价）")));
            int floor = BargainPolicy.floorPrice(o.emeraldPrice(),
                    state.emotion().favor(viewer.getUniqueId()));
            if (price <= floor) {
                inventory.setItem(42, button(Material.GRAY_STAINED_GLASS_PANE,
                        "§7已经到底价啦", List.of("§7交情再好也不能低于这个价")));
            } else {
                inventory.setItem(42, button(Material.ANVIL,
                        "§e砍价 · 自己选砍多少",
                        session.hasBargained(o.id())
                                ? List.of("§7已经谈到 " + price + " 块，还想再砍就点这儿")
                                : List.of("§7能砍多少看你们的交情")));
            }
        }
        inventory.setItem(47, balanceCard());
    }

    private void renderBargain(ShopCatalog.Offer o) {
        if (o == null) {
            view = View.LIST;
            rebuild();
            return;
        }
        if (o.buyBack()) {
            renderRaise(o);
            return;
        }
        int list = o.emeraldPrice();
        int current = session.effectivePrice(o.id(), list);
        int floor = BargainPolicy.floorPrice(list, state.emotion().favor(viewer.getUniqueId()));
        inventory.setItem(22, detailItem(o));
        if (current <= floor) {
            inventory.setItem(31, button(Material.GRAY_STAINED_GLASS_PANE,
                    "§7已经到底价了", List.of("§7现在就是 " + current + " 块，再砍他也不让了")));
        } else {
            // 砍幅按钮 1~10 两排摆开（Fong 2026-10-21 要求）：上排 1–5、下排 6–10
            for (int i = 0; i < 5; i++) inventory.setItem(28 + i, cutButton(i + 1, current));
            for (int i = 0; i < 5; i++) inventory.setItem(37 + i, cutButton(i + 6, current));
            inventory.setItem(49, button(Material.ANVIL,
                    "§e自己出价 · 输入总价", List.of("§7打开小输入框，填你想出的总价（整数）")));
        }
        inventory.setItem(45, button(Material.ARROW, "§f回商品页", List.of()));
    }

    /** 抬价面板（卖货方向）：与砍价面板同布局，按钮是「抬 N 块」，上限受交情与他的钱包双重限制 */
    private void renderRaise(ShopCatalog.Offer o) {
        int listPay = o.emeraldPrice();
        int current = session.effectivePay(o.id(), listPay);
        int funds = state.stockOf(Material.EMERALD);
        int cap = Math.min(BargainPolicy.maxPay(listPay, state.emotion().favor(viewer.getUniqueId())), funds);
        inventory.setItem(22, detailItem(o));
        if (current >= cap) {
            inventory.setItem(31, button(Material.GRAY_STAINED_GLASS_PANE,
                    "§7他出到顶啦",
                    List.of(funds <= current
                            ? "§7他兜里就 " + funds + " 块绿宝石，抬不动了"
                            : "§7现在就是 " + current + " 块，交情到顶了")));
        } else {
            // 抬幅按钮 1~10 两排摆开，与砍价面板一致
            for (int i = 0; i < 5; i++) inventory.setItem(28 + i, raiseButton(i + 1, current));
            for (int i = 0; i < 5; i++) inventory.setItem(37 + i, raiseButton(i + 6, current));
            inventory.setItem(49, button(Material.ANVIL,
                    "§e自己出价 · 输入总价", List.of("§7打开小输入框，填你想让他出的总价（整数）")));
        }
        inventory.setItem(45, button(Material.ARROW, "§f回商品页", List.of()));
    }

    private ItemStack raiseButton(int raise, int currentPay) {
        return button(Material.GOLD_INGOT, "§e抬 " + raise + " 块",
                List.of("§7现在他出 " + currentPay + " 块，抬完想让他出 " + (currentPay + raise) + " 块，点一下试试"));
    }

    private ItemStack cutButton(int cut, int currentPrice) {
        if (cut >= currentPrice) {
            return button(Material.GRAY_STAINED_GLASS_PANE, "§7砍 " + cut + " 块",
                    List.of("§7现在总共才 " + currentPrice + " 块，砍不了这么多"));
        }
        return button(Material.GOLD_INGOT, "§e砍 " + cut + " 块",
                List.of("§7现在 " + currentPrice + " 块，砍完想按 " + (currentPrice - cut) + " 块买，点一下试试"));
    }

    // ===== 展示小件 =====

    private ItemStack listItem(ShopCatalog.Offer o) {
        ItemStack icon;
        List<String> lore = new ArrayList<>();
        if (o.buyBack()) {
            icon = o.cost().get(0).clone();
            lore.add("§7收购：你交出 §f" + zh(o.cost().get(0).getType()) + " x" + o.cost().get(0).getAmount());
            lore.add("§7他付你 §a" + session.effectivePay(o.id(), o.emeraldPrice()) + " 块绿宝石");
            lore.add("§e点一下查看");
        } else {
            icon = o.result().clone();
            int list = o.emeraldPrice();
            Integer agreed = session.agreedPrice(o.id());
            if (agreed != null) {
                lore.add("§7原价 " + list + " 块 → §a谈成价 " + agreed + " 块绿宝石");
            } else {
                lore.add("§7价格 §a" + list + " 块绿宝石");
            }
            for (ItemStack c : o.cost()) {
                if (c.getType() != Material.EMERALD) {
                    lore.add("§7另需自备：" + zh(c.getType()) + " x" + c.getAmount());
                }
            }
            if (!o.result().getEnchantments().isEmpty()) lore.add("§b附魔商品");
            lore.add("§e点一下查看/购买");
        }
        return named(icon, "§f" + zh(icon.getType()) + (o.buyBack() ? "" : " x" + o.result().getAmount()), lore);
    }

    private ItemStack detailItem(ShopCatalog.Offer o) {
        ItemStack icon = o.buyBack() ? o.cost().get(0).clone() : o.result().clone();
        List<String> lore = new ArrayList<>();
        if (o.buyBack()) {
            lore.add("§7你交出：" + zh(o.cost().get(0).getType()) + " x" + o.cost().get(0).getAmount());
            int pay = session.effectivePay(o.id(), o.emeraldPrice());
            lore.add("§7他付你：§a" + pay + " 块绿宝石");
            Integer agreedPay = session.agreedPay(o.id());
            if (agreedPay != null) lore.add("§a谈成价：" + agreedPay + " 块（已抬 " + (agreedPay - o.emeraldPrice()) + " 块）");
            lore.add("§7他手头现有：" + state.stockOf(Material.EMERALD) + " 块绿宝石");
        } else {
            int list = o.emeraldPrice();
            Integer agreed = session.agreedPrice(o.id());
            lore.add("§7标价：§a" + list + " 块绿宝石");
            if (agreed != null) lore.add("§a谈成价：" + agreed + " 块（已砍 " + (list - agreed) + " 块）");
            for (ItemStack c : o.cost()) {
                if (c.getType() != Material.EMERALD) {
                    lore.add("§7另需自备：" + zh(c.getType()) + " x" + c.getAmount());
                }
            }
            if (!o.result().getEnchantments().isEmpty()) lore.add("§b附魔商品");
        }
        return named(icon, "§f" + zh(icon.getType()), lore);
    }

    private ItemStack npcCard() {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + state.profession().zhName);
        String line = session.npcLine();
        if (line != null && !line.isBlank()) {
            for (String chunk : wrap(line, 22)) lore.add("§f" + chunk);
        }
        lore.add("§8关上窗口去聊天也行，回来进度还在");
        return button(Material.NAME_TAG, "§6" + npcName(), lore);
    }

    private ItemStack balanceCard() {
        int have = ShopSettlement.countItem(viewer.getInventory(), Material.EMERALD);
        return button(Material.EMERALD, "§a你身上有 " + have + " 块绿宝石",
                List.of("§7买东西就从这里扣"));
    }

    // ===== 点击路由（ShopGuiListener 转发，主线程） =====

    public void handleClick(int slot) {
        switch (view) {
            case LIST -> clickList(slot);
            case DETAIL -> clickDetail(slot);
            case BARGAIN -> clickBargain(slot);
        }
    }

    private void clickList(int slot) {
        if (slot == 53) {
            viewer.closeInventory();
            return;
        }
        if (slot == 45) {
            buyMode = !buyMode;
            page = 0;
            rebuild();
            return;
        }
        if (slot == 48 && page > 0) {
            page--;
            rebuild();
            return;
        }
        if (slot == 50) {
            page++;
            rebuild();
            return;
        }
        String offerId = slotOffers.get(slot);
        if (offerId != null) {
            session.select(offerId);
            view = View.DETAIL;
            rebuild();
        }
    }

    private void clickDetail(int slot) {
        ShopCatalog.Offer o = currentOffer();
        if (o == null) {
            view = View.LIST;
            rebuild();
            return;
        }
        if (slot == 38) {
            session.select(null);
            view = View.LIST;
            rebuild();
            return;
        }
        Integer counter = session.pendingCounter(o.id());
        if (counter != null) {
            if (slot == 40) {          // 接受还价 → 落定（买方向记砍价、卖方向记抬价）
                session.clearPendingCounter(o.id());
                boolean recorded = o.buyBack()
                        ? session.recordRaise(o.id(), counter)
                        : session.recordBargain(o.id(), counter);
                if (recorded) {
                    String line = "好，就 " + counter + " 块！这价给你留着，"
                            + (o.buyBack() ? "卖" : "买") + "的时候就按这个算。";
                    session.setNpcLine(line);
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
                    com.howard.mcai.shop.ShopChatter.speak(plugin, state, viewer, session, line,
                            o.buyBack()
                                    ? "他接受了你出到 " + counter + " 块的还价，这件货的收购价就这么说定了（还没成交）。"
                                    : "他接受了你让到 " + counter + " 块的还价，这件东西的价就这么说定了（还没付钱）。");
                }
                rebuild();
            } else if (slot == 42) {   // 不接受
                session.clearPendingCounter(o.id());
                session.setNpcLine("那……就按原价看看吧，想买随时点。");
                rebuild();
            }
            return;
        }
        if (slot == 40) {              // 买 / 卖
            if (o.buyBack()) {
                ShopSettlement.buyBack(plugin, viewer, state, o, session);
            } else {
                ShopSettlement.buy(plugin, viewer, state, o, session);
            }
            rebuild();
            return;
        }
        if (slot == 42 && !o.buyBack()) {
            int listP = o.emeraldPrice();
            int cur = session.effectivePrice(o.id(), listP);
            int fl = BargainPolicy.floorPrice(listP, state.emotion().favor(viewer.getUniqueId()));
            if (cur > fl) {
                view = View.BARGAIN;
                session.setNpcLine(session.hasBargained(o.id())
                        ? "还想再便宜点？行，你说个数，到底价我就真不能让了。"
                        : "想砍价？行，你自己说砍多少，砍多了我可不干。");
                rebuild();
            }
        }
        if (slot == 42 && o.buyBack()) {
            int listPay = o.emeraldPrice();
            int cur = session.effectivePay(o.id(), listPay);
            int cap = Math.min(BargainPolicy.maxPay(listPay, state.emotion().favor(viewer.getUniqueId())),
                    state.stockOf(Material.EMERALD));
            if (cur < cap) {
                view = View.BARGAIN;
                session.setNpcLine(session.agreedPay(o.id()) != null
                        ? "还想再多要点？行，说个数，俺看兜里还够不够。"
                        : "想抬价？行，你说个数，俺兜里就这么多钱，量力而行。");
                rebuild();
            }
        }
    }

    private void clickBargain(int slot) {
        ShopCatalog.Offer o = currentOffer();
        if (o == null) {
            view = View.LIST;
            rebuild();
            return;
        }
        if (slot == 45) {
            view = View.DETAIL;
            rebuild();
            return;
        }
        int list = o.emeraldPrice();
        if (slot >= 28 && slot <= 32) applyBargain(o, slot - 27);        // 砍 1–5
        else if (slot >= 37 && slot <= 41) applyBargain(o, slot - 31);   // 砍 6–10
        else if (slot == 49) openPriceInput(o, list);
    }

    /**
     * 一次砍价：插件裁决，玩家只管选数（BargainPolicy 守底线）。
     * 多轮砍价：requestedCut 是从**当前价**再砍的数，裁决时换算成「从标价算起总共砍了多少」，
     * 底价始终锚定标价 —— 砍多少轮都砍不穿，额度用完村民就只还价到底价。
     */
    private void applyBargain(ShopCatalog.Offer o, int requestedCut) {
        if (o.buyBack()) {
            applyRaise(o, requestedCut);
            return;
        }
        int list = o.emeraldPrice();
        int current = session.effectivePrice(o.id(), list);
        if (requestedCut >= current) {
            session.setNpcLine("砍 " + requestedCut + " 块？现在总共才 " + current + " 块，你这是想白拿啊。");
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            view = View.BARGAIN;
            rebuild();
            return;
        }
        double favor = state.emotion().favor(viewer.getUniqueId());
        int totalCut = (list - current) + requestedCut;
        BargainPolicy.Outcome out = BargainPolicy.decide(list, favor, totalCut);
        if (out.accepted()) {
            session.recordBargain(o.id(), out.agreedPrice());
            String line = "行！" + out.agreedPrice() + " 块成交，这价给你留着，点买下它就行。";
            session.setNpcLine(line);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
            com.howard.mcai.shop.ShopChatter.speak(plugin, state, viewer, session, line,
                    "他想买你的" + zh(o.result().getType()) + "，标价 " + list + " 块，跟你砍价少付 "
                            + totalCut + " 块，你答应了，谈成 " + out.agreedPrice() + " 块（还没付钱）。");
        } else {
            session.setPendingCounter(o.id(), out.floorPrice());
            String line = "砍得太狠啦！我最多让到 " + out.floorPrice() + " 块，你看行不行？";
            session.setNpcLine(line);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            com.howard.mcai.shop.ShopChatter.speak(plugin, state, viewer, session, line,
                    "他想买你的" + zh(o.result().getType()) + "，砍得太狠，想少付 " + totalCut
                            + " 块，你没全答应，只肯让到 " + out.floorPrice() + " 块，正等他拍板。");
        }
        view = View.DETAIL;
        rebuild();
    }

    /**
     * 一次抬价（卖货方向）：与砍价镜像——按「从标价算起总共抬了多少」裁决，
     * 上限由 BargainPolicy 的交情额度与村民兜里的真钱双重守死（许诺不能超过他真有的钱）。
     */
    private void applyRaise(ShopCatalog.Offer o, int requestedRaise) {
        int listPay = o.emeraldPrice();
        int current = session.effectivePay(o.id(), listPay);
        int funds = state.stockOf(Material.EMERALD);
        if (current >= funds) {
            session.setNpcLine("俺兜里就 " + funds + " 块绿宝石了，真抬不动了。");
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            view = View.BARGAIN;
            rebuild();
            return;
        }
        double favor = state.emotion().favor(viewer.getUniqueId());
        int totalRaise = (current - listPay) + requestedRaise;
        BargainPolicy.SellOutcome out = BargainPolicy.decideSell(listPay, favor, totalRaise);
        int cap = Math.min(out.maxPay(), funds);
        if (out.accepted() && out.agreedPay() <= funds) {
            session.recordRaise(o.id(), out.agreedPay());
            String line = "行！" + out.agreedPay() + " 块收你的，这价给你留着，点卖给他就行。";
            session.setNpcLine(line);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
            com.howard.mcai.shop.ShopChatter.speak(plugin, state, viewer, session, line,
                    "他要把" + zh(o.cost().get(0).getType()) + "卖给你，你原出 " + listPay
                            + " 块，他抬价多要 " + totalRaise + " 块，你答应出 " + out.agreedPay() + " 块收（还没成交）。");
        } else if (cap > current) {
            session.setPendingCounter(o.id(), cap);
            String line = "太多了！俺最多出到 " + cap + " 块，你看行不行？";
            session.setNpcLine(line);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            com.howard.mcai.shop.ShopChatter.speak(plugin, state, viewer, session, line,
                    "他要把" + zh(o.cost().get(0).getType()) + "卖给你，抬得太高，想多要 " + totalRaise
                            + " 块，你没全答应，只肯出到 " + cap + " 块，正等他拍板。");
        } else {
            session.setNpcLine("俺最多就出到 " + current + " 块了，再多真拿不出来。");
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        view = View.DETAIL;
        rebuild();
    }

    /** 铁砧输入框：玩家填「想出的总价」，按当前价换算成砍幅/抬幅走同一套裁决（方向看买卖） */
    private void openPriceInput(ShopCatalog.Offer o, int list) {
        boolean sell = o.buyBack();
        int current = sell ? session.effectivePay(o.id(), list) : session.effectivePrice(o.id(), list);
        viewer.closeInventory();
        new AnvilPriceInput(viewer, current, target -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (target != null && sell && target > current) {
                applyBargain(o, target - current);
            } else if (target != null && !sell && target >= 1 && target < current) {
                applyBargain(o, current - target);
            } else if (target != null) {
                session.setNpcLine(sell
                        ? "你想让他出的比现在给的还低？那就按现在这个价吧。"
                        : (target >= current
                            ? "你出的价比现在谈的还高？那就按现在这个价吧，别多花钱。"
                            : "你这价我实在没法卖，咱们再商量。"));
                view = View.DETAIL;
                rebuild();
            } else {
                view = View.BARGAIN;   // 取消输入 → 回砍价面板
                rebuild();
            }
            viewer.openInventory(inventory);
        })).open();
    }

    // ===== 小工具 =====

    private ShopCatalog.Offer currentOffer() {
        String id = session.selectedOfferId();
        if (id == null) return null;
        for (ShopCatalog.Offer o : ShopCatalog.fromRecipes(state.buildCurrentRecipes())) {
            if (o.id().equals(id)) return o;
        }
        return null;
    }

    private void fillBackground() {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, pane);
    }

    private static int[] buildGridSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int r = 1; r <= 4; r++) {
            for (int c = 1; c <= 7; c++) slots.add(r * 9 + c);
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static ItemStack button(Material mat, String name, List<String> lore) {
        return named(new ItemStack(mat), name, lore);
    }

    private static ItemStack named(ItemStack stack, String name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += width) {
            out.add(text.substring(i, Math.min(text.length(), i + width)));
        }
        return out;
    }

    private static String zh(Material m) {
        return TradeRegistry.materialToZh(m);
    }

    private String npcName() {
        var e = state.entity();
        String cn = (e != null) ? e.getCustomName() : null;
        if (cn != null && !cn.isBlank()) return cn.replaceAll("§.", "");
        return state.profession().zhName;
    }
}
