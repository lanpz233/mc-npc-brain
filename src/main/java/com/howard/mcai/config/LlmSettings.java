package com.howard.mcai.config;

/**
 * 单个 LLM 后端的连接与采样参数。
 * <p>
 * v0.1.4 起只剩对话模型（Qwen）：行为决策已改为插件端打分，不再有第二个模型。
 * 本 record 只描述这一个后端的连接与采样参数。
 *
 * @param role            角色名（dialog），只用于日志排查
 * @param url             完整的 OpenAI 兼容 chat/completions 地址
 * @param apiKey          Authorization Bearer；空 = 不发该头
 * @param model           模型名；"auto" = 运行时探测后端当前加载的模型
 * @param connectTimeoutMs 连接超时
 * @param readTimeoutMs    读取超时
 * @param maxTokens        单次输出上限
 * @param reasoningEffort  Ollama/OpenAI 的思考控制；null/空 = 不发该字段
 * @param keepAlive        Ollama 专用驻留时长；仅本机 endpoint 才发送
 */
public record LlmSettings(
        String role,
        String url,
        String apiKey,
        String model,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxTokens,
        String reasoningEffort,
        String keepAlive
) {

    /** 是否配置为自动跟随后端当前模型 */
    public boolean isAutoModel() {
        return model == null || model.isBlank() || model.equalsIgnoreCase("auto");
    }

    /** 日志用短描述（不打码 key，key 不落日志） */
    public String describe() {
        return role + " @ " + url + " model=" + model;
    }
}
