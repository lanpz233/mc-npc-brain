package com.howard.mcai.webpanel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelDataFilesTest {

    @Test
    void validRecipesPassAndGetFormatted() {
        String out = PanelDataFiles.validateAndFormat("recipes.json",
                "{\"recipes\":{\"minecraft:bread\":{\"materials\":{\"minecraft:wheat\":3},\"name_zh\":\"面包\"}}}");
        assertTrue(out.contains("\"minecraft:bread\""));
        assertTrue(out.contains("\n"));  // 已格式化成多行
    }

    @Test
    void recipeWithBadMaterialCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("recipes.json",
                        "{\"recipes\":{\"minecraft:bread\":{\"materials\":{\"minecraft:wheat\":0}}}}"));
    }

    @Test
    void brokenJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("recipes.json", "{\"recipes\":"));
    }

    @Test
    void tagWithoutPromptIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("personality_tags.json",
                        "{\"tags\":{\"kind\":{\"category\":\"core\",\"name_zh\":\"善良\"}}}"));
    }

    @Test
    void surnamesAndGivenNamesValidation() {
        String ok = PanelDataFiles.validateAndFormat("surnames.json",
                "{\"surnames\":[{\"name\":\"王\",\"weight\":10}]}");
        assertTrue(ok.contains("王"));
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("surnames.json", "{\"surnames\":[]}"));
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("given_names.json",
                        "{\"female\":[\"花\"],\"male\":[]}"));
    }

    @Test
    void pricesValidation() {
        String ok = PanelDataFiles.validateAndFormat("prices.json",
                "{\"anchors\":{\"minecraft:iron_ingot\":0.25}}");
        assertTrue(ok.contains("iron_ingot"));
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("prices.json",
                        "{\"anchors\":{\"minecraft:iron_ingot\":-1}}"));
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("prices.json", "{\"anchors\":{}}"));
    }

    @Test
    void poolsValidation() {
        String ok = PanelDataFiles.validateAndFormat("profession-pools.json",
                "{\"professions\":{\"FARMER\":{\"sell\":[[\"minecraft:bread\",6]]}},\"guest\":[]}");
        assertTrue(ok.contains("FARMER"));
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("profession-pools.json",
                        "{\"professions\":{\"WIZARD\":{\"sell\":[]}}}"));
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("profession-pools.json",
                        "{\"professions\":{\"FARMER\":{\"sell\":[[\"minecraft:bread\",0]]}}}"));
    }

    @Test
    void promptsValidation() {
        String full = "{\"sections\":{"
                + "\"identity\":\"You are {name}, a {profession} villager. {personality} {speech}\","
                + "\"world_facts\":\"w\",\"behavior_rules\":\"b\",\"shop_rules\":\"s\",\"knowledge\":\"k\","
                + "\"state_rules\":\"st\",\"needs_rules\":\"n\",\"perm_envoy\":\"e\",\"perm_confidant\":\"c\","
                + "\"perm_civilian\":\"v\",\"backpack_rules\":\"bp\"}}";
        assertTrue(PanelDataFiles.validateAndFormat("prompts.json", full).contains("identity"));
        // 删掉填空位必须被拦
        String noPlaceholder = full.replace("{name}", "老王");
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("prompts.json", noPlaceholder));
        // 缺段必须被拦
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("prompts.json", "{\"sections\":{\"identity\":\"x\"}}"));
    }

    @Test
    void unknownFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("../../server.properties", "{}"));
    }
}
