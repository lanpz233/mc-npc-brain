package com.howard.mcai.api;

import com.howard.mcai.ai.ItemIds;
import com.howard.mcai.ai.ItemZhTable;
import com.howard.mcai.ai.PriceBook;
import com.howard.mcai.ai.ProfessionPools;
import com.howard.mcai.ai.PromptTemplates;
import com.howard.mcai.ai.RecipeLookup;
import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;

import java.util.List;
import java.util.function.Supplier;

/** McaiApi 的实现：全部转调插件现有的定价/配方/货池权威，自身不存状态 */
public final class McaiApiImpl implements McaiApi {

    private final Supplier<RecipeLookup> recipeLookup;

    public McaiApiImpl(Supplier<RecipeLookup> recipeLookup) {
        this.recipeLookup = recipeLookup;
    }

    @Override
    public double fairValue(String itemId) {
        Material m = ItemIds.materialOf(itemId);
        return m == null ? -1 : PriceBook.fairValue(m);
    }

    @Override
    public int sellPrice(String itemId, int count) {
        Material m = ItemIds.materialOf(itemId);
        return m == null ? -1 : PriceBook.sellPrice(m, Math.max(1, count));
    }

    @Override
    public int buybackPrice(String itemId, int count) {
        Material m = ItemIds.materialOf(itemId);
        return m == null ? -1 : PriceBook.buybackPrice(m, Math.max(1, count));
    }

    @Override
    public RecipeView recipeOf(String itemId) {
        RecipeLookup lookup = recipeLookup.get();
        if (lookup == null) return null;
        RecipeLookup.RecipeInfo info = lookup.recipeInfo(itemId);
        if (info == null) return null;
        return new RecipeView(info.itemId(), info.nameZh(), info.materials(), info.yields());
    }

    private static VillagerProfession professionOf(String profession) {
        if (profession == null) return null;
        try {
            return VillagerProfession.valueOf(profession.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean professionCanSell(String profession, String itemId) {
        VillagerProfession p = professionOf(profession);
        Material m = ItemIds.materialOf(itemId);
        return p != null && m != null && ProfessionPools.canSell(p, m);
    }

    @Override
    public boolean professionCanBuy(String profession, String itemId) {
        VillagerProfession p = professionOf(profession);
        Material m = ItemIds.materialOf(itemId);
        return p != null && m != null && ProfessionPools.canBuy(p, m);
    }

    @Override
    public List<String> professionSellItems(String profession) {
        VillagerProfession p = professionOf(profession);
        if (p == null) return List.of();
        return ProfessionPools.sellPool(p).stream()
                .map(item -> ItemIds.idOf(item.item()))
                .toList();
    }

    @Override
    public String itemNameZh(String itemId) {
        Material m = ItemIds.materialOf(itemId);
        return m == null ? null : ItemZhTable.zhOf(m);
    }

    @Override
    public String promptSection(String key) {
        if (key == null || !PromptTemplates.KEYS.contains(key)) return null;
        String v = PromptTemplates.get(key);
        return v == null || v.isEmpty() ? null : v;
    }
}
