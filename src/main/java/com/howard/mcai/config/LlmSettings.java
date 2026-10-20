package com.howard.mcai.config;

/**
 * 单个 LLM 后端的连接与采样参数。
 * <p>
 * 双模型架构里对话模型（Qwen）和行为模型（MiniCPM）需要独立的 url / model / max-tokens：
 * 它们可能跑在同一个 Ollama 上，但模型名不同、输出预算不同，行为模型还必须关闭 thinking
 * 才能稳定输出 JSON。把差异收进这个 record，{@code LLMClient} 就不用关心"自己是谁"。
 *
 * @param role            角色名（dialog / behavior），只用于日志排查
 * @param url             完整的 OpenAI 兼容 chat/completions 地址
 * @param apiKey          Authorization Bearer；空 = 不发该头
 * @param model           模型名；"auto" = 运行时探测后端当前加载的模型
 * @param connectTimeoutMs 连接超时
 * @param readTimeoutMs    读取超时
 * @param maxTokens        单次输出上限
 * @param reasoningEffort  Ollama/OpenAI 的思考控制；null/空 = 不发该字段
 * @param keepAlive        Ollama 专用驻留时长；仅本机 endpoint 才发送
 * @param disableThinking  true = 本机 Ollama 请求里带 think:false（MiniCPM 必须关闭思考才能出 JSON）
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
        String keepAlive,
        boolean disableThinking
) {

    /** 是否配置为自动跟随后端当前模型 */
    public boolean isAutoModel() {
        return model == null || model.isBlank() || model.equalsIgnoreCase("auto");
    }

    /** 日志用短描述（不打码 key，key 不落日志） */
    public String describe() {
        return role + " @ " + url + " model=" + model
                + (disableThinking ? " think=off" : "");
    }
}
