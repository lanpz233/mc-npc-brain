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
    void unknownFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PanelDataFiles.validateAndFormat("../../server.properties", "{}"));
    }
}
