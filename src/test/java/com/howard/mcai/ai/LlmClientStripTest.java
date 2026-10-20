package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归测试：模型把"工具调用格式"写进台词正文时，必须被剥干净。
 * <p>
 * 实测泄漏样本（玩家气泡里直接看到代码）：
 * {@code （挠挠头）夜里怪黑的，你说这村里最近有啥新鲜事没？\n**action**: "say", *content*}
 * <p>
 * 这些格式都不是台词，只要漏进气泡/聊天框玩家就会看到"代码"。
 */
class LlmClientStripTest {

    /** Markdown / YAML 风格的伪工具调用 —— Qwen 系列最爱这么写 */
    @Test
    void stripsMarkdownActionTail() {
        String raw = "（挠挠头）夜里怪黑的，你说这村里最近有啥新鲜事没？\n**action**: \"say\", *content*";
        assertEquals("（挠挠头）夜里怪黑的，你说这村里最近有啥新鲜事没？",
                LLMClient.stripToolCalls(raw));
    }

    /** 自造 XML 动作标签 */
    @Test
    void stripsActionXml() {
        assertEquals("行吧", LLMClient.stripToolCalls("行吧<action>say</action>"));
    }

    /** Hermes 风格的 tool_call XML */
    @Test
    void stripsToolCallXml() {
        assertEquals("好嘞", LLMClient.stripToolCalls("好嘞<tool_call>{\"name\":\"say\"}</tool_call>"));
    }

    /** 裸 JSON 调用（嵌套 arguments） */
    @Test
    void stripsBareJsonToolCall() {
        assertEquals("你好", LLMClient.stripToolCalls(
                "你好{\"name\":\"say\",\"arguments\":{\"message\":\"你好\"}}"));
    }

    /** Python 风格函数调用 */
    @Test
    void stripsPythonStyleCall() {
        assertEquals("嗯嗯", LLMClient.stripToolCalls("嗯嗯.say(message=\"嗯嗯\")"));
    }

    /** 代码围栏（含未闭合）：围栏里的 JSON/调用都该消失 */
    @Test
    void stripsCodeFence() {
        assertEquals("好嘞", LLMClient.stripToolCalls("好嘞\n```json\n{\"action\":\"say\"}\n```"));
        assertEquals("好嘞", LLMClient.stripToolCalls("好嘞\n```json\n{\"action\":\"say\""));
    }

    /** 正常台词不能被误伤 */
    @Test
    void keepsPlainDialogue() {
        String plain = "今天天气不错，你吃了没？";
        assertEquals(plain, LLMClient.stripToolCalls(plain));
    }
}
