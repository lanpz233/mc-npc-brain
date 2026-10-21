package com.howard.mcai.resourcepack;

import com.howard.mcai.McaiPlugin;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * 商店资源包（MCAI-Shop）的内置下载服务 —— 玩家进服自动发包的"包"从这里下。
 * <p>
 * 为什么自托管：资源包必须有一个能直下 zip 的固定网址。试过一圈免费托管站
 * （catbox 文件子域不可达、uguu 无响应、0x0.st 关上传、pixeldrain 要账号、
 * tmpfiles 下载链带时效令牌），都不适合当长期地址；GitHub 通道又传不了二进制。
 * 插件自己起个小 HTTP 服务最稳：地址按玩家连进来的域名/IP 现拼，校验码现算，
 * 不依赖任何外部网站。
 * <p>
 * 包的来源（先到先用）：① jar 内嵌的 {@code resourcepack/MCAI-Shop.zip}（与插件版本锁死，
 * 永远配套）；② 插件数据文件夹里的 {@code MCAI-Shop.zip}（仅当 jar 没内嵌包时兜底，
 * 如 Gradle 构建）。<b>顺序不能反</b>：2026-10-21 实测事故——数据文件夹里躺着一份
 * 最早期的老包，旧顺序下它把每个新 jar 的新包全压住，服主换了五版 jar、玩家装到的一直是老包
 * （图标素材对不上、按钮全是粉红缺失贴图），查日志 sha1 才发现。两处都没有 →
 * 自动发包静默禁用（手动装包的老路不受影响）。
 * <p>
 * 只在商店皮肤开关打开时启动。端口被占/运行环境缺 httpserver 模块等任何失败
 * 都只记一条警告并降级，绝不拖垮插件启动。
 */
public final class PackServer {

    /** 对外路径（也是发给玩家的下载文件名） */
    public static final String PATH = "/MCAI-Shop.zip";
    private static final String FILE_NAME = "MCAI-Shop.zip";
    private static final String JAR_RESOURCE = "resourcepack/MCAI-Shop.zip";

    private final McaiPlugin plugin;
    private HttpServer server;
    private byte[] packBytes;
    private String sha1Hex;
    private int port;

    public PackServer(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return true = 服务已起来、可以发包 */
    public boolean start(int port) {
        byte[] bytes = resolvePackBytes();
        if (bytes == null || bytes.length == 0) {
            plugin.getLogger().info("[MC-AI] 未找到商店资源包（数据文件夹与 jar 内都没有 "
                    + FILE_NAME + "），资源包自动发送未启用；玩家手动装包不受影响。");
            return false;
        }
        try {
            this.packBytes = bytes;
            this.sha1Hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            this.port = port;
            HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext(PATH, exchange -> {
                try (OutputStream out = exchange.getResponseBody()) {
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        exchange.sendResponseHeaders(200, packBytes.length);
                        out.write(packBytes);
                    }
                } catch (Exception ignored) {
                    // 玩家断线/取消下载是常事，不值得刷日志
                }
            });
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mcai-pack-server");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            this.server = http;
            plugin.getLogger().info("[MC-AI] 商店资源包下载服务已启动: 端口 " + port
                    + "，包大小 " + bytes.length + " 字节，sha1=" + sha1Hex);
            return true;
        } catch (Throwable t) {
            // 包括端口被占、jdk.httpserver 模块缺失（NoClassDefFoundError）等
            plugin.getLogger().warning("[MC-AI] 商店资源包下载服务启动失败（自动发包禁用，手动装包不受影响）: " + t);
            this.server = null;
            this.packBytes = null;
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

    public boolean available() {
        return server != null && packBytes != null;
    }

    public String sha1Hex() {
        return sha1Hex;
    }

    /** 按主机名拼下载地址（主机名由调用方按玩家连进来的地址推导或配置覆盖） */
    public String urlFor(String host) {
        return "http://" + host + ":" + port + PATH;
    }

    private byte[] resolvePackBytes() {
        // ① jar 内嵌副本优先：与插件版本锁死，永远不会被数据文件夹里的老包顶掉
        try (InputStream in = plugin.getResource(JAR_RESOURCE)) {
            if (in != null) {
                plugin.getLogger().info("[MC-AI] 商店资源包来源: jar 内嵌");
                return in.readAllBytes();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[MC-AI] 读取 jar 内嵌资源包失败: " + e.getMessage());
        }
        // ② 数据文件夹外置文件兜底（jar 没内嵌包时，如 Gradle 构建）
        try {
            File external = new File(plugin.getDataFolder(), FILE_NAME);
            if (external.isFile()) {
                plugin.getLogger().info("[MC-AI] 商店资源包来源: 数据文件夹（jar 内无内嵌包）");
                return Files.readAllBytes(external.toPath());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[MC-AI] 读取数据文件夹里的资源包失败: " + e.getMessage());
        }
        return null;
    }
}
