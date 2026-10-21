package com.howard.mcai.api;

import java.util.List;
import java.util.Map;

/**
 * MC-AI 对外查询接口 —— 给其他插件接入用（2026-10-22，服主定制化配套）。
 *
 * <p>其他插件通过 Bukkit 的 ServicesManager 拿到本接口，即可查询 MC-AI 的
 * 定价、配方、职业货池、物品中文名与提示词段落，查到的都是服主在配置面板里
 * 定制后的当前生效值（改价、改货池后立即一致）。全部只读，不改变任何状态。</p>
 *
 * <pre>
 * McaiApi api = Bukkit.getServicesManager().load(McaiApi.class);
 * if (api != null) {
 *     int price = api.sellPrice("minecraft:iron_pickaxe", 1);
 * }
 * </pre>
 *
 * <p>物品一律用 "minecraft:iron_ingot" 形式的 ID；职业用英文枚举名
 * （FARMER / TOOLSMITH / …，与 VillagerProfession 一致）。认不出的 ID/职业：
 * 价格类返回 -1，布尔类返回 false，对象类返回 null。</p>
 *
 * <p>不想写代码的插件也可以直接读数据文件：plugins/MC-AI/ 下的 prices.json、
 * profession-pools.json、recipes.json、prompts.json 都是标准 JSON，格式见各文件
 * _meta 说明。</p>
 */
public interface McaiApi {

    /** 某物品的公道价（绿宝石/个）；认不出的物品返回 -1 */
    double fairValue(String itemId);

    /** 商店卖价（一堆 count 个，绿宝石）；认不出的物品返回 -1 */
    int sellPrice(String itemId, int count);

    /** 商店收购价（一堆 count 个，绿宝石）；认不出的物品返回 -1 */
    int buybackPrice(String itemId, int count);

    /** 结构化配方视图 */
    record RecipeView(String itemId, String nameZh, Map<String, Integer> materials, int yields) {
    }

    /** 查某物品的合成配方（材料 ID → 个数）；没有配方返回 null */
    RecipeView recipeOf(String itemId);

    /** 某职业的村民能不能卖这件物品（按当前货池） */
    boolean professionCanSell(String profession, String itemId);

    /** 某职业的村民收不收这件物品（按当前货池） */
    boolean professionCanBuy(String profession, String itemId);

    /** 某职业的卖出清单（物品 ID 列表）；认不出的职业返回空列表 */
    List<String> professionSellItems(String profession);

    /** 物品的官方中文名；认不出返回 null */
    String itemNameZh(String itemId);

    /** 提示词的某一段当前文本（段名见 prompts.json）；没有返回 null */
    String promptSection(String key);
}
