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
            int reward = o.result().getAmount();
            inventory.setItem(40, button(Material.GOLD_BLOCK,
                    "§a卖给他 · 得 " + reward + " 块绿宝石",
                    List.of("§7他手头现有 " + state.stockOf(Material.EMERALD) + " 块绿宝石")));
        } else {
            int price = session.effectivePrice(o.id(), o.emeraldPrice());
            inventory.setItem(40, button(Material.EMERALD_BLOCK,
                    "§a买下它 · " + price + " 块绿宝石",
                    session.hasBargained(o.id())
                            ? List.of("§7这是你们谈好的价")
                            : List.of("§7点一下直接买（也可以先砍价）")));
            if (session.hasBargained(o.id())) {
                inventory.setItem(42, button(Material.GRAY_STAINED_GLASS_PANE,
                        "§7价已经谈好啦", List.of("§7一次会话里一个商品只砍一次")));
            } else {
                inventory.setItem(42, button(Material.ANVIL,
                        "§e砍价 · 自己选砍多少", List.of("§7能砍多少看你们的交情")));
            }
        }
        inventory.setItem(47, balanceCard());
    }

    private void renderBargain(ShopCatalog.Offer o) {
        if (o == null || o.buyBack()) {
            view = View.LIST;
            rebuild();
            return;
        }
        int list = o.emeraldPrice();
        inventory.setItem(22, detailItem(o));
        inventory.setItem(29, cutButton(1, list));
        inventory.setItem(31, cutButton(5, list));
        inventory.setItem(33, cutButton(10, list));
        inventory.setItem(40, button(Material.ANVIL,
                "§e自己出价 · 输入总价", List.of("§7打开小输入框，填你想出的总价（整数）")));
        inventory.setItem(45, button(Material.ARROW, "§f回商品页", List.of()));
    }

    private ItemStack cutButton(int cut, int listPrice) {
        if (cut >= listPrice) {
            return button(Material.GRAY_STAINED_GLASS_PANE, "§7砍 " + cut + " 块",
                    List.of("§7这件东西总共才 " + listPrice + " 块，砍不了这么多"));
        }
        return button(Material.GOLD_INGOT, "§e砍 " + cut + " 块",
                List.of("§7想按 " + (listPrice - cut) + " 块买，点一下试试"));
    }

    // ===== 展示小件 =====

    private ItemStack listItem(ShopCatalog.Offer o) {
        ItemStack icon;
        List<String> lore = new ArrayList<>();
        if (o.buyBack()) {
            icon = o.cost().get(0).clone();
            lore.add("§7收购：你交出 §f" + zh(o.cost().get(0).getType()) + " x" + o.cost().get(0).getAmount());
            lore.add("§7他付你 §a" + o.result().getAmount() + " 块绿宝石");
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
            lore.add("§7他付你：§a" + o.result().getAmount() + " 块绿宝石");
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
            if (slot == 40) {          // 接受还价 → 落定
                session.clearPendingCounter(o.id());
                if (session.recordBargain(o.id(), counter)) {
                    session.setNpcLine("好，就 " + counter + " 块！这价给你留着，买的时候就按这个算。");
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
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
        if (slot == 42 && !o.buyBack() && !session.hasBargained(o.id())) {
            view = View.BARGAIN;
            session.setNpcLine("想砍价？行，你自己说砍多少，砍多了我可不干。");
            rebuild();
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
        if (slot == 29) applyBargain(o, 1);
        else if (slot == 31) applyBargain(o, 5);
        else if (slot == 33) applyBargain(o, 10);
        else if (slot == 40) openPriceInput(o, list);
    }

    /** 一次砍价：插件裁决，玩家只管选数（BargainPolicy 守底线） */
    private void applyBargain(ShopCatalog.Offer o, int requestedCut) {
        int list = o.emeraldPrice();
        if (requestedCut >= list) {
            session.setNpcLine("砍 " + requestedCut + " 块？这东西总共才 " + list + " 块，你这是想白拿啊。");
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            view = View.BARGAIN;
            rebuild();
            return;
        }
        double favor = state.emotion().favor(viewer.getUniqueId());
        BargainPolicy.Outcome out = BargainPolicy.decide(list, favor, requestedCut);
        if (out.accepted()) {
            session.recordBargain(o.id(), out.agreedPrice());
            session.setNpcLine("行！" + out.agreedPrice() + " 块成交，这价给你留着，点买下它就行。");
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        } else {
            session.setPendingCounter(o.id(), out.floorPrice());
            session.setNpcLine("砍得太狠啦！我最多让到 " + out.floorPrice() + " 块，你看行不行？");
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        view = View.DETAIL;
        rebuild();
    }

    /** 铁砧输入框：玩家填「想出的总价」，换算成砍幅走同一套裁决 */
    private void openPriceInput(ShopCatalog.Offer o, int list) {
        viewer.closeInventory();
        new AnvilPriceInput(viewer, list, target -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (target != null && target >= 1 && target < list) {
                applyBargain(o, list - target);
            } else if (target != null) {
                session.setNpcLine(target >= list
                        ? "你出的价比我标的还高？那就按原价吧，别多花钱。"
                        : "你这价我实在没法卖，咱们再商量。");
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
