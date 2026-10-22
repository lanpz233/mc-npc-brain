package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：v0.4.3 实测事故 —— 村民口头答应换物（"行，换！"），把成交动作
 * 写成正文里的一行 {@code [accept_barter=message="好吧，这就换了！"]}，
 * 原生 tool_calls 只有一个空 say。旧解析三处漏：
 * ① 文本捞取只在原生调用为空时触发，有 say 就不看正文；
 * ② 方括号解析只认 [动作:…] 前缀，无前缀变体不认；
 * ③ 没人认的调用文本被残余代码守卫连台词整段丢弃。
 * 结果：嘴上说换、实际没换成，玩家侧还看不到任何报错。
 */
class LlmClientTextCallTest {

    /** 事故当晚的模型正文（台词 + 无前缀方括号调用） */
    private static final String INCIDENT_CONTENT =
            "（撇撇嘴，瞅了瞅你那几颗种子，又看了看自己手里随便捡的木棍）\n"
                    + "嘿，这把木棍不值几个钱，你的种子可是好东西，算你懂行！行，换！\n"
                    + "[accept_barter=message=\"好吧，这就换了！\"]";

    @Test
    void bareBracketAcceptBarterParses() {
        List<ToolCall> calls = LLMClient.parseBracketActions(INCIDENT_CONTENT);
        assertEquals(1, calls.size());
        assertEquals("accept_barter", calls.get(0).name());
        // 引号内的逗号不能把值切断：message 必须是完整一句
        assertEquals("好吧，这就换了！",
                calls.get(0).arguments().get("message").getAsString());
    }

    @Test
    void prefixedBracketActionStillParses() {
        List<ToolCall> calls = LLMClient.parseBracketActions("[动作:give_item 面包]");
        assertEquals(1, calls.size());
        assertEquals("give_item", calls.get(0).name());
        assertEquals("面包", calls.get(0).arguments().get("item").getAsString());
    }

    @Test
    void bareBracketWithBareValueFallsBack() {
        List<ToolCall> calls = LLMClient.parseBracketActions("[give_item 面包]");
        assertEquals(1, calls.size());
        assertEquals("give_item", calls.get(0).name());
        assertEquals("面包", calls.get(0).arguments().get("item").getAsString());
    }

    @Test
    void stripRemovesBareBracketButKeepsSpeech() {
        String cleaned = LLMClient.stripToolCalls(INCIDENT_CONTENT);
        assertFalse(cleaned.contains("accept_barter"), "调用文本不许漏进台词: " + cleaned);
        assertTrue(cleaned.contains("撇撇嘴"), "正常台词第一行要留住: " + cleaned);
        assertTrue(cleaned.contains("行，换！"), "正常台词第二行要留住: " + cleaned);
    }

    @Test
    void mergePromotesTextActionAboveNativeSay() {
        ToolCall nativeSay = new ToolCall("say", new JsonObject());
        JsonObject args = new JsonObject();
        args.addProperty("message", "好吧，这就换了！");
        ToolCall textBarter = new ToolCall("accept_barter", args);

        LLMClient.MergedCalls merged = LLMClient.mergeTextCalls(
                List.of(nativeSay), List.of(textBarter), INCIDENT_CONTENT);

        assertEquals("accept_barter", merged.calls().get(0).name(),
                "文本动作必须排到最前 —— 执行端只认 firstToolCall");
        assertSame(textBarter, merged.promoted());
        assertEquals(2, merged.calls().size(), "原生 say 保留在后面，不丢");
    }

    @Test
    void mergeKeepsNativesWhenTextHasNoAction() {
        ToolCall nativeSay = new ToolCall("say", new JsonObject());
        ToolCall textSay = new ToolCall("say", new JsonObject());

        LLMClient.MergedCalls merged = LLMClient.mergeTextCalls(
                List.of(nativeSay), List.of(textSay), "随便聊聊");

        assertEquals(1, merged.calls().size());
        assertSame(nativeSay, merged.calls().get(0));
        assertNull(merged.promoted());
    }

    @Test
    void mergeFoldsNativeSayMessageIntoContent() {
        JsonObject sayArgs = new JsonObject();
        sayArgs.addProperty("message", "你好呀");
        ToolCall nativeSay = new ToolCall("say", sayArgs);
        ToolCall textMove = new ToolCall("move_to", new JsonObject());

        LLMClient.MergedCalls merged = LLMClient.mergeTextCalls(
                List.of(nativeSay), List.of(textMove), "[move_to(x=1, y=2, z=3)]");

        assertSame(textMove, merged.promoted());
        assertTrue(merged.content().startsWith("你好呀"),
                "原生 say 的台词要并进正文，免得提升后丢失: " + merged.content());
    }
}
