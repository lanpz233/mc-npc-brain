package com.howard.mcai.config;

import org.bukkit.configuration.Configuration;

/**
 * 插件配置类 —— 从 config.yml 读取所有配置项。
 * <p>
 * 用 record 实现不可变配置，所有字段在加载时一次性初始化完毕，
 * 后续只读不写。重新配置时通过 /ai reload 重新加载整个对象。
 *
 * 注意：所有字段都给了默认值，即使 config.yml 缺失某项也不会 NPE。
 */
public record PluginConfig(
        // LLM 连接配置
        String llmUrl,
        String llmApiKey,
        String llmModel,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxTokens,
        /** 思考模式控制：null=不干预；"none"=Ollama 的 reasoning_effort:none（Qwen3.5 关思考） */
        String reasoningEffort,
        /**
         * Ollama 专用字段 keep_alive 的值（如 "60m"），空串/null = 不发；
         * 即使是本地 Ollama 值，也只在 endpoint 是本机时才会真正发出去 —— 见 LLMClient。
         */
        String llmKeepAlive,
        /** 台词最长字数（出口硬上限，在句末标点处收尾；面板可调） */
        int maxDialogueChars,

        // Tick 配置
        int tickIntervalTicks,
        int socialIntervalTicks,
        int playerScanRadius,

        // 社交配置（NPC 自动闲聊 + 玩家群体聊天）
        /** 是否启用 NPC 32 格自动闲聊（LLM 驱动，每轮一个话题多人接力） */
        boolean ambientChatEnabled,
        /** 自动闲聊的触发半径（方块），范围内有 AI 村民才开一轮 */
        double ambientChatRadius,
        /** 自动闲聊触发间隔（秒），满足一次 48 格内有玩家才真正开聊 */
        int ambientChatIntervalSeconds,
        /** 是否启用玩家群体聊天（附近 NPC 附和 + 点名/大家才单独调 LLM） */
        boolean groupChatEnabled,
        /** 群体聊天收集附近 NPC 的半径（方块） */
        double groupChatRadius,

        // 气泡配置
        int bubbleDurationTicks,
        double bubbleOffsetY,
        /** 气泡文字缩放（TextDisplay 的 Transformation scale，0.5~0.7 观感最佳） */
        double bubbleTextScale,
        /** 气泡底板不透明度 0~255（70 = 半透明，200 = 深黑） */
        int bubbleBackgroundAlpha,

        // 记忆配置
        int maxChatHistoryTokens,
        int debounceFlushTicks,

        // 需求系统配置（SPEC 2.14 —— NPC 主动性引擎）
        /** 是否启用需求驱动的 LLM 自主行动（需求增长/规则满足不受此开关影响） */
        boolean needsEnabled,
        /** 需求层 tick 间隔（秒）：增长/吃/睡/自主行动的节拍 */
        int needsIntervalSeconds,
        /** 自主行动触发阈值：任一需求 ≥ 该值才触发 LLM 决策 */
        double needsThreshold,
        /** 每个 NPC 自主行动的冷却（秒），防需求一越线就连环烧模型 */
        int needsAutonomyCooldownSeconds,
        /** 自主行动的观众半径（格）：范围内无在线玩家就不触发（省 token，与闲聊同约束） */
        double needsVisibilityRadius,

        // 权限配置（SPEC 2.14 三档权限）
        /** 亲信 favor 门槛（与某玩家 favor ≥ 该值自动成为其亲信） */
        double confidantFavor,
        /** 玩家领地半径（格）：以玩家床重生点为圆心，平民不得在内破坏 */
        double territoryRadius,
        /** 神使传送玩家的聊天确认超时（秒） */
        int teleportConfirmTimeoutSeconds,

        // 调试配置
        /**
         * 是否把 MC-AI 的蓝色调试行发给玩家聊天框。
         * 默认 false：调试行只进控制台日志，正式服/单机玩家的聊天框不再被系统刷屏。
         * 需要排查对话链路时改成 true（/ai reload 生效）。
         */
        boolean debugEnabled,

        // 商店界面（路线二）
        /**
         * 商店标题是否挂资源包背景图。默认 false：没装配套资源包（MCAI-Shop）的客户端
         * 标题会显示方框字，所以装好包再开；开了之后商店窗口是一张木柜台图而不是灰箱子。
         */
        boolean shopSkinEnabled,

        /** 商店资源包内置下载服务的端口（自动发包用，见 resourcepack/PackServer） */
        int resourcePackPort,

        /** 发包地址的主机名覆盖（null = 按每个玩家连进来的地址自动推导；内网穿透/代理时才需要填） */
        String resourcePackHost,

        // 网页配置面板（第一期）
        /** 是否启用网页配置面板（本机管理服务 + 数据文件夹里生成面板 HTML） */
        boolean webPanelEnabled,

        /** 配置面板的本机端口（只绑 127.0.0.1，改后需重启） */
        int webPanelPort
) {

    /**
     * 从 Paper 的 YamlConfiguration 加载配置。
     *
     * @param cfg Bukkit 传给 getConfig() 的 YamlConfiguration（已经 mergeDefaults）
     */
    public static PluginConfig load(Configuration cfg) {
        return new PluginConfig(
                // LLM
                // url 允许只填到域名或 /v1 —— 统一补全成 .../v1/chat/completions，
                // 用户换云端 API（DeepSeek/OpenAI/通义…）时直接粘 OpenAI SDK 的 base_url 就能用
                normalizeLlmUrl(cfg.getString("llm.url", "http://localhost:8080/v1/chat/completions")),
                cfg.getString("llm.api-key", "mcai"),
                cfg.getString("llm.model", "qwen3.5-9b"),
                cfg.getInt("llm.connect-timeout-ms", 5000),
                cfg.getInt("llm.read-timeout-ms", 30000),
                cfg.getInt("llm.max-tokens", 1024),
                cfg.getString("llm.reasoning-effort", null),
                cfg.getString("llm.keep-alive", "60m"),
                Math.clamp(cfg.getInt("llm.max-dialogue-chars", 60), 30, 200),
                // Tick
                cfg.getInt("tick.interval-ticks", 60),
                cfg.getInt("tick.social-interval-ticks", 1200),
                cfg.getInt("tick.player-scan-radius", 10),
                // Social
                cfg.getBoolean("social.ambient-chat-enabled", true),
                cfg.getDouble("social.ambient-chat-radius", 32),
                cfg.getInt("social.ambient-chat-interval-seconds", 30),
                cfg.getBoolean("social.group-chat-enabled", true),
                cfg.getDouble("social.group-chat-radius", 12),
                // Bubble
                cfg.getInt("bubble.duration-ticks", 120),
                cfg.getDouble("bubble.offset-y", 0.4),
                cfg.getDouble("bubble.text-scale", 0.6),
                cfg.getInt("bubble.background-alpha", 70),
                // Memory
                cfg.getInt("memory.max-chat-history-tokens", 1500),
                cfg.getInt("memory.debounce-flush-ticks", 10),
                // Needs（SPEC 2.14）
                cfg.getBoolean("needs.autonomy-enabled", true),
                cfg.getInt("needs.interval-seconds", 30),
                cfg.getDouble("needs.threshold", 80),
                cfg.getInt("needs.autonomy-cooldown-seconds", 120),
                cfg.getDouble("needs.visibility-radius", 32),
                // Permissions（SPEC 2.14）
                cfg.getDouble("permissions.confidant-favor", 60),
                cfg.getDouble("permissions.territory-radius", 24),
                cfg.getInt("permissions.teleport-confirm-timeout-seconds", 30),
                // Debug
                cfg.getBoolean("debug.enabled", false),
                // Villager
                // Shop
                cfg.getBoolean("shop.resource-pack-skin", false),
                cfg.getInt("shop.resource-pack-port", 8765),
                blankToNull(cfg.getString("shop.resource-pack-host", "")),
                // Web panel（第一期）
                cfg.getBoolean("web-panel.enabled", true),
                cfg.getInt("web-panel.port", 8767)
        );
    }

    // ===== LLM endpoint 工具 =====

    /** 配置里的空串一律当作"未配置" */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 对话模型（Qwen）—— 负责人格表达、上下文理解、玩家意图 */
    public LlmSettings dialogSettings() {
        return new LlmSettings("dialog", llmUrl, llmApiKey, llmModel,
                connectTimeoutMs, readTimeoutMs, maxTokens,
                reasoningEffort, llmKeepAlive);
    }

    /**
     * 补全 LLM endpoint —— 只填域名/版本号也能用。
     * <p>
     * 兼容三种写法（都指向同一个 OpenAI 兼容接口）：
     * <pre>
     *   https://api.deepseek.com/v1                   → …/v1/chat/completions
     *   https://api.deepseek.com                      → …/v1/chat/completions
     *   http://localhost:8080                         → …/v1/chat/completions
     *   https://api.openai.com/v1/chat/completions    → 原样（已完整）
     * </pre>
     * 为什么需要：OpenAI SDK 生态里大家习惯填 base_url（到 /v1 为止），
     * 直接粘进来如果插件不补全就会 POST 到 404，报错信息又很难看出是这个原因。
     */
    public static String normalizeLlmUrl(String raw) {
        if (raw == null || raw.isBlank()) return "http://localhost:8080/v1/chat/completions";
        // 去掉尾部斜杠，避免出现 //v1 这种非法路径
        String u = raw.trim().replaceAll("/+$", "");
        if (u.endsWith("/chat/completions")) return u;      // 已完整
        if (u.endsWith("/v1")) return u + "/chat/completions";
        return u + "/v1/chat/completions";
    }

    /**
     * endpoint 是否指向本机（localhost / 127.0.0.1 / ::1 / 0.0.0.0）。
     * <p>
     * 用来决定要不要发 Ollama 专有字段 keep_alive：云端 API（OpenAI/DeepSeek…）
     * 收到不认识的顶层参数会直接 400，本地 llama-server 忽略它、Ollama 靠它保活。
     */
    public static boolean isLocalEndpoint(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("[::1]") || host.equals("0.0.0.0");
        } catch (Exception e) {
            return false;
        }
    }
}
