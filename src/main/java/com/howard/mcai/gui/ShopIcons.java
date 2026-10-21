package com.howard.mcai.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 商店功能按钮的专属图标（素材阶段 1，治「用物品凑功能、难辨别」）。
 * <p>
 * 旧版功能全靠现成物品凑：一排金锭当砍价按钮、铁砧当入口、箭头当返回，不悬停看名字
 * 分不清谁是谁。现在按钮本体统一用纸，靠 CustomModelData 挂资源包里画好的图标：
 * 金币印「-N」=砍价、绿币印「+N」=抬价、汉字牌=砍/抬/买/卖/价/货/收，另有勾/叉/
 * 箭头/锁/宝石。编号必须与 resourcepack/make_icons.py 里写死的一致。
 * 没装资源包的玩家看到的是带名字的纸，名字说明照旧，功能不受影响。
 * 商品本身（真实物品）不走这里，仍由 listItem/detailItem 原样展示。
 */
public final class ShopIcons {

    public static final int TAG_CUT = 1021;
    public static final int TAG_RAISE = 1022;
    public static final int BUY = 1023;
    public static final int SELL = 1024;
    public static final int ACCEPT = 1025;
    public static final int REJECT = 1026;
    public static final int BACK = 1027;
    public static final int CLOSE = 1028;
    public static final int PRICE = 1029;
    public static final int LOCK = 1030;
    public static final int BALANCE = 1031;
    public static final int PREV = 1032;
    public static final int NEXT = 1033;
    public static final int MODE_BUY = 1034;
    public static final int MODE_SELL = 1035;

    private ShopIcons() {
    }

    /** 砍价数字币编号：1001–1010（金币印 -N） */
    public static int cut(int n) {
        return 1000 + n;
    }

    /** 抬价数字币编号：1011–1020（绿币印 +N） */
    public static int raise(int n) {
        return 1010 + n;
    }

    /** 造一个带专属图标的功能按钮（名字与说明照旧，玩家主要靠图认功能） */
    public static ItemStack button(int iconCmd, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            meta.setCustomModelData(iconCmd);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
