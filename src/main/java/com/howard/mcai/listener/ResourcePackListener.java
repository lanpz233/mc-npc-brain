package com.howard.mcai.listener;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.config.PluginConfig;
import com.howard.mcai.resourcepack.PackServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * 玩家进服自动发商店资源包（MCAI-Shop）。
 * <p>
 * 只在商店皮肤开关打开、且内置下载服务（{@link PackServer}）起来了时才发。
 * 下载地址的主机名按**每个玩家自己连进来的地址**推导（他用什么地址连的游戏，
 * 就用那个地址下包，天然对应他的网络路径）；配置里写死了 host 时用配置的
 * （内网穿透/代理把虚拟主机改掉的场景）。
 * <p>
 * 玩家拒绝或下载失败只记控制台日志，不拦人进服、不刷屏提示——皮肤是锦上添花，
 * 没装包的玩家商店照样能用（标题是方框字而已）。
 */
public final class ResourcePackListener implements Listener {

    private final McaiPlugin plugin;

    public ResourcePackListener(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PluginConfig cfg = plugin.getPluginConfig();
        PackServer pack = plugin.getPackServer();
        if (cfg == null || !cfg.shopSkinEnabled() || pack == null || !pack.available()) return;

        Player player = event.getPlayer();
        // 晚 1 秒再发：等玩家完全进世界，客户端弹资源包提示更稳
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            String host = cfg.resourcePackHost();
            if (host == null) {
                var virtual = player.getVirtualHost();
                if (virtual != null) host = virtual.getHostString();
            }
            if (host == null || host.isBlank()) {
                plugin.getLogger().warning("[MC-AI] 无法确定给 " + player.getName()
                        + " 发包用的地址（虚拟主机为空且未配置 shop.resource-pack-host），本次跳过");
                return;
            }
            String url = pack.urlFor(host);
            try {
                player.setResourcePack(url, pack.sha1Hex());
                plugin.getLogger().info("[MC-AI] 已向 " + player.getName() + " 发送商店资源包: " + url);
            } catch (Exception e) {
                plugin.getLogger().warning("[MC-AI] 向 " + player.getName() + " 发送资源包失败: " + e.getMessage());
            }
        }, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        // 只记有诊断价值的三种结果：装好了 / 被拒绝 / 下载失败；
        // ACCEPTED、DOWNLOADED 是中间态，每次进服都会有，不刷屏
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> plugin.getLogger().info(
                    "[MC-AI] " + event.getPlayer().getName() + " 的商店资源包已生效");
            case DECLINED -> plugin.getLogger().info(
                    "[MC-AI] " + event.getPlayer().getName() + " 拒绝了商店资源包（商店标题会是方框字，功能不受影响）");
            case FAILED_DOWNLOAD -> plugin.getLogger().warning(
                    "[MC-AI] " + event.getPlayer().getName() + " 下载商店资源包失败——多半是下载端口没放行，"
                            + "检查路由器/防火墙是否放行了配置里的 shop.resource-pack-port");
            default -> {
            }
        }
    }
}
