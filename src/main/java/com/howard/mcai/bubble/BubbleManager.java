package com.howard.mcai.bubble;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.LLMClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 头顶气泡管理器 —— 用 TextDisplay 实体 riding 在生物身上实现。
 * <p>
 * SPEC 第 2.4 节的设计：
 * - TextDisplay 实体（1.19.4+），billboard: center 永远面向玩家
 * - riding 模式挂在生物身上当乘客，位置自动跟随，零 tick 成本
 * - 只在"说话"时冒泡（村民对话、动物叙述、怪物情绪喊话），不常驻
 * - 默认 6 秒后自动移除（config.yml bubble.duration-ticks）
 * - 关闭服务器时清理所有气泡（防止残留浮空文字）
 *
 * v2 泛化：show/showDialog 参数从 Villager 放宽到 LivingEntity ——
 * 动物（叙述体）和怪物（情绪喊话）也能冒泡，样式区分见 showMobBubble。
 *
 * 并发考虑：
 * - 气泡 map 用 ConcurrentHashMap，因为异步 HTTP 回调可能在非主线程触发
 * - 但 TextDisplay 的创建/销毁**必须回主线程**（Bukkit API 硬性要求）
 * - 所以 show() 里先把参数存 map，然后 runTask 主线程操作
 */
public final class BubbleManager implements Listener {

    /** 插件引用，用来拿 scheduler */
    private final McaiPlugin plugin;

    /** 每个生物对应一个气泡实体（UUID = 生物自身的） */
    private final ConcurrentMap<UUID, UUID> villagerToBubble = new ConcurrentHashMap<>();

    /** 同一生物体的自动清理 tick 任务 ID，用来取消上一次的 */
    private final ConcurrentMap<UUID, Integer> cleanupTaskId = new ConcurrentHashMap<>();

    /** "等待玩家靠近"的气泡：entityUuid → 气泡信息。玩家进入 16 格才启动消失倒计时 */
    private final ConcurrentMap<UUID, PendingBubble> pendingBubbles = new ConcurrentHashMap<>();

    /** pending 气泡的监视任务（每 10 tick 检查一次玩家距离） */
    private volatile BukkitTask watcherTask;

    /** 玩家靠近到多少格内才启动气泡消失倒计时 */
    private static final double CLOSE_DISTANCE = 16.0;

    /** 无人靠近时气泡最多挂多久（60 秒）—— 防止永远悬挂泄漏实体 */
    private static final long MAX_HOLD_TICKS = 1200L;

    /** 记录"挂着等玩家"的气泡信息：气泡实体、创建时刻、靠近后倒计时多久 */
    private record PendingBubble(UUID bubbleUuid, long createdTick, long durationTicks) {}

