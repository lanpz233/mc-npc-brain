package com.howard.mcai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.howard.mcai.config.LlmSettings;
import com.howard.mcai.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 客户端 —— 调本地 llama-server (OpenAI-compatible /v1/chat/completions)。
 * <p>
 * 用 JDK 21 内置的 {@link java.net.http.HttpClient}，零额外依赖。
 * HTTP 请求走异步（CompletableFuture），不阻塞 Paper 主线程 tick。
 *
 * 线程模型：
 * - 本类的所有公开方法都在调用者线程执行（通常是 Bukkit async scheduler）
 * - 内部 HttpClient 会自动开后台线程做网络 I/O
 * - CompletableFuture 的回调需要调用方自己切回 Bukkit 主线程（Bukkit.getScheduler().runTask）
 *
 * 为什么不用同步阻塞：
 * Paper tick loop 必须在主线程运行，而 llama-server 的每次调用至少 300ms（实测），
 * 如果同步调用会严重卡 tick。异步 HTTP + 主线程回调是 Paper 插件调外部服务的标准做法。
 */
public final class LLMClient {

    /**
     * 旧工具白名单之外的动态工具名（统一 ToolRegistry 里的 wire 名，如 npc_move_to）。
     * <p>
     * 为什么要有：fallback 正则原本只认 BehaviorRegistry 的 18 个旧工具，模型如果按新工具名
     * 输出 {@code npc_move_to(...)} 或裸 JSON，既解析不出来也剥不干净 → 调用丢失 + JSON 原文进气泡。
     * 注册表装配完成后调用 {@link #registerFallbackToolNames(java.util.Collection)} 同步过来。
     * <p>
     * 注意：必须声明在下面三个 Pattern 之前 —— 它们是静态字段，初始化时会调用
     * {@link #toolNameAlternation()}；字段按声明顺序初始化，放在后面会读到 null 直接炸在 onEnable。
     */
    private static volatile Set<String> extraToolNames = Set.of();

    /** Python 风格函数调用匹配：.say(message="...") 或 say(message="...") */
    private static volatile Pattern PY_FUNC_CALL = Pattern.compile(
            "\\.?\\b(" + toolNameAlternation() + ")\\s*\\(");

    /**
     * 判断一段 JSON 是不是真的 tool_call —— "name" 的值必须是注册过的工具名。
     * 之前裸找 "name" 键会把正文里的普通 JSON（比如玩家聊到 {"name":"铁剑"}）误伤剥掉。
     */
    private static volatile Pattern TOOL_NAME_IN_JSON = Pattern.compile(
            "\"name\"\\s*:\\s*\"?(" + toolNameAlternation() + ")\"?");

    /**
     * 内联动作标记 {@code [动作:工具名 参数…]}。
     * <p>
     * 来源：assistant 历史行里我们写了 {@code [动作:give_item 面包]} 这样的动作摘要（好让模型知道
     * 自己上一步做了什么），模型会照着这个格式在正文里"说"它要做什么 —— 但它只是文本，
     * 不解析成 ToolCall 就永远不会执行，于是出现"NPC 满口答应给你改价/给你东西，实际什么都没发生"。
     * 必须解析，且要和 sanitizeDialogue 的剥离对齐（先解析、再剥）。
     */
    private static volatile Pattern BRACKET_ACTION = Pattern.compile(
            "\\[动作[:：]\\s*(" + toolNameAlternation() + ")\\b([^\\]]*)\\]");

    /** {@code [动作:…]} 里的 {@code key=value}（值可带引号）；抽走后原位置替换成空格 */
    private static final Pattern ACTION_KV = Pattern.compile(
            "\\b([A-Za-z_]+)\\s*=\\s*\"?([^\\s,\\]\"']*)\"?");

    /**
     * "工具 ("say", content="台词")" 这类伪调用外壳。
     * <p>
     * 实测 Qwen 会把调用写成：
     * {@code 工具 ("say",\ncontent="老李，今日这日头真毒，你那炉子火大没？")}
     * 外壳是 JSON/代码，只有 content 里的才是真正要对玩家说的话 —— 直接删掉会把台词一起吞了，
     * 所以这里先"拆壳取台词"，再让后续规则去剥其余的调用语法。
     * 中英文外壳（工具/tool）、圆括号/中文括号/冒号、= 或 : 都兼容。
     */
    private static final Pattern WRAPPED_SAY_CALL = Pattern.compile(
            "(?is)(?:工具|tool)\\s*[\\(（:：]\\s*[\"']?(?:say|speak)[\"']?\\s*[,，]\\s*"
                    + "(?:content|message|text|内容|台词)\\s*[=:：]\\s*[\"']([^\"']*)[\"']\\s*[\\)）]?");

    /**
     * 伪工具调用（非 JSON/非函数调用）的"段落特征"。
     * <p>
     * 实测 Qwen 在闲聊里会输出这些变体，现有规则只认 {@code action: say} 这种
     * 标签后直接跟字母的写法，反引号和方括号变体全部漏网：
     * <ul>
     *   <li>{@code [tool] `say`} + {@code [content] "…"} —— 方括号头</li>
     *   <li>{@code Tool: `say`} + {@code Content: "…"} —— 反引号包住工具名</li>
     *   <li>{@code [Tool Call]} + {@code - **action**: `say`} + {@code - **text**: "…"}</li>
     *   <li>{@code {"action":"say","content":"…"}} —— 裸 JSON（键不是 name，逃过 TOOL_NAME_IN_JSON）</li>
     * </ul>
     * 出现这些特征时，真正要对玩家说的台词只在引号里；整行丢弃会让 NPC 无缘无故沉默，
     * 所以先取台词、再剥壳（见 {@link #extractPseudoCallSpeech(String)}）。
     */
    private static final Pattern PSEUDO_SAY_MARKER = Pattern.compile(
            "(?is)(?:\\[\\s*(?:tool|tool\\s*call)\\s*\\]"
                    + "|(?:^|[\\r\\n])\\s*[-*]*\\s*\\**\\s*(?:tool|action|say|speak)\\s*\\**\\s*[:：=]"
                    + "|\\{\\s*\"(?:action|tool)\"\\s*:\\s*\"(?:say|speak)\")");

    /** 台词字段名后面的引号内容（content/text/message/内容/台词，兼容中间夹冒号、反引号、** 等外壳） */
    private static final Pattern SPEECH_FIELD = Pattern.compile(
            "(?is)(?:content|text|message|speech|台词|内容)[^\\n]{0,24}?[\"“]([^\"“”\\n]{1,200})[\"”]");

    /** 没有字段名时的兜底：任意含中文的引号片段 */
    private static final Pattern QUOTED_SPEECH = Pattern.compile(
            "[\"“]([^\"“”\\n]{1,200})[\"”]");

    /**
     * 行首的裸 {@code say("台词"} 调用（常见形态：反引号 + 未闭合右括号）。
     * <p>
     * 实测模型写到一半被截断：{@code `say("麦浪高过头顶，瞅着就饱。"}。
     * 规则 3 找不到闭合括号不会剥，最后被残余守卫整行丢弃，连带把前面正常台词也丢了。
     * 这里直接把引号里的台词抽出来，外壳（含未闭合的尾部）一并丢弃。
     */
    private static final Pattern UNCLOSED_SPEECH_CALL = Pattern.compile(
            "(?is)(?:^|(?<=[\\r\\n]))[\\s`*]*\\b(?:say|speak)\\s*\\(\\s*[\"“]([^\"“”\\n]{1,200})[\"”]\\s*\\)?[^\\r\\n]*");

