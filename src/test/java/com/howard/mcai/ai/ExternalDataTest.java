package com.howard.mcai.ai;

import com.howard.mcai.api.McaiApi;
import com.howard.mcai.api.McaiApiImpl;
import com.howard.mcai.mob.VillagerProfession;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.2.0 外置数据：定价表 / 职业货池 / 提示词 / 全量配方按需查 / 对外接口 */
class ExternalDataTest {

    @TempDir
    Path dataFolder;

    @Test
    void pricesFileOverridesAnchors() throws Exception {
        double before = PriceBook.fairValue(Material.IRON_INGOT);
        Files.writeString(dataFolder.resolve("prices.json"),
                "{\"anchors\":{\"minecraft:iron_ingot\": 2.0}}");
        int n = PriceBook.load(dataFolder);
        assertEquals(1, n);
        assertEquals(2.0, PriceBook.fairValue(Material.IRON_INGOT), 0.001);
        // 恢复内置默认，避免污染其他用例（重新 load 一个只有 emerald 的文件不如直接 load 空目录拷贝内置版）
        Path empty = Files.createTempDirectory("pb-empty");
        PriceBook.load(empty);  // 空目录会拷入内置版 80 条
        assertEquals(before, PriceBook.fairValue(Material.IRON_INGOT), 0.001);
    }

    @Test
    void brokenPricesFileKeepsDefaults() throws Exception {
        Files.writeString(dataFolder.resolve("prices.json"), "{坏的");
        assertEquals(-1, PriceBook.load(dataFolder));
        assertTrue(PriceBook.fairValue(Material.WHEAT) > 0);
    }

    @Test
    void poolsFileOverridesProfession() throws Exception {
        Files.writeString(dataFolder.resolve("profession-pools.json"),
                "{\"professions\":{\"FARMER\":{\"sell\":[[\"minecraft:diamond_sword\",1]],\"buy\":[]}},\"guest\":[]}");
        int n = ProfessionPools.load(dataFolder);
        assertTrue(n >= 0);
        assertTrue(ProfessionPools.canSell(VillagerProfession.FARMER, Material.DIAMOND_SWORD));
        assertFalse(ProfessionPools.canSell(VillagerProfession.FARMER, Material.BREAD));
        // 没写到的职业回退内置默认
        assertTrue(ProfessionPools.canSell(VillagerProfession.TOOLSMITH, Material.IRON_PICKAXE));
        // 恢复默认池
        Path empty = Files.createTempDirectory("pp-empty");
        ProfessionPools.load(empty);
        assertTrue(ProfessionPools.canSell(VillagerProfession.FARMER, Material.BREAD));
    }

    @Test
    void promptTemplatesBundledAndOverride() throws Exception {
        String identity = PromptTemplates.get("identity");
        assertTrue(identity.contains("{name}"));
        assertTrue(identity.contains("{profession}"));
        // 用数据文件夹的改版覆盖一段
        String bundled = Files.readString(Path.of("src/main/resources/prompts.json"));
        String modified = bundled.replace("你是{name}", "你是{name}师傅");
        Files.writeString(dataFolder.resolve("prompts.json"), modified);
        assertEquals(11, PromptTemplates.load(dataFolder));
        assertTrue(PromptTemplates.get("identity").contains("师傅"));
        // 恢复内置（空目录自动拷内置版）
        Path empty = Files.createTempDirectory("pt-empty");
        PromptTemplates.load(empty);
        assertTrue(PromptTemplates.get("identity").startsWith("你是{name}"));
    }

    @Test
    void missingPromptSectionIsRejected() throws Exception {
        Files.writeString(dataFolder.resolve("prompts.json"), "{\"sections\":{\"identity\":\"x\"}}");
        assertEquals(-1, PromptTemplates.load(dataFolder));
    }

    @Test
    void fullRecipeTableLoadsAndRelevantLookupWorks() {
        RecipeLookup lookup = new RecipeLookup();
        lookup.load(dataFolder);
        assertTrue(lookup.isLoaded());
        assertTrue(lookup.size() >= 780, "全量表应有 780+ 条，实际 " + lookup.size());
        String relevant = lookup.relevantFor("师傅，铁镐怎么做？", 6);
        assertTrue(relevant.contains("铁镐"), relevant);
        assertTrue(relevant.contains("铁锭"), relevant);
        // 大表不再整表塞 prompt：摘要应是短说明
        String summary = lookup.buildPromptSummary();
        assertTrue(summary.contains("配方表共"), summary);
        assertTrue(summary.length() < 500);
        // 结构化配方
        var info = lookup.recipeInfo("minecraft:iron_pickaxe");
        assertNotNull(info);
        assertEquals(Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), info.materials());
    }

    @Test
    void oldSmallRecipeTableIsMigratedWithBackup() throws Exception {
        Files.writeString(dataFolder.resolve("recipes.json"),
                "{\"_meta\":{\"description\":\"old\"},\"recipes\":{\"minecraft:bread\":"
                        + "{\"materials\":{\"minecraft:wheat\":3},\"name_zh\":\"面包\"}}}");
        RecipeLookup lookup = new RecipeLookup();
        lookup.load(dataFolder);
        assertTrue(lookup.size() >= 780, "旧小表应被迁移为全量表，实际 " + lookup.size());
        assertTrue(Files.exists(dataFolder.resolve("recipes.json.old.bak")), "旧表应先备份");
        // 再 load 一次：已是全量表（带 count 标记），不能重复迁移
        RecipeLookup again = new RecipeLookup();
        again.load(dataFolder);
        assertEquals(lookup.size(), again.size());
    }

    @Test
    void apiSurfaceWorks() {
        RecipeLookup lookup = new RecipeLookup();
        lookup.load(dataFolder);
        McaiApi api = new McaiApiImpl(() -> lookup);
        assertTrue(api.fairValue("minecraft:iron_ingot") > 0);
        assertEquals(-1, api.fairValue("minecraft:not_a_thing"));
        assertTrue(api.sellPrice("minecraft:bread", 1) >= 1);
        assertTrue(api.professionCanSell("FARMER", "minecraft:bread"));
        assertFalse(api.professionCanSell("FARMER", "minecraft:netherite_sword"));
        assertEquals("铁锭", api.itemNameZh("minecraft:iron_ingot"));
        assertNull(api.itemNameZh("minecraft:not_a_thing"));
        var recipe = api.recipeOf("minecraft:enchanting_table");
        assertNotNull(recipe);
        assertEquals(4, recipe.materials().get("minecraft:obsidian"));
        assertNotNull(api.promptSection("identity"));
        assertNull(api.promptSection("nope"));
    }
}