    public BubbleManager(McaiPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 显示气泡文字（旧样式：裸文字，无底板）。
     * <p>
     * 注意：Paper API 操作**必须在主线程**。
     * 如果调用方已经在主线程（如 PlayerInteractEvent），可以直接调用。
     * 如果是异步 HTTP 回调（LLM 回复），调用方需要先 runTask 切回主线程再调这个方法。
     *
     * @param entity 目标生物实体
     * @param text   气泡要显示的文字
     */
    public void show(LivingEntity entity, String text) {
        show(entity, text, plugin.getPluginConfig().bubbleDurationTicks());
    }

    /**
     * 自定义时长版本（旧样式）。
     *
     * @param entity        目标生物实体
     * @param text          气泡文字
     * @param durationTicks 自动消失时长（tick）
     */
    public void show(LivingEntity entity, String text, long durationTicks) {
        show(entity, text, durationTicks, false,
                (float) plugin.getPluginConfig().bubbleOffsetY());
    }

    /**
     * 显示"会话框"—— NPC 对话的正式样式：
     * <ul>
     *   <li>金色名字头一行 + 白色正文（TextDisplay 支持 \n 换行）</li>
     *   <li>深色半透明底板（setBackground），亮地方向/雪地上也看得清</li>
     *   <li>**按文字数量自动计时**：3 秒基础 + 每 3 字 1 秒，最少 6 秒、最长 40 秒
     *       —— 之前固定 6 秒，回复一长还没看完就没了</li>
     * </ul>
     *
     * @param entity      目标生物实体
     * @param speakerName 显示在头行的名字（已去颜色码）
     * @param text        台词正文
     */
    public void showDialog(LivingEntity entity, String speakerName, String text) {
        String body = text == null ? "" : text;
        showDialog(entity, speakerName, null, body, autoDurationTicks(body));
    }

    /**
     * 带"听者"的会话框 —— 玩家/NPC 对话统一走这个重载。
     * <p>
     * 气泡标题和聊天框都会显示 {@code 说话者>听者}，控制台同时留一行
     * {@code [chat] 说话者>听者: 台词}，保证"谁对谁说话"在游戏内外都能追溯。
     */
    public void showDialog(LivingEntity entity, String speakerName, String listenerName, String text) {
        String body = text == null ? "" : text;
        showDialog(entity, speakerName, listenerName, body, autoDurationTicks(body));
    }

    /**
     * 带时长覆盖的会话框版本 —— 闲聊/群聊等"玩家是旁观者"的场景用更长时长，
     * 玩家从远处跑过来还能看清；默认对话仍走 {@link #autoDurationTicks} 动态计时。
     *
     * @param listenerName 听者名字（可为 null：没有明确听者时只显示说话者）
     * @param durationTicks 显示时长（tick），传固定值覆盖自动计算
     */
    public void showDialog(LivingEntity entity, String speakerName, String listenerName,
                           String text, long durationTicks) {
        // 最终收口：无论调用方是谁，气泡正文都再过一遍剥离 —— 防止未来新增的展示路径
        // 忘记清洗，把模型原始 JSON / 工具调用直接怼到玩家脸上。
        String body = LLMClient.stripToolCalls(text == null ? "" : text);
        if (body.isBlank()) return;  // 清洗后没有可读内容 → 不创建空气泡
        String speaker = (speakerName == null || speakerName.isBlank()) ? "村民" : speakerName;
        String title = (listenerName == null || listenerName.isBlank())
                ? speaker : speaker + ">" + listenerName;
        // 全链路留档：所有会话框台词都进控制台（含闲聊/自主行动/无人听者），
        // 格式与游戏内标题一致（说话者>听者），便于玩家/开发者 grep 追溯"谁对谁说"
        plugin.getLogger().info("[chat] " + title + ": " + body);
        String display = "§6§l" + title + "\n§f" + body;
        // 会话框比裸气泡高，Y 偏移多抬一点，防止长回复盖住村民的脸
        show(entity, display, durationTicks, true,
                (float) (plugin.getPluginConfig().bubbleOffsetY() + 0.15));
    }

    /**
     * 动物/怪物气泡（翻译官模式，SPEC 2.4 样式区分）：
     * <ul>
     *   <li>动物：灰色斜体（§7§o）碎片化短句，如"哞——！"、"低头吃草"</li>
     *   <li>怪物：红色（§c）情绪喊话，不说人话，如"嘶——"</li>
     * </ul>
     * 不带名字头，正文颜色由调用方传入；沿用深色底板保证任何光照下可读。
     * 短句时长单独算：3 秒基础 + 每 4 字 1 秒，最短 3 秒、最长 8 秒
     * （比对话框短——碎片句一眼扫完就行，挂太久反而挡视线）。
     */
    public void showMobBubble(LivingEntity entity, String coloredText) {
        String text = coloredText == null ? "" : coloredText;
        // 动物/怪物气泡同样进控制台留档 —— 规则驱动的氛围台词也要"所有运行内容可见"，
        // 颜色码去掉，格式与村民会话框一致，便于 grep
        plugin.getLogger().info("[chat] " + entity.getName() + ": " + text.replaceAll("§.", ""));
        show(entity, text, shortDurationTicks(text), true,
                (float) (plugin.getPluginConfig().bubbleOffsetY() + 0.15));
    }

    /** 短句气泡时长：20 tick = 1 秒；3 + 每 4 字 1 秒，钳制在 [3s, 8s] */
    private long shortDurationTicks(String text) {
        int len = text.length();
        return Math.max(60, Math.min(160, 20L * (3 + (len + 3) / 4)));
    }

    /**
     * 按文字数量计算显示时长。
     * 阅读速度按 ~3 字/秒（留余量），基础 3 秒反应时间；
     * config 的 bubble.duration-ticks 当**最小值**（默认 6 秒），上限 40 秒防挂机刷屏。
     */
    public long autoDurationTicks(String text) {
        int chars = text == null ? 0 : text.length();
        long seconds = 3 + Math.round(chars / 3.0);
        long min = plugin.getPluginConfig().bubbleDurationTicks();
        return Math.min(MAX_DURATION_TICKS, Math.max(min, seconds * 20L));
    }

    /** 会话框最长显示 40 秒（800 tick），防止超长回复挂机不消失 */
    private static final long MAX_DURATION_TICKS = 800L;

    /**
     * 完整实现：创建/替换气泡实体。
     *
     * @param dialogPanel true = 会话框样式（深色底板 + 窄换行宽度）；false = 旧裸文字样式
     * @param offsetY     气泡相对村民的 Y 偏移
     */
    private void show(LivingEntity entity, String text, long durationTicks, boolean dialogPanel, float offsetY) {
        // 取消上一个自动清理任务（如果有的话）—— 说话覆盖说话
        Integer prevId = cleanupTaskId.remove(entity.getUniqueId());
        if (prevId != null) {
            Bukkit.getScheduler().cancelTask(prevId);
        }

        // 先移除旧气泡实体（如果还在）
        UUID oldBubbleUuid = villagerToBubble.remove(entity.getUniqueId());
        if (oldBubbleUuid != null) {
            Entity oldBubble = Bukkit.getEntity(oldBubbleUuid);
            if (oldBubble != null) {
                oldBubble.remove();
            }
        }

        // 创建新气泡 TextDisplay
        Location loc = entity.getLocation().clone();
        TextDisplay bubble = loc.getWorld().spawn(loc, TextDisplay.class, td -> {
            td.setText(text);
            // 永远面向相机（所有玩家都能看到"正脸"的气泡文字）
            td.setBillboard(TextDisplay.Billboard.CENTER);
            // 不被方块挡住（让气泡穿墙，保证玩家 10 格内都能看到）
            td.setSeeThrough(true);
            // 远处也能看到：TextDisplay 的 view_range 默认渲染距离只有约 1 区块，
            // 玩家从远处跑过来时气泡根本不渲染，等走近才出现、看两眼就没了。
            // 设到最大（64 块）让任何合理视距下都可见。
            td.setViewRange(64.0f);
            // 气泡不碰撞、不推挤、不与任何方块/实体交互
            td.setGravity(false);
            td.setPersistent(false);  // 气泡是临时 UI，不序列化到 level.dat
            td.setShadowed(false);    // 有底板就不需要阴影
            if (dialogPanel) {
                // 会话框：半透明黑底板（alpha 可配，默认 70 让背景透出来，不再死黑）
                int alpha = Math.max(0, Math.min(255, plugin.getPluginConfig().bubbleBackgroundAlpha()));
                td.setBackgroundColor(org.bukkit.Color.fromARGB(alpha, 0x0B, 0x0B, 0x0B));
                td.setLineWidth(200);  // 窄换行宽度，模拟聊天气泡（~12 个中文字/行）
            } else {
                td.setLineWidth(300);  // 旧样式：宽行
            }
        });

        // riding 模式：生物带着 bubble 走，位置自动跟随，零 tick 成本
        // 文字缩放可配（text-scale，默认 0.6）—— TextDisplay 没有 fontSize API，
        // 文字渲染大小由 Transformation 的 scale 向量控制，0.6 比默认 1.0 小一号更秀气
        float scale = (float) plugin.getPluginConfig().bubbleTextScale();
        bubble.setTransformation(new org.bukkit.util.Transformation(
                new Vector3f(0f, offsetY, 0f),
                new Quaternionf(0f, 0f, 0f, 1f),
                new Vector3f(scale, scale, scale),
                new Quaternionf(0f, 0f, 0f, 1f)
        ));
        entity.addPassenger(bubble);

        // 记录映射
        villagerToBubble.put(entity.getUniqueId(), bubble.getUniqueId());
        pendingBubbles.remove(entity.getUniqueId());  // 覆盖说话时，清掉旧的"等玩家"状态

        // 清理时机 —— 靠近才开始倒计时：
        // 附近 16 格内已有玩家 → 立即按 durationTicks 倒计时（对话等正常场景，行为不变）；
        // 否则气泡先"挂着"，由 watcher 监视 —— 玩家一旦进入 16 格才启动倒计时，
        // 长时间无人靠近（60s）强制清理，防止气泡实体永久悬挂泄漏。
        if (hasNearbyPlayer(entity.getLocation(), CLOSE_DISTANCE)) {
            scheduleCleanup(entity.getUniqueId(), bubble.getUniqueId(), durationTicks);
        } else {
            pendingBubbles.put(entity.getUniqueId(),
                    new PendingBubble(bubble.getUniqueId(), Bukkit.getCurrentTick(), durationTicks));
            ensureWatcher();
        }
    }

    /** 有没有在线玩家在 loc 的 radius 格内（主线程调用；玩家数量少，直接遍历比 getNearbyEntities 便宜） */
    private boolean hasNearbyPlayer(Location loc, double radius) {
        double d2 = radius * radius;
        for (Player p : loc.getWorld().getPlayers()) {
            if (!p.isDead() && p.getLocation().distanceSquared(loc) <= d2) return true;
        }
        return false;
    }

    /** 懒启动 pending 监视任务（每 10 tick = 0.5s 检查一次玩家距离） */
    private void ensureWatcher() {
        if (watcherTask != null) return;
        watcherTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPendingWatcher, 10L, 10L);
    }

