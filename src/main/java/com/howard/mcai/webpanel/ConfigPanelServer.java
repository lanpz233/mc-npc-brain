package com.howard.mcai.webpanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 网页配置面板（第一期）的本机管理服务。
 *
 * <p>安全模型：只绑定 127.0.0.1（路由器放行也进不来），且每个请求都要带
 * 本次启动随机生成的密钥。密钥只出现在两处：控制台日志不打印它；它被写进
 * 生成在插件数据文件夹里的面板 HTML —— 能读到服务器文件的人本来就能改
 * 配置文件，信任级别一致。插件重启后密钥自动换新，旧面板文件会被覆盖。</p>
 *
 * <p>所有保存动作：先校验 → 备份旧文件到 panel-backups/ → 原子写盘 → 请插件
 * 在主线程重载配置与数据。任何一步失败都不留半成品。</p>
 */
public final class ConfigPanelServer {

    /** 插件侧要提供的东西（主插件实现，单测可换假的） */
    public interface Host {
        Path dataFolder();

        Logger logger();

        String version();

        /** 在服务器主线程执行配置+数据重载，返回一行摘要 */
        CompletableFuture<String> reload();
    }

    public static final String PANEL_FILE_NAME = "MC-AI配置面板.html";
    private static final String BACKUP_DIR = "panel-backups";
    private static final int MAX_BODY_BYTES = 2_000_000;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter READABLE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Host host;
    private final String token;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object fileLock = new Object();

    private HttpServer server;
    private int port = -1;

