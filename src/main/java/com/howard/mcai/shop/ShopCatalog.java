package com.howard.mcai.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店商品目录 —— 把 VillagerState.buildCurrentRecipes() 的原版交易配方
 * 翻译成商店界面能直接摆的「商品」：卖货（玩家付绿宝石/原料拿货）与收购（玩家交货拿绿宝石）。
 * <p>
 * 这里只做翻译，不定价、不结算：标价来自配方本身（基础价/动态价/上架价已在配方里），
 * 砍价看 {@link com.howard.mcai.security.BargainPolicy}，成交价记在 ShopSession。
 * 商品 id 在同一次目录构建内稳定（材料名 + 附魔签名 / 收购含数量），会话按 id 记砍价结果。
 */
public final class ShopCatalog {

    private ShopCatalog() {
    }

    /**
     * 一件商品。
     *
     * @param id          会话内稳定 id（砍价结果按它记）
     * @param buyBack     true = 收购（玩家把 cost 交给村民，拿 result 的绿宝石）；
     *                    false = 卖货（玩家付 cost，拿 result）
     * @param result      玩家最终拿到的东西（卖货=商品，收购=绿宝石）
     * @param cost        玩家要付出的东西（卖货通常是绿宝石，可能附带加工原料；收购=要交的货）
     * @param emeraldPrice 绿宝石计价：卖货 = cost 里的绿宝石总数（砍价就砍这个数）；
     *                    收购 = 玩家能拿到的绿宝石数
     */
    public record Offer(String id, boolean buyBack, ItemStack result,
                        List<ItemStack> cost, int emeraldPrice) {
    }

    /** 从配方列表构建目录（配方应已是深拷贝，仍逐项克隆防外部改动串扰） */
    public static List<Offer> fromRecipes(List<MerchantRecipe> recipes) {
        List<Offer> offers = new ArrayList<>();
        for (MerchantRecipe r : recipes) {
            if (r == null || r.getResult() == null) continue;
            ItemStack result = r.getResult().clone();
            List<ItemStack> cost = new ArrayList<>();
            for (ItemStack ing : r.getIngredients()) {
                if (ing != null && ing.getType() != Material.AIR) cost.add(ing.clone());
            }
            if (result.getType() == Material.EMERALD) {
                // 收购：结果是绿宝石，玩家交 cost 换钱
                if (cost.isEmpty()) continue;
                ItemStack first = cost.get(0);
                String id = "BUY#" + first.getType().name() + "x" + first.getAmount();
                offers.add(new Offer(id, true, result, List.copyOf(cost), result.getAmount()));
            } else {
                int emeralds = 0;
                for (ItemStack c : cost) {
                    if (c.getType() == Material.EMERALD) emeralds += c.getAmount();
                }
                offers.add(new Offer(sellId(result), false, result, List.copyOf(cost), emeralds));
            }
        }
        return offers;
    }

    /** 卖货 id：材料名 + 附魔签名（同材料不同附魔是不同商品） */
    static String sellId(ItemStack result) {
        StringBuilder sb = new StringBuilder(result.getType().name());
        if (!result.getEnchantments().isEmpty()) {
            List<String> keys = new ArrayList<>();
            result.getEnchantments().forEach((ench, lvl) ->
                    keys.add(ench.getKey().getKey() + lvl));
            keys.sort(String::compareTo);
            sb.append('#').append(String.join(",", keys));
        }
        return sb.toString();
    }
}
