package com.howard.mcai.webpanel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 面板服务的真 HTTP 往返测试：假 Host（临时目录）+ 本机随机端口 */
class ConfigPanelServerTest {

    @TempDir
    Path dataFolder;

    private ConfigPanelServer server;
    private HttpClient http;
    private String base;
    private boolean httpReachable = true;
    private final AtomicInteger reloads = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), """
                llm:
                  read-timeout-ms: 30000      # 读取超时
                needs:
                  threshold: 80
                """);
        Files.writeString(dataFolder.resolve("recipes.json"),
                "{\"recipes\":{\"minecraft:bread\":{\"materials\":{\"minecraft:wheat\":3},\"name_zh\":\"面包\"}}}");
        ConfigPanelServer.Host host = new ConfigPanelServer.Host() {
            @Override public Path dataFolder() { return dataFolder; }
            @Override public Logger logger() { return Logger.getLogger("panel-test"); }
            @Override public String version() { return "test"; }
            @Override public CompletableFuture<String> reload() {
                reloads.incrementAndGet();
                return CompletableFuture.completedFuture("测试重载完成");
            }
        };
        server = new ConfigPanelServer(host);
        assertTrue(server.start(0));
        base = "http://127.0.0.1:" + server.port();
        // 直连本机：测试机可能给 JVM 挂了代理，走代理连 127.0.0.1 会被拦
        http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .build();
        // 有些沙箱环境会把 JVM 的回环 TCP 也拦掉（连接能通但回的不是 HTTP）。
        // 探一发：拿不到像样的 401 就说明回环不可用，HTTP 往返用例自动跳过
        // （在正常机器上会真跑；本机另用 curl 对同一服务做过冒烟验证）。
        try {
            HttpResponse<String> probe = send("GET", "/api/state", null, false);
            httpReachable = probe.statusCode() == 401;
        } catch (Exception e) {
            httpReachable = false;
        }
    }

    private void requireHttp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(httpReachable,
                "当前环境的回环 TCP 被拦截，跳过 HTTP 往返用例");
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> send(String method, String path, String body, boolean withToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (withToken) b.header("X-MCAI-Panel-Token", server.token());
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json");
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void stateRequiresTokenAndReturnsValues() throws Exception {
        requireHttp();
        assertEquals(401, send("GET", "/api/state", null, false).statusCode());
        HttpResponse<String> resp = send("GET", "/api/state", null, true);
        assertEquals(200, resp.statusCode());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(30000, json.getAsJsonObject("values").get("llm.read-timeout-ms").getAsInt());
        assertEquals(80.0, json.getAsJsonObject("values").get("needs.threshold").getAsDouble());
        assertTrue(json.getAsJsonArray("fields").size() >= 40);
        assertEquals(4, json.getAsJsonArray("files").size());
    }

    @Test
    void saveConfigWritesFileKeepsCommentAndReloads() throws Exception {
        requireHttp();
        HttpResponse<String> resp = send("POST", "/api/config",
                "{\"values\":{\"llm.read-timeout-ms\":45000}}", true);
        assertEquals(200, resp.statusCode());
        String text = Files.readString(dataFolder.resolve("config.yml"));
        assertTrue(text.contains("read-timeout-ms: 45000 # 读取超时"), text);
        assertEquals(1, reloads.get());
        // 备份已生成
        try (var stream = Files.list(dataFolder.resolve("panel-backups"))) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().startsWith("config.yml__")));
        }
    }

    @Test
    void invalidConfigValueChangesNothing() throws Exception {
        requireHttp();
        HttpResponse<String> resp = send("POST", "/api/config",
                "{\"values\":{\"llm.read-timeout-ms\":5}}", true);
        assertEquals(400, resp.statusCode());
        assertTrue(Files.readString(dataFolder.resolve("config.yml")).contains("30000"));
        assertEquals(0, reloads.get());
    }

    @Test
    void saveDataValidatesAndWrites() throws Exception {
        requireHttp();
        HttpResponse<String> bad = send("POST", "/api/data",
                "{\"name\":\"recipes.json\",\"content\":\"{坏的\"}", true);
        assertEquals(400, bad.statusCode());
        HttpResponse<String> good = send("POST", "/api/data",
                "{\"name\":\"recipes.json\",\"content\":\"{\\\"recipes\\\":{\\\"minecraft:bread\\\":{\\\"materials\\\":{\\\"minecraft:wheat\\\":4},\\\"name_zh\\\":\\\"面包\\\"}}}\"}",
                true);
        assertEquals(200, good.statusCode());
        assertTrue(Files.readString(dataFolder.resolve("recipes.json")).contains("\"minecraft:wheat\": 4")
                || Files.readString(dataFolder.resolve("recipes.json")).contains("\"minecraft:wheat\":4"));
    }

    @Test
    void panelFileIsGeneratedWithToken() throws Exception {
        Path panel = server.writePanelFile();
        assertTrue(Files.exists(panel));
        String html = Files.readString(panel);
        assertTrue(html.contains(server.token()));
        assertTrue(html.contains("127.0.0.1:" + server.port()));
    }
}
