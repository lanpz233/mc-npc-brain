package com.howard.mcai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.howard.mcai.config.LlmSettings;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.ToolRegistry;
import com.howard.mcai.tool.ToolRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行为模型客户端（MiniCPM-1B）—— 只管"下一步做什么"，不负责说话。
 * <p>
 * 与对话模型的差异是实测出来的，不能假设两者协议一致：
 * <ul>
 *   <li>MiniCPM 有 tools capability，但原生 tool_calls 不稳定（实测只回思考文本）</li>
 *   <li>{@code format:"json"} 反而更容易吐出非法 JSON（字符串未闭合）</li>
 *   <li>开启 thinking 时输出预算被思考吃光，content 为空</li>
 * </ul>
 * 因此这里固定走"纯文本 + 强约束 JSON 指令 + 关闭 thinking"，并且：
 * <ol>
 *   <li>一次解析失败就把错误原因反馈给模型，再给一次机会</li>
 *   <li>解析结果最终仍要过 {@code ToolSchema.validate}，模型说了不算</li>
 *   <li>Ollama 用原生 /api/chat（think 字段只在这里可靠）；非 Ollama 回退 OpenAI /v1</li>
 * </ol>
 * 本类不做任何 Bukkit 访问，网络请求全程异步。
 */
public final class BehaviorModelClient {

    /** 连续两次解析失败就放弃，避免 1B 模型在坏格式上反复烧算力 */
    private static final int MAX_PARSE_ATTEMPTS = 2;

    private final LlmSettings settings;
    private final ToolRegistry registry;
    private final BehaviorDecisionParser parser;
    private final LlmToolAdapter adapter;
    private final HttpClient httpClient;

    /**
     * 候选动作：由服务端预先校验过的"可执行选项"。
     * <p>
     * 1B 模型自由生成工具名 + 坐标的合法率太低（实测 10 次只有 5 次能被 schema 接受），
     * 但它从 2-4 个已合法选项中挑一个的能力明显更强。候选模式把"生成"换成"选择"，
     * 模型只输出序号，参数与合法性由服务端保证。
     *
     * @param action 工具规范名；"idle" 表示什么都不做
     * @param label  给模型看的一行人话说明（英文，避免弱模型语言漂移）
     * @param args   已通过 schema 校验的参数；idle 用空对象
     */
    public record Candidate(String action, String label, JsonObject args) {
    }

    /**
     * 候选选择结果。
     *
     * @param request 选中的可执行请求；idle 或失败时为 null
     * @param index   选中的序号（失败为 -1）
     * @param error   失败原因；成功/idle 为 null
     */
    public record ChoiceOutcome(ToolRequest request, int index, String error) {
        public boolean ok() {
            return error == null;
        }

        /** 合法但决定什么都不做 */
        public boolean idle() {
            return request == null && error == null;
        }
    }

    /** 后端是否 Ollama（有 /api/version）；null = 未探测，60 秒后允许重探 */
    private volatile Boolean ollamaBackend;
    private volatile long ollamaCheckedAtMs;
    private static final long OLLAMA_PROBE_TTL_MS = 60_000L;

    public BehaviorModelClient(LlmSettings settings, ToolRegistry registry) {
        this.settings = settings;
        this.registry = registry;
        this.parser = new BehaviorDecisionParser(registry);
        this.adapter = new LlmToolAdapter(registry);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .build();
    }

    public LlmSettings settings() {
        return settings;
    }