    public ConfigPanelServer(Host host) {
        this.host = host;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        this.token = HexFormat.of().formatHex(raw);
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    public boolean start(int port) {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            http.createContext("/", this::handleRoot);
            http.createContext("/api/state", ex -> handleApi(ex, this::state));
            http.createContext("/api/config", ex -> handleApi(ex, this::saveConfig));
            http.createContext("/api/data", ex -> handleApi(ex, this::saveData));
            http.createContext("/api/backups", ex -> handleApi(ex, this::listBackups));
            http.createContext("/api/restore", ex -> handleApi(ex, this::restore));
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mcai-config-panel");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            this.server = http;
            this.port = http.getAddress().getPort();
            host.logger().info("[MC-AI] 网页配置面板已启动: http://127.0.0.1:" + this.port + "（仅本机）");
            return true;
        } catch (Throwable t) {
            host.logger().warning("[MC-AI] 网页配置面板启动失败（不影响插件其他功能）: " + t);
            this.server = null;
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ignored) {
            }
            server = null;
        }
    }

    /** 把面板 HTML 生成到插件数据文件夹（服主双击打开的那一份） */
    public Path writePanelFile() throws IOException {
        Files.createDirectories(host.dataFolder());
        String content = html("http://127.0.0.1:" + port);
        try {
            Path file = host.dataFolder().resolve(PANEL_FILE_NAME);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (java.nio.file.InvalidPathException e) {
            // 个别 Linux 服务器是 POSIX/C 编码，中文文件名根本建不出来 → 退英文名，功能不受影响
            Path file = host.dataFolder().resolve("MC-AI-panel.html");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            host.logger().warning("[MC-AI] 系统编码不支持中文文件名，配置面板改用英文名: " + file);
            return file;
        }
    }

    /** 面板 HTML：apiBase 为空 = 用页面自己的 origin（服务端直出时） */
    private String html(String apiBase) throws IOException {
        try (InputStream in = ConfigPanelServer.class.getResourceAsStream("/webpanel/panel.html")) {
            if (in == null) throw new IOException("jar 里找不到 webpanel/panel.html");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.replace("{{API_BASE}}", apiBase)
                    .replace("{{TOKEN}}", token)
                    .replace("{{VERSION}}", host.version());
        }
    }

    // ===== HTTP 骨架 =====

    private interface ApiAction {
        JsonObject run(HttpExchange exchange) throws Exception;
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            if (query == null || !query.contains("token=" + token)) {
                sendText(exchange, 403, "配置面板需要通过插件生成的 HTML 文件打开"
                        + "（在服务器的 plugins/MC-AI 文件夹里，双击《" + PANEL_FILE_NAME + "》）。");
                return;
            }
            sendText(exchange, 200, html(""), "text/html; charset=utf-8");
        } catch (Exception e) {
            sendText(exchange, 500, "面板页面生成失败: " + e.getMessage());
        }
    }

    private void handleApi(HttpExchange exchange, ApiAction action) throws IOException {
        try {
            cors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }
            String given = exchange.getRequestHeaders().getFirst("X-MCAI-Panel-Token");
            if (given == null || !MessageDigest.isEqual(
                    given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                sendJson(exchange, 401, error("密钥不对——请用插件这次启动生成的面板文件打开（重启后旧文件会被覆盖更新）"));
                return;
            }
            JsonObject result = action.run(exchange);
            sendJson(exchange, 200, result);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            host.logger().warning("[MC-AI] 配置面板处理失败: " + e);
            sendJson(exchange, 500, error("插件处理时出错了: " + e.getMessage()));
        }
    }

    private void cors(HttpExchange exchange) {
        var h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, X-MCAI-Panel-Token");
    }

    private void sendJson(HttpExchange exchange, int code, JsonObject body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int code, String text) throws IOException {
        sendText(exchange, code, text, "text/plain; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int code, String text, String contentType) throws IOException {
        cors(exchange);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendEmpty(HttpExchange exchange, int code) throws IOException {
        cors(exchange);
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message == null ? "未知错误" : message);
        return o;
    }

    private static JsonObject ok(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return o;
    }

    private JsonObject readBodyJson(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("提交的内容太大了");
        }
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("提交格式不对");
        }
    }

    // ===== 各个接口 =====

    private JsonObject state(HttpExchange exchange) {
        JsonObject out = ok("ok");
        out.addProperty("version", host.version());
        var fields = new com.google.gson.JsonArray();
        for (ConfigPanelSchema.Field field : ConfigPanelSchema.FIELDS) {
            JsonObject f = new JsonObject();
            f.addProperty("path", field.path());
            f.addProperty("section", field.section());
            f.addProperty("label", field.label());
            f.addProperty("hint", field.hint());
            f.addProperty("type", field.type().name());
            if (field.min() != null) f.addProperty("min", field.min());
            if (field.max() != null) f.addProperty("max", field.max());
            f.addProperty("live", field.live());
            fields.add(f);
        }
        out.add("fields", fields);
        out.add("values", gson.toJsonTree(currentValues()));
        var files = new com.google.gson.JsonArray();
        for (PanelDataFiles.DataFile df : PanelDataFiles.FILES) {
            JsonObject f = new JsonObject();
            f.addProperty("name", df.name());
            f.addProperty("label", df.label());
            f.addProperty("description", df.description());
            f.addProperty("content", readDataFile(df.name()));
            files.add(f);
        }
        out.add("files", files);
        return out;
    }

    private JsonObject saveConfig(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        JsonObject body = readBodyJson(exchange);
        if (!body.has("values") || !body.get("values").isJsonObject()) {
            throw new IllegalArgumentException("没有收到设置内容");
        }
        Map<String, Object> values = ConfigPanelSchema.validateAll(body.getAsJsonObject("values"));
        synchronized (fileLock) {
            Path configFile = host.dataFolder().resolve("config.yml");
            String current = Files.exists(configFile)
                    ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            String updated = YamlScalarFile.applyValues(current, values);
            backup(configFile, "config.yml");
            atomicWrite(configFile, updated);
        }
        long restartCount = values.keySet().stream()
                .filter(p -> !ConfigPanelSchema.BY_PATH.get(p).live()).count();
        String message = "已保存 " + values.size() + " 项设置。" + reloadNote();
        if (restartCount > 0) {
            message += " 其中 " + restartCount + " 项标了“需重启服务器”，重启后才真正生效。";
        }
        return ok(message);
    }

    private JsonObject saveData(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        JsonObject body = readBodyJson(exchange);
        String name = body.has("name") ? body.get("name").getAsString() : null;
        String content = body.has("content") ? body.get("content").getAsString() : null;
        String formatted = PanelDataFiles.validateAndFormat(name, content);
        Path file = host.dataFolder().resolve(name);
        synchronized (fileLock) {
            backup(file, name);
            atomicWrite(file, formatted);
        }
        return ok("已保存 " + name + "。" + reloadNote());
    }

    private JsonObject listBackups(HttpExchange exchange) throws IOException {
        JsonObject out = ok("ok");
        var arr = new com.google.gson.JsonArray();
        for (BackupEntry b : scanBackups()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", b.id());
            o.addProperty("target", b.target());
            o.addProperty("time", READABLE.format(Files.getLastModifiedTime(b.path()).toInstant()));
            arr.add(o);
        }
        out.add("backups", arr);
        return out;
    }

    private JsonObject restore(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        JsonObject body = readBodyJson(exchange);
        String id = body.has("id") ? body.get("id").getAsString() : "";
        BackupEntry found = null;
        for (BackupEntry b : scanBackups()) {
            if (b.id().equals(id)) found = b;
        }
        if (found == null) {
            throw new IllegalArgumentException("找不到这份备份（可能已被清理）");
        }
        Path target = host.dataFolder().resolve(found.target());
        String content = Files.readString(found.path(), StandardCharsets.UTF_8);
        synchronized (fileLock) {
            if (PanelDataFiles.isAllowed(found.target())) {
                // 数据文件恢复前也过一遍现行校验，防老备份与新结构冲突时直接写坏
                content = PanelDataFiles.validateAndFormat(found.target(), content);
            }
            backup(target, found.target());
            atomicWrite(target, content);
        }
        return ok("已恢复 " + found.target() + " 的备份。" + reloadNote());
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("这个接口只接受 " + method);
        }
    }

    /** 保存后请插件重载；重载失败不算保存失败（文件已经落盘），但要如实告诉服主 */
    private String reloadNote() {
        try {
            String summary = host.reload().get(15, TimeUnit.SECONDS);
            return " 已重新加载：" + summary;
        } catch (Exception e) {
            return " 文件已保存，但自动重载没成功（" + e.getMessage() + "），可以进游戏执行 /ai reload，或重启服务器。";
        }
    }

    // ===== 文件读写 =====

    /** 当前 config.yml 里的值（按清单类型转换），读不到/格式怪的项用默认值兜底 */
    private Map<String, Object> currentValues() {
        Map<String, Object> values = ConfigPanelSchema.defaults();
        try {
            Path configFile = host.dataFolder().resolve("config.yml");
            if (!Files.exists(configFile)) return values;
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            Map<String, String> raw = YamlScalarFile.readValues(
                    text, ConfigPanelSchema.BY_PATH.keySet());
            for (var entry : raw.entrySet()) {
                ConfigPanelSchema.Field field = ConfigPanelSchema.BY_PATH.get(entry.getKey());
                String v = entry.getValue();
                try {
                    switch (field.type()) {
                        case BOOL -> values.put(field.path(), Boolean.parseBoolean(v));
                        case INT -> values.put(field.path(), (int) Math.round(Double.parseDouble(v)));
                        case DOUBLE -> values.put(field.path(), Double.parseDouble(v));
                        default -> values.put(field.path(), v);
                    }
                } catch (RuntimeException ignored) {
                    // 单项坏值不挡整个面板：保留默认值，服主在面板里改掉即可
                }
            }
        } catch (IOException e) {
            host.logger().warning("[MC-AI] 配置面板读取 config.yml 失败: " + e.getMessage());
        }
        return values;
    }

    /** 数据文件内容：数据文件夹优先（服主改过的），缺失时退回 jar 内置版 */
    private String readDataFile(String name) {
        try {
            Path file = host.dataFolder().resolve(name);
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            try (InputStream in = ConfigPanelServer.class.getResourceAsStream("/" + name)) {
                if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            host.logger().warning("[MC-AI] 配置面板读取 " + name + " 失败: " + e.getMessage());
        }
        return "{}";
    }

    private record BackupEntry(String id, String target, Path path) {
    }

    private List<BackupEntry> scanBackups() throws IOException {
        Path dir = host.dataFolder().resolve(BACKUP_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        List<BackupEntry> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                int sep = name.indexOf("__");
                if (sep <= 0 || !name.endsWith(".bak")) continue;
                String target = name.substring(0, sep);
                if (!target.equals("config.yml") && !PanelDataFiles.isAllowed(target)) continue;
                out.add(new BackupEntry(name, target, p));
            }
        }
        out.sort(Comparator.comparing(b -> b.path().getFileName().toString(), Comparator.reverseOrder()));
        return out;
    }

    /** 备份一份：panel-backups/<目标名>__<时间戳>.bak；目标不存在（首次）则跳过 */
    private void backup(Path file, String targetName) throws IOException {
        if (!Files.exists(file)) return;
        Path dir = host.dataFolder().resolve(BACKUP_DIR);
        Files.createDirectories(dir);
        String stamp = TS.format(Instant.now());
        Path dest = dir.resolve(targetName + "__" + stamp + ".bak");
        int n = 1;
        while (Files.exists(dest)) {
            dest = dir.resolve(targetName + "__" + stamp + "-" + (n++) + ".bak");
        }
        Files.copy(file, dest);
        pruneBackups(dir, targetName);
    }

    /** 同一目标只留最近 20 份备份，防止长期开服把磁盘塞满 */
    private void pruneBackups(Path dir, String targetName) {
        try (var stream = Files.list(dir)) {
            List<Path> mine = stream
                    .filter(p -> p.getFileName().toString().startsWith(targetName + "__"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .toList();
            for (int i = 20; i < mine.size(); i++) {
                Files.deleteIfExists(mine.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