    /**
     * pending 气泡的监视逻辑（主线程 tick）：
     * 1. 气泡/宿主已失效 → 清掉记录；
     * 2. 挂满 MAX_HOLD_TICKS 无人靠近 → 强制清理（防泄漏）；
     * 3. 玩家进入 16 格 → 从 pending 移除，按原 durationTicks 启动消失倒计时。
     */
    private void tickPendingWatcher() {
        if (pendingBubbles.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        var it = pendingBubbles.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID entityUuid = entry.getKey();
            PendingBubble pb = entry.getValue();

            Entity bubble = Bukkit.getEntity(pb.bubbleUuid());
            if (bubble == null || !bubble.isValid()) {   // 被说话覆盖/宿主死亡等已失效
                it.remove();
                continue;
            }
            if (now - pb.createdTick() >= MAX_HOLD_TICKS) {  // 一直没人来，强制清
                it.remove();
                bubble.remove();
                villagerToBubble.remove(entityUuid);
                cleanupTaskId.remove(entityUuid);
                continue;
            }
            if (hasNearbyPlayer(bubble.getLocation(), CLOSE_DISTANCE)) {  // 靠近 → 开始倒计时
                it.remove();
                scheduleCleanup(entityUuid, pb.bubbleUuid(), pb.durationTicks());
            }
        }
        // 没有待等待的气泡了 → 停掉监视任务（懒启动+自停，避免常驻空跑）
        if (pendingBubbles.isEmpty() && watcherTask != null) {
            watcherTask.cancel();
            watcherTask = null;
        }
    }

