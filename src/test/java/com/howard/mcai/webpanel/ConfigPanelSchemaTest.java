package com.howard.mcai.webpanel;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPanelSchemaTest {

    private static JsonObject values(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validValuesAreNormalized() {
        Map<String, Object> out = ConfigPanelSchema.validateAll(values(
                "{\"llm.read-timeout-ms\": 45000, \"bubble.text-scale\": 0.7,"
                        + " \"social.ambient-chat-enabled\": false, \"llm.model\": \"auto\"}"));
        assertEquals(45000, out.get("llm.read-timeout-ms"));
        assertEquals(0.7, out.get("bubble.text-scale"));
        assertEquals(false, out.get("social.ambient-chat-enabled"));
        assertEquals("auto", out.get("llm.model"));
    }

    @Test
    void outOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigPanelSchema.validateAll(values("{\"shop.resource-pack-port\": 99999}")));
    }

    @Test
    void nonIntegerForIntFieldIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigPanelSchema.validateAll(values("{\"llm.max-tokens\": 10.5}")));
    }

    @Test
    void unknownPathIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigPanelSchema.validateAll(values("{\"hacker.path\": 1}")));
    }

    @Test
    void blankModelAndBadUrlAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigPanelSchema.validateAll(values("{\"llm.model\": \"  \"}")));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigPanelSchema.validateAll(values("{\"llm.url\": \"not a url\"}")));
    }

    @Test
    void blankApiKeyIsAllowed() {
        Map<String, Object> out = ConfigPanelSchema.validateAll(values("{\"llm.api-key\": \"\"}"));
        assertEquals("", out.get("llm.api-key"));
    }

    @Test
    void schemaCoversPanelItselfAndDefaultsExist() {
        assertTrue(ConfigPanelSchema.BY_PATH.containsKey("web-panel.port"));
        assertEquals(ConfigPanelSchema.FIELDS.size(), ConfigPanelSchema.defaults().size());
    }
}
