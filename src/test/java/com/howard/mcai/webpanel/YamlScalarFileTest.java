package com.howard.mcai.webpanel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlScalarFileTest {

    private static final String SAMPLE = """
            # 顶部注释
            llm:
              url: 'http://127.0.0.1:11434/v1/chat/completions'
              read-timeout-ms: 30000      # 云端建议 60000
              model: 'Qwen:latest'
            needs:
              threshold: 80                 # 触发阈值
            villagers:
              blacksmith:
                name: '老王'
                spawn-x: 100
            """;

    @Test
    void readsNestedValues() {
        Map<String, String> values = YamlScalarFile.readValues(SAMPLE,
                List.of("llm.read-timeout-ms", "needs.threshold", "villagers.blacksmith.name"));
        assertEquals("30000", values.get("llm.read-timeout-ms"));
        assertEquals("80", values.get("needs.threshold"));
        assertEquals("老王", values.get("villagers.blacksmith.name"));
    }

    @Test
    void updatePreservesCommentsAndQuoting() {
        String out = YamlScalarFile.applyValues(SAMPLE, Map.of("llm.read-timeout-ms", 60000));
        assertTrue(out.contains("read-timeout-ms: 60000 # 云端建议 60000"), out);
        assertTrue(out.contains("# 顶部注释"));
        String out2 = YamlScalarFile.applyValues(SAMPLE, Map.of("villagers.blacksmith.name", "老李"));
        assertTrue(out2.contains("name: '老李'"), out2);
    }

    @Test
    void updateDoubleAndBoolean() {
        String out = YamlScalarFile.applyValues(SAMPLE,
                Map.of("needs.threshold", 65.5, "villagers.blacksmith.spawn-x", 160.0));
        assertTrue(out.contains("threshold: 65.5 # 触发阈值"), out);
        assertTrue(out.contains("spawn-x: 160"), out);
    }

    @Test
    void missingLeafIsInsertedIntoItsSection() {
        String out = YamlScalarFile.applyValues(SAMPLE, Map.of("needs.autonomy-cooldown-seconds", 90));
        assertTrue(out.contains("  autonomy-cooldown-seconds: 90"), out);
        // 插进去之后还能读回来，且位置在 needs 段内（threshold 之后、villagers 之前）
        Map<String, String> back = YamlScalarFile.readValues(out,
                List.of("needs.autonomy-cooldown-seconds", "needs.threshold"));
        assertEquals("90", back.get("needs.autonomy-cooldown-seconds"));
        assertTrue(out.indexOf("autonomy-cooldown-seconds") < out.indexOf("villagers:"));
    }

    @Test
    void stringNeedingQuotesGetsQuoted() {
        String out = YamlScalarFile.applyValues(SAMPLE, Map.of("llm.model", "my model: v2"));
        Map<String, String> back = YamlScalarFile.readValues(out, List.of("llm.model"));
        assertEquals("my model: v2", back.get("llm.model"));
    }
}
