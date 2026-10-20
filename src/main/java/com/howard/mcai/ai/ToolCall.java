package com.howard.mcai.ai;

import com.google.gson.JsonObject;

/**
 * 函数调用（function call）的数据结构。
 * <p>
 * 对应 OpenAI chat completion 响应里的 {@code message.tool_calls[].function}：
 * <pre>
 *   "tool_calls": [{
 *     "type": "function",
 *     "function": {
 *       "name": "flee_from",
 *       "arguments": "{\"target\":\"player_1\",\"distance\":5}"
 *     }
 *   }]
 * </pre>
 *
 * @param name      函数名（必须在 {@link com.howard.mcai.ai.BehaviorRegistry} 里注册过）
 * @param arguments JSON 对象，包含函数参数（用 JsonObject 方便后续动态取值，避免手动反射）
 */
public record ToolCall(
        String name,
        JsonObject arguments
) {
}
