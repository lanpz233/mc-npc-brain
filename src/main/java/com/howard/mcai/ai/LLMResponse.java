package com.howard.mcai.ai;

import java.util.List;

/**
 * LLM 响应的数据结构，封装了一次 chat completion 返回的所有有用字段。
 * <p>
 * paper-plugin 只关心：模型说了什么（content），以及它想调用哪些函数（toolCalls）。
 * 其他字段（usage token、finish_reason 等）只用于日志调试，不进这个 record。
 *
 * @param content   模型的纯文本回复（可能是空字符串，当 toolCalls 不为空时）
 * @param toolCalls 模型想调用的函数列表（可能是空列表）
 */
public record LLMResponse(
        String content,
        List<ToolCall> toolCalls
) {
    /** 快速判断：这次调用有没有返回函数调用 */
    public boolean hasToolCall() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 取第一个 tool call，通常 M1 阶段一个 tick 一个村民一个决策 */
    public ToolCall firstToolCall() {
        if (!hasToolCall()) {
            throw new IllegalStateException("No tool calls in this response");
        }
        return toolCalls.get(0);
    }
}
