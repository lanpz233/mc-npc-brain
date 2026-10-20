package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 候选模式序号解析的回归测试。
 * <p>
 * 1B 模型会按心情输出 JSON、裸数字、带 think 块或代码围栏的内容，
 * 这里覆盖实际见过的几种形态，避免解析规则被后续改动破坏。
 */
class BehaviorModelClientChoiceTest {

    @Test
    void parsesStandardChoiceObject() {
        assertEquals(2, BehaviorModelClient.parseChoiceIndex(
                "{\"choice\": 2, \"reason\": \"walk to the field\"}"));
    }

    @Test
    void parsesQuotedChoice() {
        assertEquals(1, BehaviorModelClient.parseChoiceIndex("{\"choice\": \"1\"}"));
    }

    @Test
    void parsesLooseChoiceWithoutJson() {
        assertEquals(3, BehaviorModelClient.parseChoiceIndex("choice: 3"));
    }

    @Test
    void parsesBareSingleNumber() {
        assertEquals(1, BehaviorModelClient.parseChoiceIndex("I choose 1"));
    }

    @Test
    void rejectsAmbiguousMultipleNumbers() {
        // 没有 choice 标记且出现多个数字：宁可判失败让上层回退，也不要瞎猜一个
        assertEquals(-1, BehaviorModelClient.parseChoiceIndex("maybe 1 or 2"));
    }

    @Test
    void stripsThinkBlockAndCodeFence() {
        assertEquals(0, BehaviorModelClient.parseChoiceIndex(
                "<think>let me think</think>\n```json\n{\"choice\": 0}\n```"));
    }

    @Test
    void rejectsNegativeChoice() {
        assertEquals(-1, BehaviorModelClient.parseChoiceIndex("{\"choice\": -1}"));
    }
}