    /** 调度一次气泡自动清理（scheduleSyncDelayedTask 是单次延迟任务） */
    private void scheduleCleanup(UUID entityUuid, UUID bubbleUuid, long durationTicks) {
        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            Entity b = Bukkit.getEntity(bubbleUuid);
            if (b != null && b.isValid()) {
                b.remove();
            }
            villagerToBubble.remove(entityUuid);
            cleanupTaskId.remove(entityUuid);
        }, durationTicks);
        cleanupTaskId.put(entityUuid, taskId);
    }

    /**
     * 清掉某个生物的气泡（村民死亡时立即清，不等自然过期）。
     * 主线程调用。
     */
    public void clearFor(UUID entityUuid) {
        Integer taskId = cleanupTaskId.remove(entityUuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingBubbles.remove(entityUuid);
        UUID bubbleUuid = villagerToBubble.remove(entityUuid);
        if (bubbleUuid != null) {
            Entity b = Bukkit.getEntity(bubbleUuid);
            if (b != null && b.isValid()) {
                b.remove();
            }
        }
    }

    /** 清理所有气泡（onDisable 调用） */
    public void clearAll() {        // 停掉 pending 监视任务并清空等待队列
        if (watcherTask != null) {
            watcherTask.cancel();
            watcherTask = null;
        }
        pendingBubbles.clear();

        // 取消所有自动清理任务
        cleanupTaskId.values().forEach(Bukkit.getScheduler()::cancelTask);
        cleanupTaskId.clear();

        // 移除所有气泡实体
        for (UUID bubbleUuid : villagerToBubble.values()) {
            Entity b = Bukkit.getEntity(bubbleUuid);
            if (b != null && b.isValid()) {
                b.remove();
            }
        }
        villagerToBubble.clear();
    }

    // ===== Event Listener =====

    /**
     * Chunk load 时可能有村民实体带着旧气泡（不应该发生，但做防御性清理）。
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // M1 简化版：riding 关系实体重新加载后会保持（如果 Bubble 持久化了），
        // 但我们 setPersistent(false) 了，所以 Chunk load 不会有残留问题
    }
}
