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

    /** 伪调用外壳里包着台词：必须取出 content，而不是把台词一起删掉 */
    @Test
    void extractsSpeechFromWrappedCall() {
        String raw = "工具 (\"say\",\ncontent=\"老李，今日这日头真毒，你那炉子火大没？\")";
        assertEquals("老李，今日这日头真毒，你那炉子火大没？", LLMClient.stripToolCalls(raw));
        // 冒号写法的外壳同样兼容
        assertEquals("好嘞", LLMClient.stripToolCalls("工具：say，content=\"好嘞\""));
    }

    /** 没有外壳的 action/content 文本 */
    @Test
    void stripsBareActionKeyValueTail() {
        assertEquals("", LLMClient.stripToolCalls("action: say, content: \"你好\""));
    }

    /** 裸 JSON 里 arguments 排在 name 前面也要能剥 */
    @Test
    void stripsBareJsonWithArgumentsBeforeName() {
        assertEquals("走好", LLMClient.stripToolCalls(
                "走好{\"arguments\":{\"message\":\"走好\"},\"name\":\"say\"}"));
    }

    /** 台词 + JSON 混排：只保留台词 */
    @Test
    void keepsDialogueAroundBareJson() {
        assertEquals("行，我这就去。", LLMClient.stripToolCalls(
                "行，我这就去。{\"name\":\"move_to\",\"arguments\":{\"x\":1,\"y\":2,\"z\":3}}"));
    }

    /** 残余守卫：黑名单没覆盖到的未知格式，整行丢弃（宁可沉默也不显示代码） */
    @Test
    void residualGuardDropsUnknownCodeFormats() {
        // 未闭合的 JSON 尾巴
        assertEquals("", LLMClient.stripToolCalls("你好{\"x\":1"));
        // 中文 + 工具名残留
        assertEquals("", LLMClient.stripToolCalls("你好，工具调用: npc_move_to"));
        // 函数调用尾巴
        assertEquals("", LLMClient.stripToolCalls("好嘞 x=1"));
        // 引号 + 右括号的调用残骸
        assertEquals("", LLMClient.stripToolCalls("好嘞\")"));
    }

    /** 正常中文台词（含全角括号、标点、数字）不能被残余守卫误伤 */
    @Test
    void keepsNormalChineseDialogue() {
        String line = "（挠挠头）夜里怪黑的，你说这村里最近有啥新鲜事没？我今年 32 岁，住在村东头。";
        assertEquals(line, LLMClient.stripToolCalls(line));
    }

    /** 正常台词不能被误伤 */
    @Test
    void keepsPlainDialogue() {
        String plain = "今天天气不错，你吃了没？";
        assertEquals(plain, LLMClient.stripToolCalls(plain));
    }
}
