package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词第 2 档优化（2026-10-26）的模板侧钉子：
 * 好感数字门槛撤出提示词、降级规则在位、占位符不丢。
 * 直接读 src/main/resources/prompts.json（与 ExternalDataTest 同口径）。
 */
class PromptsV2Test {

    private static JsonObject sections() throws Exception {
        String text = Files.readString(Path.of("src/main/resources/prompts.json"));
        return JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("sections");
    }

    private static String section(JsonObject sections, String key) {
        return sections.get(key).getAsString();
    }

    @Test
    void noFavorThresholdNumbersAnywhere() throws Exception {
        String text = Files.readString(Path.of("src/main/resources/prompts.json"));
        assertFalse(text.contains("≥10"), "提示词里不许再出现普通物品好感门槛数字");
        assertFalse(text.contains("≥60"), "提示词里不许再出现稀有物品好感门槛数字");
        assertFalse(text.contains("好感≥"), "提示词里不许再出现好感门槛表述");
    }

    @Test
    void backpackRulesAreVerdictBased() throws Exception {
        String backpack = section(sections(), "backpack_rules");
        // 结论制：照场景说明里的判定结论办，台词不许报数字
        assertTrue(backpack.contains("场景说明"), backpack);
        assertTrue(backpack.contains("照结论办"), backpack);
        assertTrue(backpack.contains("不许报交情数字"), backpack);
        // 段内不许残留任何数字门槛（含阿拉伯数字本身）
        assertFalse(backpack.chars().anyMatch(Character::isDigit), backpack);
        // 硬规矩保留：只能送真实库存、必须调 give_item
        assertTrue(backpack.contains("give_item"), backpack);
        assertTrue(backpack.contains("真实拥有的"), backpack);
    }

    @Test
    void shopRulesAlignedWithVerdict() throws Exception {
        String shop = section(sections(), "shop_rules");
        assertFalse(shop.contains("门槛"), shop);
        assertTrue(shop.contains("赠送判定"), shop);
    }

    @Test
    void behaviorRulesHaveDegradationRule() throws Exception {
        String behavior = section(sections(), "behavior_rules");
        // 降级规则：没听懂／场景冲突 → 只用 say 说一句困惑话，严禁编造
        assertTrue(behavior.contains("没听懂"), behavior);
        assertTrue(behavior.contains("困惑话"), behavior);
        assertTrue(behavior.contains("严禁编造"), behavior);
        assertTrue(behavior.contains("say"), behavior);
    }

    @Test
    void identityPlaceholdersIntact() throws Exception {
        String identity = section(sections(), "identity");
        assertTrue(identity.startsWith("你是{name}"), identity);
        for (String ph : new String[]{"{name}", "{profession}", "{personality}", "{speech}"}) {
            assertTrue(identity.contains(ph), "占位符丢失: " + ph);
        }
    }
}