    /**
     * 残余代码特征（最后一道守卫）：中文台词里几乎不会出现的 ASCII 组合。
     * <ul>
     *   <li>大括号、反引号 —— JSON / 代码块尾巴</li>
     *   <li>ASCII 标识符紧跟 {@code (}、{@code =}、{@code :} —— 函数调用或 key:value</li>
     *   <li>{@code <tag>} —— XML 标签</li>
     *   <li>{@code snake_case} —— 工具名残留（npc_move_to 等）</li>
     *   <li>引号后跟右括号 —— 被杀了一半的调用尾巴</li>
     * </ul>
     */
    private static final Pattern CODE_RESIDUE = Pattern.compile(
            "[{}`]"
                    + "|\\b[A-Za-z_][A-Za-z0-9_]*\\s*[(=:]"
                    + "|<\\s*/?\\s*[A-Za-z][^>]*>"
                    + "|\\b[a-z][a-z0-9]*_[a-z0-9_]+\\b"
                    + "|[\"']\\s*[)\\]}]");

    /** 残余告警节流时间戳（毫秒） */
    private static volatile long lastResidueWarnAt = 0L;

    /**
     * 把统一工具注册表里的工具名并入 fallback 正则。可在每次 /ai reload 后重复调用（幂等）。
     *
     * @param names 工具名（传 wire 名：点号会破坏正则，注册表里 wire 名是下划线形式）
     */
    public static void registerFallbackToolNames(java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        Set<String> merged = new java.util.LinkedHashSet<>(extraToolNames);
        merged.addAll(names);
        extraToolNames = Set.copyOf(merged);
        String alt = toolNameAlternation();
        PY_FUNC_CALL = Pattern.compile("\\.?\\b(" + alt + ")\\s*\\(");
        TOOL_NAME_IN_JSON = Pattern.compile("\"name\"\\s*:\\s*\"?(" + alt + ")\"?");
        BRACKET_ACTION = Pattern.compile("\\[动作[:：]\\s*(" + alt + ")\\b([^\\]]*)\\]");
    }