    /**
     * 让行为模型基于真实游戏状态选一个动作。
     *
     * @param state 服务端生成的只读状态 JSON（位置/附近实体/任务等）
     * @param hint  给模型的一句人话提示（例如"你饿了"）
     * @return 解析 + 校验后的统一请求；idle 时 request 为 null 且无 error
     */
    public CompletableFuture<BehaviorDecisionParser.Outcome> decide(
            UUID npcId, UUID playerUuid, String callerId, JsonObject state, String hint) {

        if (settings.isAutoModel()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "llm.behavior.model 必须显式指定（auto 会探测到对话模型，行为模型不能用 auto）"));
        }

        List<JsonObject> messages = baseMessages(state, hint);
        return chat(messages)
                .thenCompose(content -> attemptParse(content, npcId, playerUuid, callerId,
                        messages, 1));
    }

    /**
     * 候选模式决策：模型只回候选序号，不生成工具名/坐标。
     * <p>
     * 这是为 1B 级行为模型准备的兜底方案；换成更大的模型后，调用方可以通过
     * {@code llm.behavior.candidate-mode: false} 回到 {@link #decide} 的自由 JSON 模式，
     * 两种模式共用同一套 schema 校验与请求构造，替换模型不需要改代码。
     */
    public CompletableFuture<ChoiceOutcome> decideChoice(
            UUID npcId, UUID playerUuid, String callerId,
            JsonObject state, String hint, List<Candidate> candidates) {

        if (settings.isAutoModel()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "llm.behavior.model 必须显式指定（auto 会探测到对话模型，行为模型不能用 auto）"));
        }
        if (candidates == null || candidates.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("候选动作不能为空"));
        }

        List<JsonObject> messages = choiceMessages(state, hint, candidates);
        return chat(messages).thenCompose(content -> attemptChoice(
                content, npcId, playerUuid, callerId, candidates, messages, 1));
    }

    /** 候选模式的重试循环：序号缺失/越界时把错误反馈给模型再给一次机会 */
    private CompletableFuture<ChoiceOutcome> attemptChoice(
            String content, UUID npcId, UUID playerUuid, String callerId,
            List<Candidate> candidates, List<JsonObject> messages, int attempt) {

        int index = parseChoiceIndex(content);
        if (index < 0 || index >= candidates.size()) {
            if (attempt >= MAX_PARSE_ATTEMPTS) {
                return CompletableFuture.completedFuture(new ChoiceOutcome(null, -1,
                        "序号无效或越界: " + abbreviate(content)));
            }
            List<JsonObject> next = new ArrayList<>(messages);
            next.add(message("assistant", content));
            next.add(message("user", "Invalid choice. Output ONLY {\"choice\": <0-"
                    + (candidates.size() - 1) + ">, \"reason\": \"<short>\"}."));
            return chat(next).thenCompose(retry ->
                    attemptChoice(retry, npcId, playerUuid, callerId, candidates, next, attempt + 1));
        }

        Candidate picked = candidates.get(index);
        if (picked.action() == null || "idle".equalsIgnoreCase(picked.action())) {
            return CompletableFuture.completedFuture(new ChoiceOutcome(null, index, null));
        }

        NpcTool tool = registry.find(picked.action());
        if (tool == null) {
            return CompletableFuture.completedFuture(new ChoiceOutcome(null, index,
                    "未知工具: " + picked.action()));
        }
        JsonObject args = picked.args() == null ? new JsonObject() : picked.args();
        List<String> errors = tool.schema().validate(args);
        if (!errors.isEmpty()) {
            return CompletableFuture.completedFuture(new ChoiceOutcome(null, index,
                    "参数不合法: " + String.join("; ", errors)));
        }
        ToolRequest request = adapter.toRequest(new ToolCall(tool.name(), args),
                CallerType.BEHAVIOR_MODEL, callerId, npcId, playerUuid);
        if (request == null) {
            return CompletableFuture.completedFuture(new ChoiceOutcome(null, index,
                    "工具未注册: " + tool.name()));
        }
        return CompletableFuture.completedFuture(new ChoiceOutcome(request, index, null));
    }

    /** 候选模式的 prompt：明确"只能选，不许造" */
    private List<JsonObject> choiceMessages(JsonObject state, String hint,
                                            List<Candidate> candidates) {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message("system",
                "You are the behavior controller of a Minecraft villager NPC. You never speak to players.\n"
                        + "Choose exactly ONE option from the numbered OPTIONS list. "
                        + "The options are already validated server-side; never invent actions or parameters.\n"
                        + "Output ONLY one JSON object, no markdown, no explanation:\n"
                        + "{\"choice\": <option number>, \"reason\": \"<short reason>\"}\n"
                        + "Prefer idle if nothing important is happening. "
                        + "Do not choose an action that is already RUNNING."));
        StringBuilder user = new StringBuilder();
        user.append("STATE: ").append(state == null ? "{}" : state.toString());
        if (hint != null && !hint.isBlank()) {
            user.append("\nHINT: ").append(hint);
        }
        user.append("\nOPTIONS:\n");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            user.append(i).append(". ").append(candidate.action())
                    .append(" — ").append(candidate.label()).append('\n');
        }
        user.append("Decide. Output ONLY the JSON object.");
        messages.add(message("user", user.toString()));
        return messages;
    }

    /**
     * 从模型输出里提取候选序号；-1 = 提取不到。
     * <p>
     * 按严格度递减尝试三种写法：标准 JSON 的 "choice": N → "choice: N" → 全文只有一个数字。
     * 单独抽成包内静态方法，便于不起 HTTP 的单元测试覆盖。
     */
    static int parseChoiceIndex(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String text = raw
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<\\|think\\|>.*?</\\|think\\|>", "")
                .trim();
        Matcher fence = Pattern.compile("(?s)```(?:json)?\\s*(.*?)```").matcher(text);
        if (fence.find() && !fence.group(1).isBlank()) {
            text = fence.group(1).trim();
        }

        Matcher strict = Pattern.compile("\"choice\"\\s*:\\s*\"?(-?\\d+)\"?").matcher(text);
        if (strict.find()) return nonNegative(strict.group(1));
        Matcher loose = Pattern.compile("(?i)\\bchoice\\b\\s*[:=]?\\s*\"?(-?\\d+)").matcher(text);
        if (loose.find()) return nonNegative(loose.group(1));

        // 最后兜底：模型只回了 "2" / "I choose 2."。要求全文只有一个数字，避免从 STATE 里误抓。
        Matcher digit = Pattern.compile("\\d+").matcher(text);
        if (digit.find()) {
            String only = digit.group();
            if (!digit.find()) return nonNegative(only);
        }
        return -1;
    }

    private static int nonNegative(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }

    /** 递归给出重试机会：解析失败时把原因反馈给模型 */
    private CompletableFuture<BehaviorDecisionParser.Outcome> attemptParse(
            String content, UUID npcId, UUID playerUuid, String callerId,
            List<JsonObject> messages, int attempt) {

        BehaviorDecisionParser.Outcome outcome =
                parser.parse(content, CallerType.BEHAVIOR_MODEL, callerId, npcId, playerUuid);
        if (outcome.ok() || attempt >= MAX_PARSE_ATTEMPTS) {
            return CompletableFuture.completedFuture(outcome);
        }

        // 修复反馈：上一轮输出原样回填，再明确告诉它错在哪
        List<JsonObject> next = new ArrayList<>(messages);
        next.add(message("assistant", content));
        next.add(message("user", "Your previous output was invalid: " + outcome.error()
                + "\nOutput ONLY one valid JSON object. No markdown, no explanation."));
        return chat(next).thenCompose(retryContent ->
                attemptParse(retryContent, npcId, playerUuid, callerId, next, attempt + 1));
    }

    // ===== 请求构造 =====

    private List<JsonObject> baseMessages(JsonObject state, String hint) {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message("system", buildSystemPrompt()));
        StringBuilder user = new StringBuilder();
        user.append("STATE: ").append(state == null ? "{}" : state.toString());
        if (hint != null && !hint.isBlank()) {
            user.append("\nHINT: ").append(hint);
        }
        user.append("\nDecide the single next action. Output ONLY JSON.");
        messages.add(message("user", user.toString()));
        return messages;
    }

    /**
     * 行为模型的 system prompt（英文 —— 项目约定：英文 system 才能稳定约束模型用中文/正确格式）。
     * 工具清单从注册表生成，新增工具不需要改这里。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the behavior controller of a Minecraft villager NPC. ")
                .append("You never speak to players; another model handles dialogue.\n")
                .append("Given the game state, choose exactly ONE action from the allowed list.\n")
                .append("Output ONLY one JSON object, no markdown, no explanation:\n")
                .append("{\"action\":\"<tool name>\",\"args\":{...},\"reason\":\"<short>\"}\n")
                .append("Use {\"action\":\"idle\",\"args\":{},\"reason\":\"nothing to do\"} ")
                .append("when no action is needed.\n")
                .append("Rules:\n")
                .append("- Only use the exact tool names below. Never invent tools or parameters.\n")
                .append("- Coordinates must be reachable: within 32 blocks of the NPC.\n")
                .append("- Prefer idle over random movement. Do not repeat an action that is already RUNNING.\n")
                .append("Allowed actions:\n");
        for (NpcTool tool : registry.all()) {
            if (!tool.allowedCallers().contains(CallerType.BEHAVIOR_MODEL)) continue;
            sb.append("- ").append(tool.name()).append(" {");
            boolean first = true;
            for (var entry : tool.schema().params().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(entry.getKey()).append(':')
                        .append(entry.getValue().type().name().toLowerCase());
                if (tool.schema().required().contains(entry.getKey())) sb.append('*');
            }
            sb.append("} — ").append(tool.description()).append('\n');
        }
        return sb.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    /** 一次模型调用：Ollama 走原生 /api/chat，其他后端回退 OpenAI /v1/chat/completions */
    private CompletableFuture<String> chat(List<JsonObject> messages) {
        return isOllamaAsync().thenCompose(ollama ->
                ollama ? chatOllama(messages) : chatOpenAi(messages));
    }

    private CompletableFuture<String> chatOllama(List<JsonObject> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("stream", false);
        // 关键：1B 模型必须关闭 thinking，否则正文为空
        body.addProperty("think", false);
        JsonArray arr = new JsonArray();
        messages.forEach(arr::add);
        body.add("messages", arr);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.1);
        options.addProperty("num_predict", settings.maxTokens());
        body.add("options", options);

        return send(endpointRoot() + "/api/chat", body)
                .thenApply(json -> {
                    JsonObject msg = json.has("message") && json.get("message").isJsonObject()
                            ? json.getAsJsonObject("message") : null;
                    return msg != null && msg.has("content") && !msg.get("content").isJsonNull()
                            ? msg.get("content").getAsString() : "";
                });
    }

    private CompletableFuture<String> chatOpenAi(List<JsonObject> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("stream", false);
        body.addProperty("max_tokens", settings.maxTokens());
        // 非 Ollama 后端不认识 think；Qwen 系可用 reasoning_effort 关思考
        if (settings.reasoningEffort() != null && !settings.reasoningEffort().isBlank()) {
            body.addProperty("reasoning_effort", settings.reasoningEffort());
        }
        JsonArray arr = new JsonArray();
        messages.forEach(arr::add);
        body.add("messages", arr);

        return send(settings.url(), body)
                .thenApply(json -> {
                    JsonArray choices = json.has("choices")
                            ? json.getAsJsonArray("choices") : new JsonArray();
                    if (choices.isEmpty()) return "";
                    JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    return msg != null && msg.has("content") && !msg.get("content").isJsonNull()
                            ? msg.get("content").getAsString() : "";
                });
    }

    private CompletableFuture<JsonObject> send(String url, JsonObject body) {
        final HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(settings.readTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                b.header("Authorization", "Bearer " + settings.apiKey());
            }
            request = b.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("行为模型 url 非法: " + url, e));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("行为模型 (" + url + ") HTTP "
                                + response.statusCode() + ": " + response.body());
                    }
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                });
    }

    // ===== Ollama 探测 =====

    private CompletableFuture<Boolean> isOllamaAsync() {
        Boolean cached = ollamaBackend;
        long now = System.currentTimeMillis();
        if (cached != null && now - ollamaCheckedAtMs < OLLAMA_PROBE_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        String root = endpointRoot();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(root + "/api/version"))
                    .timeout(Duration.ofMillis(Math.max(1500, settings.connectTimeoutMs())))
                    .GET().build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(false);
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    boolean isOllama = r.statusCode() == 200;
                    ollamaBackend = isOllama;
                    ollamaCheckedAtMs = System.currentTimeMillis();
                    return isOllama;
                })
                .exceptionally(e -> {
                    // 探测失败不能阻断决策：按 OpenAI 兼容接口处理，让调用方拿到真实错误
                    ollamaBackend = false;
                    ollamaCheckedAtMs = System.currentTimeMillis();
                    return false;
                });
    }

    /** endpoint 的 scheme://authority（Ollama 原生接口挂在根路径） */
    private String endpointRoot() {
        try {
            URI u = URI.create(settings.url());
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return "";
        }
    }
}