    /**
     * 用 BehaviorRegistry 的白名单拼正则交替项 —— 工具名清单只在 VALID_TOOL_NAMES
     * 一处维护，之前这里硬编码 10 个、漏了 learn/nod，fallback 路径会丢这两个工具的调用。
     * 长名排前面：正则交替"先到先得"，短名在前可能截断长名（防御性排序，保证行为确定）。
     * 每个名字都 Pattern.quote，避免未来出现带正则元字符的工具名。
     */
    private static String toolNameAlternation() {
        List<String> names = new ArrayList<>(BehaviorRegistry.validToolNames());
        names.addAll(extraToolNames);
        return names.stream()
                .distinct()
                .sorted((a, b) -> b.length() - a.length())
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private final LlmSettings settings;
    private final HttpClient httpClient;

    /**
     * 对话模型客户端 —— 沿用 PluginConfig 里的 llm.* 配置。
     */
    public LLMClient(PluginConfig config) {
        this(config, config.dialogSettings());
    }

    /**
     * 指定角色的客户端 —— 双模型架构里 dialog / behavior 各持一份 settings。
     * HttpClient 是重量级对象（内置连接池），所以整个插件共用一个。
     */
    public LLMClient(PluginConfig config, LlmSettings settings) {
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
                // connect timeout: 网络连不上 llama-server 时快速失败，不阻塞 tick
                .connectTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .build();
        // 每次新建（onEnable / /ai reload / /ai llm 换后端）打一行，方便确认当前连的是哪个后端
        org.bukkit.Bukkit.getLogger().info("[MC-AI] LLM 后端(" + settings.role() + "): "
                + settings.url() + "  model=" + settings.model()
                + (isAutoModel() ? " (auto：运行时自动探测)" : "")
                + (PluginConfig.isLocalEndpoint(settings.url()) ? "  (本机)" : "  (云端)"));
    }

    /**
     * 发一次 chat completion 请求（带 tools，模型会返回 tool_calls 或纯文本）。
     *
     * @param messages 对话历史，每条是 {"role": "system"/"user"/"assistant", "content": "..."}
     *                 用 List<Map> 而非自定义 record，因为 Paper 插件拼 prompt 时结构很灵活
     * @param tools    可用工具列表，JSON Schema 格式（参考 OpenAI tools 定义）
     *                 传空列表代表纯文本对话，不要 tool calls
     * @return CompletableFuture<LLMResponse> —— 成功时包含 content + toolCalls；失败时 completeExceptionally
     */
    public CompletableFuture<LLMResponse> chat(List<Map<String, String>> messages,
                                                List<Map<String, Object>> tools) {
        // llm.model = 'auto' → 每次（按 30s TTL）实时探测"后端当前在用的模型"，跟随你在 Ollama 里的切换
        if (!isAutoModel()) {
            return doChat(messages, tools, settings.model(), false);
        }
        return currentModelAsync().thenCompose(model -> doChat(messages, tools, model, true));
    }

    /**
     * 真正的发请求逻辑。
     *
     * @param model          本次使用的模型名（显式配置值 / auto 探测值）
     * @param allowReResolve auto 模式下，若后端报"模型不存在"，是否允许重探 + 重试一次
     */
    private CompletableFuture<LLMResponse> doChat(List<Map<String, String>> messages,
                                                  List<Map<String, Object>> tools,
                                                  String model, boolean allowReResolve) {
        // 1. 构造请求 body（纯 JSON 字符串，直接 StringBuilder 拼，避免 Gson 嵌套对象）
        String body = buildRequestBody(messages, tools, model);

        // DEBUG：打印 prompt 长度 —— 判断 context window 是否溢出
        int totalChars = body.length();
        double estTokens = totalChars / 2.0;  // 中文≈1.5/char, 英文≈4/char, 混合÷2 粗略估算
        String sysPreview = messages.isEmpty() ? "(empty)"
                : messages.get(0).getOrDefault("content", "(empty)").replace('\n', ' ');
        if (sysPreview.length() > 180) sysPreview = sysPreview.substring(0, 180) + "...";
        org.bukkit.Bukkit.getLogger().info("[MC-AI] LLM POST  model=" + model
                + "  bodyLen=" + totalChars + "chars ≈" + (int) estTokens
                + "tokens  msgs=" + messages.size() + "  SYSTEM_PREVIEW=" + sysPreview);

        // 2. 构造 HTTP request
        //    uri/api-key 都来自 config —— 换后端只改 config.yml 三行（url/api-key/model），
        //    也可以用 /ai llm 命令在游戏里一键切换，不必重启服务器
        final HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(settings.url()))
                    .header("Content-Type", "application/json")
                    // read timeout：默认 30 秒没回就放弃。注意云端 API 排队/长回复可能超 30 秒，
                    // 用 /ai llm 切云端时会自动把 llm.read-timeout-ms 抬到 60 秒
                    .timeout(Duration.ofMillis(settings.readTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            // api-key 留空 → 不发 Authorization 头（本地 llama-server、内网 one-api 之类常不需要）
            if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + settings.apiKey());
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            // url 写错（URI 非法）不能同步抛出去 —— 调用方在异步链路里，抛出去等于打穿调度。
            // 包成 failedFuture，让统一的 exceptionally 分支（降级 idle / 释放 LLM 锁）兜住。
            return CompletableFuture.failedFuture(
                    new RuntimeException("LLM url 非法，请检查 config.yml 的 llm.url: " + settings.url(), e));
        }

        // 3. 异步发送
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode == 200) {
                        return CompletableFuture.completedFuture(parseResponse(response.body()));
                    }
                    // auto 模式：你在 Ollama 里换/删了模型 → 后端回 "model not found"。
                    // 这里立刻重探一次并自动重试，玩家完全不用动配置（不用 /ai reload、不用重启）。
                    if (allowReResolve && looksLikeModelMissing(statusCode, response.body())) {
                        org.bukkit.Bukkit.getLogger().info("[MC-AI] auto 模型已失效(" + model + ")，重新探测当前模型…");
                        autoResolvedModel = null;   // 让 TTL 缓存立刻作废
                        return currentModelAsync().thenCompose(fresh -> doChat(messages, tools, fresh, false));
                    }
                    // 带上 url 一起报错：换后端后最常见的坑就是 url 写错（404）或 key 没填（401）
                    throw new RuntimeException("LLM (" + settings.url() + ") returned HTTP "
                            + statusCode + ": " + response.body());
                });
    }

    /** 后端是否在说"这个模型不存在"（Ollama 回 404 + model not found） */
    private static boolean looksLikeModelMissing(int statusCode, String body) {
        if (body == null) return statusCode == 404;
        String lower = body.toLowerCase();
        return statusCode == 404
                || lower.contains("not found") || lower.contains("no such model")
                || body.contains("不存在") || lower.contains("unknown model");
    }

    // ===== 模型自动识别（llm.model = 'auto'）=====

    /** auto 哨兵值：llm.model 填 auto 或留空 → 运行时跟随后端实际在用的模型 */
    private static final String AUTO_MODEL = "auto";

    /** 探测结果缓存 + 时刻；TTL 30 秒 —— 你在 Ollama 换模型后最多 30 秒内自动跟随 */
    private volatile String autoResolvedModel;
    private volatile long autoResolvedAtMs;
    private static final long AUTO_MODEL_TTL_MS = 30_000L;

    /** 是否配置为自动跟随模型 */
    public boolean isAutoModel() {
        String m = settings.model();
        return m == null || m.isBlank() || m.equalsIgnoreCase(AUTO_MODEL);
    }

    /** 当前该用的模型：非 auto → 配置值；auto → 探测值（过期则重探） */
    private CompletableFuture<String> currentModelAsync() {
        if (!isAutoModel()) return CompletableFuture.completedFuture(settings.model());
        String cached = autoResolvedModel;
        if (cached != null && System.currentTimeMillis() - autoResolvedAtMs < AUTO_MODEL_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return probeModelAsync();
    }

    /** endpoint 的 scheme://authority 部分（探测接口挂在根路径，不在 /v1 下） */
    private String endpointRoot() {
        try {
            URI u = URI.create(settings.url());
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 依次探测：Ollama /api/ps（当前加载在显存，最准）→ /api/tags（已安装列表，按最近更新排）
     * → /v1/models（OpenAI 风格，llama-server / 云端也支持）。
     * <p>
     * 三个接口只要有一个能用就行，不互相阻塞；候选按优先级排好后，**逐个用 /api/show 验证
     * capabilities 是否支持 chat**（不含 completion/tools 的会被跳过）—— 否则会像
     * `mmproj-F16`（视觉投影器）那样被当成模型挑走，请求回 400 "does not support chat"。
     */
    private CompletableFuture<String> probeModelAsync() {
        String root = endpointRoot();
        // /api/tags 是否可用 → 判断这是不是 Ollama。是的话，/api/show 404 说明该条目根本不是可用模型
        // （mmproj-F16 实测就是 404），必须跳过；不是 Ollama 时无法验证，只能放行。
        java.util.concurrent.atomic.AtomicBoolean ollamaLike = new java.util.concurrent.atomic.AtomicBoolean(false);
        CompletableFuture<List<String>> ps = getJsonAsync(root + "/api/ps")
                .thenApply(json -> candidates(json, true)).exceptionally(e -> List.of());
        CompletableFuture<List<String>> tags = getJsonAsync(root + "/api/tags")
                .thenApply(json -> {
                    ollamaLike.set(true);
                    return candidates(json, false);
                }).exceptionally(e -> List.of());
        CompletableFuture<List<String>> openai = getJsonAsync(root + "/v1/models")
                .thenApply(json -> candidates(json, false)).exceptionally(e -> List.of());
        return CompletableFuture.allOf(ps, tags, openai)
                .thenCompose(v -> {
                    // 合并候选并按优先级去重：ps（已加载的排最前）→ tags（最近更新的在前）→ /v1/models
                    List<String> ordered = new ArrayList<>();
                    for (CompletableFuture<List<String>> f : List.of(ps, tags, openai)) {
                        for (String name : f.join()) {
                            if (!ordered.contains(name)) ordered.add(name);
                        }
                    }
                    if (ordered.isEmpty()) {
                        return CompletableFuture.<String>failedFuture(
                                new RuntimeException("探测不到任何模型（" + root + "）"));
                    }
                    // 逐个验证"能不能对话"，跳过投影器/向量模型
                    return firstChatCapable(root, ordered, 0, ollamaLike.get());
                })
                .thenApply(model -> {
                    autoResolvedModel = model;
                    autoResolvedAtMs = System.currentTimeMillis();
                    org.bukkit.Bukkit.getLogger().info("[MC-AI] LLM 自动识别模型: " + model
                            + "  (llm.model=auto，跟随 " + root + " 当前模型)");
                    return model;
                })
                .exceptionallyCompose(e -> CompletableFuture.failedFuture(new RuntimeException(
                        "llm.model=auto 无法自动识别模型（探测 " + root + " 失败）：" + e.getMessage()
                                + "；请用 /ai llm model <模型名> 显式指定", e)));
    }

    /**
     * 把探测响应转成候选模型名列表。
     *
     * @param loadedFirst true = 响应来自 /api/ps（条目本身就是当前加载的模型，保持接口顺序，取最前）；
     *                    false = 列表接口，按 modified_at 从新到旧排（刚导入/更新的模型优先）
     */
    private List<String> candidates(JsonObject json, boolean loadedFirst) {
        List<ModelCandidate> list = new ArrayList<>();
        for (JsonElement el : modelsArray(json)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = firstString(o, "model", "name", "id");
            if (name == null || name.isBlank()) continue;
            // 名字就露馅的（mmproj / embed / clip…）直接排除，省一次 /api/show 往返
            if (isNonChatModelName(name)) continue;
            long ts = 0L;
            if (o.has("modified_at")) {
                try {
                    ts = java.time.OffsetDateTime.parse(o.get("modified_at").getAsString()).toInstant().toEpochMilli();
                } catch (Exception ignored) {
                    // 时间解析失败不影响选模型，退化为按接口顺序
                }
            }
            list.add(new ModelCandidate(name, ts));
        }
        if (!loadedFirst) {
            list.sort(java.util.Comparator.comparingLong(ModelCandidate::modifiedAtMs).reversed());
        }
        List<String> out = new ArrayList<>();
        for (ModelCandidate c : list) out.add(c.name());
        return out;
    }

    /** Ollama 的 /api/ps、/api/tags 都是 {"models":[...]}；OpenAI 风格是 {"data":[{"id":...}]} */
    private static JsonArray modelsArray(JsonObject json) {
        if (json == null) return new JsonArray();
        if (json.has("models") && json.get("models").isJsonArray()) return json.getAsJsonArray("models");
        if (json.has("data") && json.get("data").isJsonArray()) return json.getAsJsonArray("data");
        return new JsonArray();
    }

    /**
     * 从上到下挑第一个"确实能对话"的候选：
     * 用 Ollama 的 /api/show 看 capabilities（含 completion 或 tools 才算可对话）。
     * 非 Ollama 后端（llama-server / 云端，没有 /api/show）无法判定 → 放行。
     */
    private CompletableFuture<String> firstChatCapable(String root, List<String> candidates,
                                                       int idx, boolean ollamaLike) {
        if (idx >= candidates.size()) {
            return CompletableFuture.failedFuture(new RuntimeException(
                    "候选模型都不能对话（已排查 " + String.join("、", candidates) + "）"));
        }
        String name = candidates.get(idx);
        return canChatAsync(root, name, ollamaLike).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(name)
                : firstChatCapable(root, candidates, idx + 1, ollamaLike));
    }

    /**
     * 该模型是否支持对话（/api/show capabilities 里有 completion/tools）。
     *
     * @param ollamaLike true = 后端确实是 Ollama（/api/tags 可用）。此时 /api/show 报错（404）说明这个
     *                   条目压根不是可对话的模型（如 mmproj 投影器），必须跳过；否则无法判定，放行
     */
    private CompletableFuture<Boolean> canChatAsync(String root, String name, boolean ollamaLike) {
        final HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(root + "/api/show"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.max(2000, settings.connectTimeoutMs())))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + escape(name) + "\"}"));
            if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                b.header("Authorization", "Bearer " + settings.apiKey());
            }
            request = b.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(true);
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        // Ollama 认不出这个模型 → 不是可对话模型；非 Ollama 后端没有这个接口 → 放行
                        if (ollamaLike) {
                            org.bukkit.Bukkit.getLogger().info("[MC-AI] auto 跳过候选模型（Ollama 无法识别）: " + name
                                    + "  HTTP " + r.statusCode());
                            return false;
                        }
                        return true;
                    }
                    JsonObject json = JsonParser.parseString(r.body()).getAsJsonObject();
                    if (!json.has("capabilities")) return true;   // 旧版 Ollama 不带这个字段 → 放行
                    for (JsonElement c : json.getAsJsonArray("capabilities")) {
                        String cap = c.getAsString();
                        if ("completion".equals(cap) || "tools".equals(cap)) return true;
                    }
                    org.bukkit.Bukkit.getLogger().info("[MC-AI] auto 跳过候选模型（不支持对话）: " + name
                            + "  capabilities=" + json.get("capabilities"));
                    return false;
                })
                .exceptionally(e -> true);   // 探测接口本身出错 → 放行，别让 auto 整体不可用
    }

    /** 探测到的候选模型 + 最近更新时间（列表接口用它排序） */
    private record ModelCandidate(String name, long modifiedAtMs) {}

    /** 从 JSON 对象里按候选 key 顺序取第一个非空字符串 */
    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                String v = o.get(k).getAsString();
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    /**
     * 名字像向量/重排/视觉投影器/语音模型 → 不可能做 NPC 对话，直接跳过。
     * mmproj-F16 这种视觉投影器最坑：它能被 ollama create 注册成模型，但请求会 400 does not support chat。
     */
    private static boolean isNonChatModelName(String name) {
        String n = name.toLowerCase();
        return n.contains("embed") || n.contains("bge") || n.contains("rerank")
                || n.contains("minilm") || n.contains("whisper")
                || n.contains("mmproj") || n.contains("projector")
                || n.contains("clip") || n.contains("encoder");

    }

    /** 异步 GET 一个 JSON 接口（探测用，短超时，失败快速抛出好走下一档） */
    private CompletableFuture<JsonObject> getJsonAsync(String url) {
        final HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(2000, settings.connectTimeoutMs())))
                    .GET();
            if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                b.header("Authorization", "Bearer " + settings.apiKey());
            }
            request = b.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode());
                    return JsonParser.parseString(r.body()).getAsJsonObject();
                });
    }

    /**
     * 阻塞式列出后端可用对话模型（/ai llm list 用）。
     * <p>
     * 必须在**异步线程**调用（内部是同步 HTTP），否则会卡住服务器主线程。
     */
    public List<String> listModelsBlocking() {
        List<String> out = new ArrayList<>();
        String root = endpointRoot();
        for (String path : List.of("/api/tags", "/v1/models")) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(root + path))
                        .timeout(Duration.ofSeconds(3))
                        .GET();
                if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                    b.header("Authorization", "Bearer " + settings.apiKey());
                }
                HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) continue;
                JsonObject json = JsonParser.parseString(r.body()).getAsJsonObject();
                JsonArray arr = json.has("models") ? json.getAsJsonArray("models")
                        : json.has("data") ? json.getAsJsonArray("data") : null;
                if (arr == null) continue;
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    String name = firstString(el.getAsJsonObject(), "model", "name", "id");
                    if (name != null && !name.isBlank() && !out.contains(name)) out.add(name);
                }
                if (!out.isEmpty()) return out;   // 第一个能用的探测点就够，不必再试
            } catch (Exception ignored) {
                // 换下一个探测点
            }
        }
        return out;
    }

    /**
     * 构造 JSON 请求体。
     * 用 StringBuilder 直接拼——结构固定，比 Gson 嵌套对象更直观、无反射开销。
     */
    private String buildRequestBody(List<Map<String, String>> messages,
                                     List<Map<String, Object>> tools,
                                     String model) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{");
        // model 由调用方传入：显式配置值，或 auto 模式实时探测到的值（见 chat/doChat）
        sb.append("\"model\":\"").append(escape(model)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"max_tokens\":").append(settings.maxTokens()).append(",");

        // 思考开关（Ollama 等 OpenAI 兼容后端）—— reasoning_effort:none 关闭 Qwen3.5 的 <think>，
        // 不关的话 token 全花在思考上、正文为空。llama-server 忽略此字段，两个后端通用。
        if (settings.reasoningEffort() != null && !settings.reasoningEffort().isBlank()) {
            sb.append("\"reasoning_effort\":\"").append(escape(settings.reasoningEffort())).append("\",");
        }

        // 行为模型专用：MiniCPM 开着 thinking 时会把输出预算全耗在思考上，正文为空（实测）。
        // think 是 Ollama 专有顶层字段，只在 endpoint 是本机时发送，云端 API 收到会 400。
        if (settings.disableThinking() && PluginConfig.isLocalEndpoint(settings.url())) {
            sb.append("\"think\":false,");
        }

        // 模型驻留 60 分钟 —— Ollama 默认 5 分钟空闲就卸载 6.1GB 权重，
        // 下一条消息要等十几秒重新加载，玩家会感觉"NPC 突然卡死"。
        // **只在 endpoint 是本机时发**：这是 Ollama 专有字段，云端 API（OpenAI/DeepSeek…）
        // 收到不认识的顶层参数会直接回 HTTP 400「Unrecognized request argument」；
        // 本地 llama-server 忽略它，所以本机统一发不会出问题。
        if (settings.keepAlive() != null && !settings.keepAlive().isBlank()
                && PluginConfig.isLocalEndpoint(settings.url())) {
            sb.append("\"keep_alive\":\"").append(escape(settings.keepAlive())).append("\",");
        }

        // messages 数组
        sb.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"role\":\"").append(escape(msg.getOrDefault("role", "user"))).append("\",");
            sb.append("\"content\":").append(jsonStr(msg.getOrDefault("content", "")));
            sb.append("}");
        }
        sb.append("]");

        // tools 数组（可选）
        if (tools != null && !tools.isEmpty()) {
            sb.append(",\"tools\":").append(toJson(tools));
            sb.append(",\"tool_choice\":\"auto\"");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 解析 JSON 响应 → LLMResponse。
     * 只关心 choices[0].message.content 和 tool_calls 数组，其余忽略。
     *
     * 响应结构（OpenAI /v1/chat/completions）：
     * <pre>
     * {
     *   "choices": [{
     *     "message": {
     *       "content": "...",
     *       "tool_calls": [{
     *         "function": {"name": "...", "arguments": "{...}"}
     *       }]
     *     }
     *   }]
     * }
     * </pre>
     */
    private LLMResponse parseResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                // 没有 choices？兜底返回空
                return new LLMResponse("", List.of());
            }
            JsonObject message = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message");

            // content（可能不存在，当 tool_calls 不为空时）
            String content = "";
            if (message.has("content") && !message.get("content").isJsonNull()) {
                content = message.get("content").getAsString();
            }
            // === 剥离推理块 <think>...</think>（Qwen3.5 等模型即使 reasoning_effort:none
            // 仍会输出思考块，不剥的话会原样进聊天框，观感是模型"自言自语"） ===
            // 在唯一漏斗 parseResponse 这里一次性剥干净，对话/闲聊/群聊/测试命令全部受益
            if (content != null && !content.isBlank()) {
                content = stripThinkBlock(content);
            }

            // tool_calls 数组（可能不存在或为空）
            List<ToolCall> toolCalls = new ArrayList<>();
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                for (JsonElement tc : message.getAsJsonArray("tool_calls")) {
                    JsonObject func = tc.getAsJsonObject().getAsJsonObject("function");
                    String name = func.get("name").getAsString();
                    JsonObject args;
                    if (func.has("arguments") && !func.get("arguments").isJsonNull()) {
                        args = JsonParser.parseString(func.get("arguments").getAsString()).getAsJsonObject();
                    } else {
                        args = new JsonObject();
                    }
                    toolCalls.add(new ToolCall(name, args));
                }
            }

            // === tool_call fallback：标准 tool_calls 数组为空时，从 content 里捞调用 ===
            // qwen 系列格式不稳定，支持三种：
            //  - XML：content 里包 <tool_call>...</tool_call>
            //  - 裸 JSON：content 里直接有 {"name": "say", "arguments": {...}}
            //  - Python 风格：move_to(x=-10, y=8, z=-5) / .say(message="你好")
            //  - 内联标记：[动作:offer_trade 熟牛排, price=1]（模型抄我们写进历史的动作摘要格式）
            // 必须**先解析再剥**：搞反顺序会把调用文本删掉导致解析不到
            if (toolCalls.isEmpty() && content != null) {
                List<ToolCall> parsed = parseHermesToolCalls(content);
                if (parsed.isEmpty()) parsed = parseBracketActions(content);
                toolCalls.addAll(parsed);
            }

            // === 解析失败显式打标（绝不静默）===
            // 模型吐了"像工具调用"的文本（<tool_call> / 裸 JSON / [动作:…] / Python 调用），
            // 却一个 ToolCall 都没解析出来 —— 这堆文本马上会被 stripToolCalls 删掉，
            // 结果就是"AI 说要做、实际什么都没发生"，而且控制台一片安静、没人知道。
            // 这里必须留下证据（原文片段），否则这类 bug 只能靠玩家截图才被发现。
            if (toolCalls.isEmpty() && content != null && !content.isBlank()) {
                String suspicious = describeUnparsedCallText(content);
                if (suspicious != null) {
                    org.bukkit.Bukkit.getLogger().warning(
                            "[MC-AI] LLM 输出含疑似工具调用文本但解析失败，已剥离以免漏进台词: " + suspicious);
                }
            }

            // === content 里的调用文本一律剥干净（含 XML 标签 / 裸 JSON / Python 风格）===
            // 之前的 bug：只有 fallback 解析成功才剥 content。当 Ollama 原生 tool_calls
            // 已给出调用、但模型又在 content 里复述一遍调用文本时（截图里的
            // "move_to(\"x\": -10, \"y\": 8, \"z\": -5)"），content 没被剥 → 原始调用进了气泡。
            // 所以这里无条件剥一次，与 tool_call 来源无关。
            if (content != null && !content.isBlank()) {
                content = stripToolCalls(content);
            }

            return new LLMResponse(content, toolCalls);
        } catch (Exception e) {
            // 解析失败不静默吞掉：包装成 RuntimeException 抛出（不是"返回空兜底"），
            // 由调用方 chat().exceptionally 分支统一兜底（fallback idle / 释放 LLM 锁）
            throw new RuntimeException("Failed to parse LLM response: " + e.getMessage() + "\nBody: " + body, e);
        }
    }

    /**
     * 找出 content 里"看起来像工具调用、但没能解析成 ToolCall"的片段 —— 用于显式告警。
     * <p>
     * 只识别不修复：修复不了的调用文本会被 {@link #stripToolCalls} 删掉，
     * 若不报警，玩家看到的就是"NPC 满口答应、实际什么都没发生"，而控制台一片安静。
     * 四种来源按解析优先级依次探测，命中即返回。
     *
     * @return 命中的片段（已截断到 120 字）或 null（没有疑似调用文本）
     */
    private static String describeUnparsedCallText(String content) {
        Matcher bracket = BRACKET_ACTION.matcher(content);
        if (bracket.find()) return abbreviate(bracket.group());
        if (content.contains("<tool_call>")) return abbreviate("<tool_call> 标签（内部 JSON 非法或未闭合）");
        Matcher named = TOOL_NAME_IN_JSON.matcher(content);
        if (named.find()) return abbreviate(content.substring(named.start()));
        Matcher py = PY_FUNC_CALL.matcher(content);
        if (py.find()) return abbreviate(content.substring(py.start()));
        return null;
    }

    /** 压成单行并截断到 120 字，避免把整段正文刷进日志 */
    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    // ===== JSON 工具方法 =====

    /** 转义 JSON 字符串里的特殊字符（避免 content 里有引号/换行时破坏 JSON 结构） */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // JSON 规范要求所有 <0x20 的控制字符必须转义（不止 \n\r\t，
                    // 还有 \b、\f、\u0001 等）—— 模型偶尔输出这类字符，漏转义
                    // 会让请求体变成非法 JSON，llama-server 直接回 HTTP 400
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** 从 tool_call arguments 里安全取 string 参数（null 安全） */
    public static String safeArg(JsonObject args, String key, String def) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return def;
        return args.get(key).getAsString();
    }

    /**
     * 解析 Hermes 风格的 tool_call 标签。
     * qwen2.5-7b-instruct 输出格式：
     * <pre>
     * {"name": "say", "arguments": {"message": "你好"}}
     * ... 或者嵌套在 XML 标签里：
     * &lt;tool_call&gt;{"name": "say", "arguments": {...}}&lt;/tool_call&gt;
     * </pre>
     *
     * 处理策略：
     * 1. 先找 {@code <tool_call>...</tool_call>} 标签里的 JSON
     * 2. 如果没有 XML 标签，直接找第一个 {@code {}} JSON 对象（arguments 嵌套）
     * 3. 解析成 ToolCall
     */
    private List<ToolCall> parseHermesToolCalls(String content) {
        List<ToolCall> result = new ArrayList<>();
        if (content == null) return result;

        try {
            // 策略 1：找 <tool_call>...</tool_call> 里的 JSON
            int start = content.indexOf("<tool_call>");
            int end = content.indexOf("</tool_call>");
            String jsonStr;

            if (start >= 0 && end > start) {
                jsonStr = content.substring(start + "<tool_call>".length(), end).trim();
            } else {
                // 策略 2：从 content 里找第一个有效的 tool_call JSON
                jsonStr = extractToolCallJson(content);
            }

            if (jsonStr == null) {
                // 策略 3：Python 风格函数调用 —— .say(message="...") / say(message="...")
                // 没有大括号，就是 tool_name(arg="val", arg2=num) 裸文本
                return parsePythonFuncCall(content);
            }

            // 解析 {"name": "xxx", "arguments": {...}}
            JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "unknown";
            JsonObject args;
            if (obj.has("arguments") && !obj.get("arguments").isJsonNull()) {
                JsonElement argsEl = obj.get("arguments");
                if (argsEl.isJsonObject()) {
                    args = argsEl.getAsJsonObject();
                } else if (argsEl.isJsonPrimitive()) {
                    // 有时候 arguments 是 JSON 字符串不是对象
                    args = JsonParser.parseString(argsEl.getAsString()).getAsJsonObject();
                } else {
                    args = new JsonObject();
                }
            } else {
                args = new JsonObject();
            }
            result.add(new ToolCall(name, args));
        } catch (Exception ignored) {
            // Hermes 解析失败就忽略，上层有 fallback
        }

        return result;
    }

    /**
     * 从 content 字符串里提取 tool_call JSON 对象（不依赖 XML 标签）。
     * 找第一个包含 "name" 键 + 工具名的 JSON 对象。
     */
    private static String extractToolCallJson(String content) {
        // 用工具名守卫找 "name":"工具名" —— 不能裸找 "name"（会命中正文里的普通 JSON）
        Matcher nm = TOOL_NAME_IN_JSON.matcher(content);
        if (!nm.find()) return null;
        int[] range = enclosingObject(content, nm.start());
        return range == null ? null : content.substring(range[0], range[1]);
    }

    /**
     * 括号栈配对：从 start 处的 '{' 找到配对的 '}'，返回闭合括号后一位下标；找不到返回 -1。
     * <p>
     * 为什么必须跟踪字符串状态：arguments 的字符串值里可能含大括号
     * （如 message:"玩{这个}！"），裸计数 depth 会被带偏 → JSON 截断或误删正文。
     * 所以遇到 '"' 进入字符串态、'\\' 置转义位，字符串内的 { } 一律不计数。
     */
    private static int matchBrace(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '{') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    /**
     * 找到"包含 name 键位置"的最内层完整 JSON 对象，返回 {start, end}（end 为开区间）。
     * <p>
     * 为什么不能直接 lastIndexOf('{', nameIdx)：模型可能把 arguments 放在 name 前面
     * （{"arguments":{...},"name":"say"}），此时 name 前最近的 '{' 是 arguments 的内层括号，
     * 提取出的只是参数对象、name 变 "unknown" → 生成垃圾 ToolCall。
     * 所以往前找候选 '{' 后，必须验证配对区间确实包住 nameIdx，不包就继续往前找。
     */
    private static int[] enclosingObject(String s, int nameIdx) {
        int braceStart = s.lastIndexOf('{', nameIdx);
        while (braceStart >= 0) {
            int end = matchBrace(s, braceStart);
            if (end < 0) return null;          // 找不到闭合 → 放弃（防截断出半个 JSON）
            if (end > nameIdx) return new int[]{braceStart, end};  // 包住 name → 就是外层 tool_call 对象
            braceStart = s.lastIndexOf('{', braceStart - 1);       // 没包住（arguments 在前）→ 继续往外找
        }
        return null;
    }

    /**
     * 把 content 里的 Hermes XML 标签和 tool_call JSON 剥掉，留下纯文本台词。
     * <p>
     * public static：Smalltalk.stripToolCall（群聊/闲聊一次性回复）也复用这里 ——
     * 模型吐的三种格式（XML 标签 / 裸 JSON / Python 风格）统一在一处剥离，
     * 避免两份实现不同步、原始调用片段漏进气泡。
     *
     * 为什么不用正则？tool_call JSON 里 arguments 本身就是一个对象，
     * 形如 {"name":"say","arguments":{"message":"你好"}} —— 正则的 [^}]* 遇到内层 } 就停了。
     * 所以用**括号栈**找完整的 tool_call JSON 块（处理嵌套 + 字符串内的假括号），再删掉。
     */
    public static String stripToolCalls(String content) {
        if (content == null) return "";

        String cleaned = content;

        // 0. 拆壳取台词：工具 ("say", content="…") / 工具: say, content="…"。
        //    必须最先做 —— 后面的规则会把整段调用语法删掉，台词也会跟着消失。
        cleaned = extractWrappedSpeech(cleaned);

        // 0.1 同样先取台词再剥壳：反引号 / 方括号 / 裸 JSON 等伪调用变体，
        //     台词只存在于 content/text 的引号里，直接走后续剥离会把台词一起删掉。
        cleaned = extractPseudoCallSpeech(cleaned).trim();

        // 0.15 行首裸 say("台词" / `say("台词"：右侧括号可能缺失，
        //      规则 3 找不到闭合位置不会剥，最后被残余守卫整行带走连累正常台词。
        cleaned = unwrapUnclosedSpeechCall(cleaned).trim();

        // 0.2 剥掉内联 [动作:…] 标记 —— 这是我们自己写进对话历史的动作摘要格式，
        //    模型学会了照抄进正文。解析成功的话动作已执行，这里必须剥掉防它漏进气泡/聊天框。
        cleaned = cleaned.replaceAll("\\[动作[:：][^\\]]*\\]", "").trim();

        // 1. 剥掉 <tool_call>...</tool_call> 及其内容
        cleaned = cleaned.replaceAll("<tool_call>.*?</tool_call>", "").trim();

        // 2. 剥掉裸的 tool_call JSON（带嵌套 arguments）
        // 用工具名守卫定位 —— 裸找 "name" 会把正文里的普通 JSON 误伤剥掉；
        // 外层对象由 enclosingObject 定位（引号感知 + 兼容 arguments 在 name 前面的顺序）
        while (true) {
            Matcher nm = TOOL_NAME_IN_JSON.matcher(cleaned);
            if (!nm.find()) break;

            int[] range = enclosingObject(cleaned, nm.start());
            if (range == null) break;  // 找不到完整闭合 → 放弃（防止死循环）

            // 区间必然包含 nameIdx（end > nameIdx ≥ start），串一定变短，无死循环风险
            cleaned = cleaned.substring(0, range[0]) + cleaned.substring(range[1]);
            cleaned = cleaned.trim();
        }

        // 3. 剥掉 Python 风格函数调用 —— .say(message="...") / say(message="...")
        while (true) {
            Matcher m = PY_FUNC_CALL.matcher(cleaned);
            if (!m.find()) break;
            int parenStart = cleaned.indexOf('(', m.start());
            if (parenStart < 0) break;

            // 找闭合 )
            int depth = 1;
            int parenEnd = -1;
            for (int i = parenStart + 1; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) { parenEnd = i + 1; break; } }
            }
            if (parenEnd < 0) break;

            // 前面可能还有 "."
            int prefixStart = m.start();
            while (prefixStart > 0 && (cleaned.charAt(prefixStart - 1) == '.'
                    || Character.isWhitespace(cleaned.charAt(prefixStart - 1)))) {
                prefixStart--;
            }

            cleaned = cleaned.substring(0, prefixStart) + cleaned.substring(parenEnd);
            cleaned = cleaned.trim();
        }

        // 4. 剥掉 Markdown / YAML 风格的伪工具调用 —— Qwen 系列会写：
        //    **action**: "say", *content*
        //    这是在模仿 tool_call 格式，不是台词。从标签起到结尾整段删掉：
        //    这段文本本身没有任何台词价值，留着只会让玩家看到代码。
        cleaned = cleaned.replaceAll(
                "(?is)\\*{0,2}(?:action|tool|name|function|say|speak|动作|工具)\\*{0,2}\\s*[:：]\\s*[`\"']*[A-Za-z_][A-Za-z0-9_.]*[`\"']?.*$",
                "").trim();

        // 5. <action>say</action> 这类自造 XML 也一并剥掉
        cleaned = cleaned.replaceAll("(?is)<(?:action|act|tool)>.*?</(?:action|act|tool)>", "").trim();

        // 6. 代码围栏里的内容一律不是台词 —— 模型爱把 JSON/调用包进 ```json ... ```
        //    先配对删除闭合围栏，再删落单的未闭合围栏到结尾（模型经常写到一半被截断）
        cleaned = cleaned.replaceAll("(?s)```[\\s\\S]*?```", "").trim();
        cleaned = cleaned.replaceAll("(?s)```.*$", "").trim();

        // 7. 残余代码守卫（最后一道白名单式保险）——
        //    黑名单永远追不上模型的花样，所以凡仍带代码特征的整行直接丢弃：
        //    大括号/反引号、ASCII 标识符跟括号或等号、XML 标签、下划线标识符、
        //    引号后跟右括号（调用尾巴）。返回空串由调用方透传 "..."，并且打日志留证，
        //    方便下次直接按真实格式补规则，而不是等玩家再报一次。
        if (looksLikeCodeResidue(cleaned)) {
            warnResidue(cleaned);
            return "";
        }

        return cleaned.trim();
    }

    /**
     * 中文台词里几乎不会出现这些 ASCII 特征 —— 一旦出现，基本就是没剥干净的调用/JSON/XML。
     * 注意刻意不把「ASCII 圆括号」整类判死：模型有时写 {@code (挠头)}，那是动作描写不是代码。
     */
    private static boolean looksLikeCodeResidue(String text) {
        return text != null && !text.isEmpty() && CODE_RESIDUE.matcher(text).find();
    }

    /** 残余代码告警限流：30 秒最多一条，避免模型抽风时刷爆控制台 */
    private static void warnResidue(String cleaned) {
        long now = System.currentTimeMillis();
        if (now - lastResidueWarnAt < 30_000L) return;
        lastResidueWarnAt = now;
        String sample = cleaned.length() > 160 ? cleaned.substring(0, 160) + "…" : cleaned;
        java.util.logging.Logger.getLogger("MC-AI")
                .warning("疑似代码残留，已整行丢弃: " + sample);
    }

    /**
     * 从伪工具调用文本里把"真正要说的话"抽出来。
     * <p>
     * 只在命中 {@link #PSEUDO_SAY_MARKER} 时生效 —— 正常中文台词不含这些特征，
     * 不会被误伤。台词优先取 content/text/message 字段的引号内容（这类字段名是模型
     * 模仿 tool_call 时必带的），没有字段名时退而取最后一个含中文的引号片段。
     * 找不到可用台词就原样返回，交给后续剥离 + 残余守卫处理。
     */
    private static String extractPseudoCallSpeech(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!PSEUDO_SAY_MARKER.matcher(text).find()) return text;

        String speech = null;
        Matcher fields = SPEECH_FIELD.matcher(text);
        while (fields.find()) {
            String candidate = fields.group(1).trim();
            if (!candidate.isEmpty() && containsHan(candidate)) speech = candidate;
        }
        if (speech != null) return speech;

        Matcher quoted = QUOTED_SPEECH.matcher(text);
        while (quoted.find()) {
            String candidate = quoted.group(1).trim();
            if (!candidate.isEmpty() && containsHan(candidate)) speech = candidate;
        }
        return speech != null ? speech : text;
    }

    /** 是否含汉字 —— 用来过滤掉 {@code "say"}、{@code "npc_move_to"} 这类纯 ASCII 引号内容 */
    private static boolean containsHan(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    /**
     * 把 {@code 工具("say", content="台词")} 的外壳换成台词本身，保留前后可能存在的正常文本。
     * <p>
     * 只取 content/message 字段 —— 这是模型唯一真正想说给玩家听的部分；
     * 外壳里的 say、引号、括号全是代码，不能进气泡。
     */
    private static String extractWrappedSpeech(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = WRAPPED_SAY_CALL.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean replaced = false;
        while (m.find()) {
            out.append(text, last, m.start());
            String speech = m.group(1);
            out.append(speech == null ? "" : speech);
            last = m.end();
            replaced = true;
        }
        if (!replaced) return text;
        out.append(text.substring(last));
        return out.toString().trim();
    }

    /**
     * 把行首的 {@code say("台词"} / {@code `say("台词"} 换成台词本身，保留其他行。
     * <p>
     * 这类调用常常没有右括号（模型输出被截断），规则 3 的括号配对找不到终点就原样留下，
     * 随后残余守卫看到 {@code `} 和 {@code say(} 会把整段丢弃 —— 连前面正常的中文台词
     * 一起没了（实测徐燕闲聊）。这里只吃掉调用外壳，台词照常保留。
     */
    private static String unwrapUnclosedSpeechCall(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = UNCLOSED_SPEECH_CALL.matcher(text);
        if (!m.find()) return text;

        StringBuilder out = new StringBuilder();
        int last = 0;
        do {
            out.append(text, last, m.start());
            out.append(m.group(1));
            last = m.end();
        } while (m.find());
        out.append(text.substring(last));
        return out.toString().trim();
    }

    /**
     * 剥离模型的推理块（think/reasoning 内容），只留真正要说的话。
     * <p>
     * Qwen3.5 等带思考模式的模型会把推理过程包在 {@code <think>...</think>} 里
     * （有的后端输出 {@code <|think|>...</|think|>}），这些内容不该进聊天框。
     * 空块（{@code <think>\n\n</think>}）也要剥掉，否则残留空行。
     * 正则加 (?s) 让 . 匹配换行 —— think 块跨多行时默认的 .*? 会匹配失败。
     */
    private static String stripThinkBlock(String content) {
        if (content == null) return content;
        String cleaned = content
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<\\|think\\|>.*?</\\|think\\|>", "")
                .replaceAll("(?s)<reasoning>.*?</reasoning>", "")
                .trim();
        // 剥掉后只剩空内容 → 返回 ""，上层走 "..." 透传（不拿硬编码兜底句顶替）
        return cleaned;
    }

    /** 把字符串包成 JSON 字面量（带引号 + 转义） */
    private static String jsonStr(String s) {
        return "\"" + escape(s) + "\"";
    }

    /** 把 List<Map> 递归转成 JSON 字符串（用于 tools 参数） */
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return jsonStr(s);
        if (obj instanceof Number n) return n.toString();
        if (obj instanceof Boolean b) return b.toString();
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(jsonStr(String.valueOf(e.getKey()))).append(":").append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof Iterable<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return jsonStr(String.valueOf(obj));
    }

    // ===== Python 风格函数调用解析 =====
    // qwen2.5-instruct 偶尔吐这种格式：.say(message="你好") 或 say(message="你好")
    // 没有大括号也没有 XML 标签，就是 tool_name(key="val", key2=num)

    /**
     * 扫描 content 里所有 Python 风格函数调用 → 转成 ToolCall 列表。
     * 匹配格式：{tool_name}({args})，其中 args 支持 key="value" 和 key=number。
     */
    private List<ToolCall> parsePythonFuncCall(String content) {
        List<ToolCall> calls = new ArrayList<>();
        if (content == null) return calls;

        // 遍历找每个 "tool_name(" 结构
        int searchFrom = 0;
        while (searchFrom < content.length()) {
            // 先尝试匹配已知工具名 + "("，前面可能有 "." 或空格
            Matcher m = PY_FUNC_CALL.matcher(content.substring(searchFrom));
            if (!m.find()) break;

            String toolName = m.group(1);

            // 找对应的闭合 ")" —— 用括号栈处理嵌套
            int openIdx = searchFrom + m.start() + toolName.length();  // 定位 "("
            // 其实 m 已经匹配了 toolName，让我们精确找 (
            int parenStart = content.indexOf('(', searchFrom + m.start());
            if (parenStart < 0) break;

            int depth = 1;
            int parenEnd = -1;
            for (int i = parenStart + 1; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) { parenEnd = i; break; }
                }
            }
            if (parenEnd < 0) break;

            String argsStr = content.substring(parenStart + 1, parenEnd).trim();
            JsonObject args = parsePythonArgs(argsStr);
            calls.add(new ToolCall(toolName, args));

            searchFrom = parenEnd + 1;
        }

        return calls;
    }

    /**
     * 解析 Python 风格 key="value", key2=number 参数串。
     * 示例：message="你好", item="铁剑", price=3 → {message:"你好", item:"铁剑", price:3}
     */
    private JsonObject parsePythonArgs(String argsStr) {
        JsonObject args = new JsonObject();
        if (argsStr.isBlank()) return args;

        // 按逗号分割，但要处理引号里的逗号
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (inQuote) {
                cur.append(c);
                if (c == quoteChar && argsStr.charAt(i - 1) != '\\') inQuote = false;
            } else {
                if (c == '"' || c == '\'') { inQuote = true; quoteChar = c; cur.append(c); }
                else if (c == ',') { parts.add(cur.toString().trim()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString().trim());

        for (String part : parts) {
            // 分隔符既可能是 '='（标准 kwargs），也可能是 ':'（模型把参数写成
            // move_to("x": -10, "y": 8, "z": -5) 这种 JSON 风格键值时）
            int sep = firstKeySep(part);
            if (sep < 0) continue;
            String key = part.substring(0, sep).trim();
            String val = part.substring(sep + 1).trim();

            // 去掉 key 两侧引号（"x" → x），避免 JSON 风格键把参数名带脏
            if ((key.startsWith("\"") && key.endsWith("\"")) ||
                    (key.startsWith("'") && key.endsWith("'"))) {
                key = key.substring(1, key.length() - 1);
            }

            // 去掉 val 引号 → 字符串；否则尝试数字
            if ((val.startsWith("\"") && val.endsWith("\"")) ||
                    (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
                args.addProperty(key, val);
            } else {
                // 尝试数字
                try { args.addProperty(key, Integer.parseInt(val)); }
                catch (NumberFormatException e) { args.addProperty(key, val); }
            }
        }

        return args;
    }

    /** 找 key/value 分隔符 '=' 或 ':' —— 只认引号外的第一个（值里的冒号/等号不算） */
    private static int firstKeySep(String part) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (inQuote) {
                if (c == quoteChar && (i == 0 || part.charAt(i - 1) != '\\')) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    // ===== 内联 [动作:…] 解析 =====

    /**
     * 扫描 content 里所有 {@code [动作:工具名 参数…]} 内联标记 → ToolCall 列表。
     * <p>
     * 为什么必须解析：assistant 历史行里我们写了 {@code [动作:give_item 面包]} 这类动作摘要
     * （为的是让模型知道自己上一步做了什么），模型会照着这个格式在正文里"宣布"自己的动作 ——
     * 但那只是文本，不解析成 ToolCall 就永远不会执行，于是"NPC 满口答应改价/给东西，实际什么都没发生"。
     * 参数两种写法都认：{@code key=value}（可带引号）与裸值（第一个裸值按工具语义归位）。
     */
    private List<ToolCall> parseBracketActions(String content) {
        List<ToolCall> calls = new ArrayList<>();
        if (content == null) return calls;
        Matcher m = BRACKET_ACTION.matcher(content);
        while (m.find()) {
            calls.add(new ToolCall(m.group(1), parseActionArgs(m.group(1), m.group(2))));
        }
        return calls;
    }

    /** {@code [动作:…]} 的参数串 → JsonObject：先抽 key=value，剩下的裸值按工具语义归位 */
    private JsonObject parseActionArgs(String toolName, String rest) {
        JsonObject args = new JsonObject();
        if (rest == null || rest.isBlank()) return args;

        // 1. 抽 key=value（值可能带引号），把抽走的位置换成空格 —— 剩下的就是裸值
        List<String> bare = new ArrayList<>();
        StringBuilder leftover = new StringBuilder();
        Matcher kv = ACTION_KV.matcher(rest);
        int cursor = 0;
        while (kv.find()) {
            leftover.append(rest, cursor, kv.start()).append(' ');
            cursor = kv.end();
            String val = kv.group(2);
            if (val != null && !val.isBlank()) args.addProperty(kv.group(1), val);
        }
        if (cursor < rest.length()) leftover.append(rest, cursor, rest.length());

        for (String tok : leftover.toString().split("[\\s,，]+")) {
            String t = tok.replaceAll("^[\"']|[\"']$", "").trim();
            if (!t.isEmpty()) bare.add(t);
        }
        if (bare.isEmpty()) return args;

        // 2. 裸值归位：不同工具的"第一个裸值"含义不同
        String first = bare.get(0);
        switch (toolName) {
            case "say" -> args.addProperty("message", String.join(" ", bare));
            case "learn" -> args.addProperty("content", String.join(" ", bare));
            case "give_item", "equip" -> {
                args.addProperty("item", first);
                if (bare.size() > 1 && isNumeric(bare.get(1))) args.addProperty("count", bare.get(1));
            }
            case "offer_trade" -> {
                // open/offer/discount/remove 出现在第一位 = 动作类型，不是商品名
                if (bare.size() == 1 && (first.equals("open") || first.equals("offer")
                        || first.equals("discount") || first.equals("remove"))) {
                    args.addProperty("action", first);
                } else {
                    args.addProperty("item", first);
                    if (bare.size() > 1 && isNumeric(bare.get(1))) {
                        args.addProperty("price", bare.get(1));
                        // 只给了商品+价格、没给 action —— 模型的意思就是"把价改成这个数"。
                        // 补 action=offer：对已在菜单上的商品，动态价格表会直接覆盖原价（不新增条目）；
                        // 对不在菜单上的商品则补一条，比 discount（找不到菜就静默失败）更不容易翻车。
                        if (!args.has("action")) args.addProperty("action", "offer");
                    }
                }
            }
            case "move_to", "teleport", "break_block" -> fillCoords(args, bare, false);
            case "place_block" -> fillCoords(args, bare, true);
            case "teleport_to", "look_at" -> args.addProperty("target_id", first);
            case "follow_player" -> args.addProperty("player_id", first);
            default -> args.addProperty("item", first);
        }
        return args;
    }

    /** 裸值里的连续数字依次填进 x/y/z（place_block 顺带把第一段非数字认作 material） */
    private static void fillCoords(JsonObject args, List<String> bare, boolean withMaterial) {
        String[] xyz = {"x", "y", "z"};
        int i = 0;
        for (String b : bare) {
            if (isNumeric(b) && i < 3) args.addProperty(xyz[i++], b);
            else if (withMaterial && !args.has("material")) args.addProperty("material", b);
        }
    }

    private static boolean isNumeric(String s) {
        return s != null && s.matches("-?\\d+(\\.\\d+)?");
    }
}
