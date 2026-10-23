package com.howard.mcai.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆存储 —— VillagerState 为内存真源 + debounce flush + JSON 文件持久化。
 * <p>
 * v0.1.3 修复：旧版只持久化 emotion，chatHistory/summary/facts/砍价价/上架商品
 * 重启全丢。现在全量持久化：
 * <ul>
 *   <li>emotion（fear/favor）</li>
 *   <li>summary（滚动摘要）</li>
 *   <li>chatHistory（最近 ≤10 轮原文）</li>
 *   <li>facts（关键事实 ≤20 条）</li>
 *   <li>dynamicPrices（砍价后的动态价格）</li>
 *   <li>offeredItems（LLM 上架的额外商品）</li>
 * </ul>
 * <p>
 * 另外支持运行中途注册的村民（chunk 扫描 / ChunkLoadEvent）：
 * loadVillager 按需从磁盘恢复；writeToFile 时按需建档，
 * 不再依赖"onEnable 时 map 里已有"的假设（旧版后注册村民永远落不了盘）。
 * <p>
 * 文件位置：plugins/MC-AI/memory/<villager_uuid>.json
 */
public final class MemoryStore {

    private final McaiPlugin plugin;

    /** 标记哪些村民需要落盘 */
    private final Set<UUID> dirtySet = ConcurrentHashMap.newKeySet();

    /** 防止重复调度 debounce 的标志 */
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    /** 全部村民运行时状态（内存真源，序列化时实时读取） */
    private final Map<UUID, VillagerState> villagers;

    public MemoryStore(McaiPlugin plugin, Map<UUID, VillagerState> villagers) {
        this.plugin = plugin;
        // 不缓存 PluginConfig 引用：/ai reload 会 new 一个新 config 对象，
        // 缓存旧引用会导致防抖间隔等配置永远停留在启动时的值 —— 每次用时动态取
        this.villagers = villagers;
    }

    /** 加载所有已注册村民的记忆文件。onEnable 时调一次。 */
    public void loadAll() {
        for (VillagerState state : List.copyOf(villagers.values())) {
            loadVillager(state);
        }
    }

    /**
     * 加载单个村民的记忆 —— 供运行中途注册的村民调用。
     * 文件不存在直接返回（首次写入时自动建档）。
     */
    public void loadVillager(VillagerState state) {
        Path file = getMemoryDir().resolve(state.getUniqueId() + ".json");
        if (!Files.exists(file)) return;

        try {
            JsonObject data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            restore(state, data);
            plugin.getLogger().info("恢复记忆: " + state.getUniqueId()
                    + " (chat=" + state.chatHistory().size()
                    + " facts=" + state.facts().size()
                    + " prices=" + state.dynamicPrices().size()
                    + " offers=" + state.offeredItems().size() + ")");
            // 迁移守卫：老存档没有职业背包 → 补发一次（只增不删，保留面包），补发后落盘
            if (!state.isInventorySeeded()) {
                state.seedProfessionInventory();
                state.setInventorySeeded(true);
                markDirty(state.getUniqueId());
                plugin.getLogger().info("§b[MC-AI] 老村民补发职业背包: " + state.getUniqueId()
                        + " (" + state.profession().zhName + ")");
            }
            // 迁移守卫：老村民没有启动钱 → 补发一次（收购从他兜里真出钱，没钱买卖转不起来）
            boolean seededNow = false;
            if (!state.isWalletSeeded()) {
                state.seedWallet();
                state.setWalletSeeded(true);
                seededNow = true;
                markDirty(state.getUniqueId());
                plugin.getLogger().info("§b[MC-AI] 老村民补发启动钱: " + state.getUniqueId()
                        + " (" + state.profession().zhName + ")");
            }
            // v0.1.3 钱包扩容：旧村民按新标准（原来的 3 倍）补足一次。
            // 刚刚才补发启动钱的村民拿到的已经是新标准，不再重复补。
            if (seededNow) {
                state.setWalletV013Boosted(true);
            } else if (!state.isWalletV013Boosted()) {
                int added = state.topUpWalletForV013();
                state.setWalletV013Boosted(true);
                markDirty(state.getUniqueId());
                plugin.getLogger().info("§b[MC-AI] v0.1.3 村民收购钱包已扩容: " + state.getUniqueId()
                        + " (" + state.profession().zhName + ") 补入 " + added + " 块绿宝石，现有 "
                        + state.stockOf(org.bukkit.Material.EMERALD) + " 块");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("记忆文件损坏，忽略: " + file + " — " + e.getMessage());
        }
    }

    /** 反序列化 JSON → 回填 VillagerState */
    private void restore(VillagerState state, JsonObject data) {
        if (data.has("emotion") && data.get("emotion").isJsonObject()) {
            JsonObject emotion = data.getAsJsonObject("emotion");
            if (emotion.has("fear")) state.emotion().setFear(emotion.get("fear").getAsInt());
            // 旧档只有一个标量 favor → 读进 legacyFavor（作为无记录玩家的回退值）
            if (emotion.has("favor")) state.emotion().setLegacyFavor(emotion.get("favor").getAsDouble());
            // 新档按玩家分账 favors → 覆盖式恢复（key = 玩家 UUID 字符串）
            if (emotion.has("favors") && emotion.get("favors").isJsonObject()) {
                Map<String, Double> favors = new HashMap<>();
                for (var e : emotion.getAsJsonObject("favors").entrySet()) {
                    favors.put(e.getKey(), e.getValue().getAsDouble());
                }
                state.emotion().restoreFavors(favors);
            }
        }
        // 性格开放度持久化 —— 重启后每个 NPC 的"好学程度"不变
        if (data.has("openness")) {
            state.setOpenness(data.get("openness").getAsInt());
        }
        // 人格标签持久化 —— 重启后每个 NPC 的独特性格不变
        if (data.has("personalityTags") && data.get("personalityTags").isJsonArray()) {
            List<String> tags = new ArrayList<>();
            for (JsonElement el : data.getAsJsonArray("personalityTags")) {
                tags.add(el.getAsString());
            }
            state.restorePersonalityTags(tags);
        }
        // 性别持久化 —— 重启后男女不变（旧文件无该字段 → 保留构造随机值，下次 flush 落盘）
        if (data.has("gender")) {
            state.setGender(data.get("gender").getAsString());
        }
        // 家庭关系持久化 —— 重启后夫妻/兄弟姐妹不变（旧文件无 → 由配对器补配）
        if (data.has("relationships") && data.get("relationships").isJsonArray()) {
            List<VillagerState.Relationship> rels = new ArrayList<>();
            for (JsonElement el : data.getAsJsonArray("relationships")) {
                JsonObject o = el.getAsJsonObject();
                rels.add(new VillagerState.Relationship(
                        o.has("type") ? o.get("type").getAsString() : "sibling",
                        o.has("targetUuid") ? o.get("targetUuid").getAsString() : "",
                        o.has("targetName") ? o.get("targetName").getAsString() : "?"));
            }
            state.restoreRelationships(rels);
        }
        if (data.has("summary")) {
            state.setSummary(data.get("summary").getAsString());
        }
        if (data.has("chatHistory") && data.get("chatHistory").isJsonArray()) {
            List<VillagerState.ChatEntry> entries = new ArrayList<>();
            for (JsonElement el : data.getAsJsonArray("chatHistory")) {
                JsonObject o = el.getAsJsonObject();
                entries.add(new VillagerState.ChatEntry(
                        o.has("role") ? o.get("role").getAsString() : "user",
                        o.has("content") ? o.get("content").getAsString() : ""));
            }
            state.restoreChatHistory(entries);
        }
        if (data.has("facts") && data.get("facts").isJsonArray()) {
            List<VillagerState.Fact> facts = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (JsonElement el : data.getAsJsonArray("facts")) {
                if (el.isJsonObject()) {
                    // 新格式：{text, ts, gossip, owner}
                    JsonObject o = el.getAsJsonObject();
                    facts.add(new VillagerState.Fact(
                            o.has("text") ? o.get("text").getAsString() : "",
                            o.has("ts") ? o.get("ts").getAsLong() : now,
                            o.has("gossip") && o.get("gossip").getAsBoolean(),
                            o.has("owner") ? o.get("owner").getAsString() : null));
                } else {
                    // 旧格式兼容：纯字符串数组 —— 无时间戳无标记，
                    // ts 取当前时间（从加载起重新计 7 天遗忘），大嘴巴豁免默认否（无从判断来源）
                    facts.add(new VillagerState.Fact(el.getAsString(), now, false));
                }
            }
            state.restoreFacts(facts);
        }
        if (data.has("dynamicPrices") && data.get("dynamicPrices").isJsonObject()) {
            Map<String, Integer> prices = new HashMap<>();
            for (var e : data.getAsJsonObject("dynamicPrices").entrySet()) {
                prices.put(e.getKey(), e.getValue().getAsInt());
            }
            state.restoreDynamicPrices(prices);
        }
        if (data.has("offeredItems") && data.get("offeredItems").isJsonObject()) {
            Map<String, VillagerState.OfferedItem> offers = new HashMap<>();
            for (var e : data.getAsJsonObject("offeredItems").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                String enchant = o.has("enchant") ? o.get("enchant").getAsString() : null;
                offers.put(e.getKey(), new VillagerState.OfferedItem(
                        o.get("price").getAsInt(), o.get("count").getAsInt(), enchant));
            }
            state.restoreOfferedItems(offers);
        }
        // 需求持久化（SPEC 2.14）—— 重启后需求连续，不清零（"活着的连续感"）
        if (data.has("needs") && data.get("needs").isJsonObject()) {
            Map<String, Double> needsMap = new HashMap<>();
            for (var e : data.getAsJsonObject("needs").entrySet()) {
                needsMap.put(e.getKey(), e.getValue().getAsDouble());
            }
            state.restoreNeeds(needsMap);
        }
        // 权限等级持久化 —— 神使授予跨重启保留（撤销才收回）
        if (data.has("rank")) {
            state.setRank(com.howard.mcai.security.NpcRank.fromString(data.get("rank").getAsString()));
        }
        // 买卖等级持久化 —— 出生等级与累计成交笔数跨重启保留；老存档无字段 →
        // 保留构造时抽的出生等级（等效于给老村民补抽一次出生等级）
        if (data.has("tradeBornLevel") || data.has("tradeCount")) {
            state.restoreTrade(
                    data.has("tradeBornLevel") ? data.get("tradeBornLevel").getAsInt() : state.tradeBornLevel(),
                    data.has("tradeCount") ? data.get("tradeCount").getAsInt() : state.tradeCount());
        }
        // NPC 库存持久化 —— 送礼/放置/装备的账面物资跨重启保留
        if (data.has("inventory") && data.get("inventory").isJsonObject()) {
            Map<String, Integer> inv = new HashMap<>();
            for (var e : data.getAsJsonObject("inventory").entrySet()) {
                inv.put(e.getKey(), e.getValue().getAsInt());
            }
            state.restoreInventory(inv);
        }
        // 背包是否已按职业投放 —— 旧档无该字段 → 保持 false，由 loadVillager 补发一次
        state.setInventorySeeded(data.has("inventorySeeded") && data.get("inventorySeeded").getAsBoolean());
        // 启动钱是否已发 —— 同上，旧档无该字段 → false，loadVillager 补发一次
        state.setWalletSeeded(data.has("walletSeeded") && data.get("walletSeeded").getAsBoolean());
        // v0.1.3 钱包扩容是否已执行 —— 旧档无该字段 → false，loadVillager 补足一次
        state.setWalletV013Boosted(data.has("walletBoostV013") && data.get("walletBoostV013").getAsBoolean());
        // 遇见过哪些玩家（首次见面判定，UUID→名字）—— 旧档无 → 空表，首次互动时补记
        if (data.has("metPlayers") && data.get("metPlayers").isJsonObject()) {
            Map<String, String> met = new HashMap<>();
            for (var e : data.getAsJsonObject("metPlayers").entrySet()) {
                met.put(e.getKey(), e.getValue().getAsString());
            }
            state.restoreMetPlayers(met);
        }
        // 上次对话结束时间（开场说明定档用）—— 旧档无此字段 → 空表，下次对话结束时补记
        if (data.has("lastConversationEnds") && data.get("lastConversationEnds").isJsonObject()) {
            Map<String, Long> ends = new HashMap<>();
            for (var e : data.getAsJsonObject("lastConversationEnds").entrySet()) {
                try {
                    ends.put(e.getKey(), e.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // 单条脏数据跳过，不连累整档
                }
            }
            state.restoreLastConversationEnds(ends);
        }
        // 稀有赠送日账 —— 旧档无此字段 → 空表
        if (data.has("rareGiftDays") && data.get("rareGiftDays").isJsonObject()) {
            Map<String, String> days = new HashMap<>();
            for (var e : data.getAsJsonObject("rareGiftDays").entrySet()) {
                days.put(e.getKey(), e.getValue().getAsString());
            }
            state.restoreRareGiftDays(days);
        }
    }

    /** 序列化 VillagerState → JSON。state 是真源，每次写入都实时构建，杜绝脏缓存 */
    private JsonObject buildSnapshot(VillagerState state) {
        JsonObject root = new JsonObject();
        root.addProperty("villagerId", state.getUniqueId().toString());
        String name = state.entity().getCustomName();
        root.addProperty("name", name != null ? name.replaceAll("§.", "") : state.profession().zhName);

        JsonObject emotion = new JsonObject();
        emotion.addProperty("fear", state.emotion().fear());
        // 旧标量 favor 仍写（取 legacyFavor）—— 回滚到旧版 jar 时它还能读回一个中性回退值
        emotion.addProperty("favor", state.emotion().legacyFavor());
        // 按玩家分账的好感（UUID字符串 → 值）—— 新档的权威来源
        JsonObject favors = new JsonObject();
        state.emotion().favorSnapshot().forEach(favors::addProperty);
        emotion.add("favors", favors);
        root.add("emotion", emotion);

        // 性格（开放度 + 人格标签）
        root.addProperty("openness", state.openness());
        JsonArray pTags = new JsonArray();
        state.personalityTagIds().forEach(pTags::add);
        root.add("personalityTags", pTags);

        // 性别 + 家庭关系（社会关系系统）
        root.addProperty("gender", state.gender());
        JsonArray rels = new JsonArray();
        for (VillagerState.Relationship r : state.relationships()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", r.type());
            o.addProperty("targetUuid", r.targetUuid());
            o.addProperty("targetName", r.targetName());
            rels.add(o);
        }
        root.add("relationships", rels);

        root.addProperty("summary", state.summary());

        JsonArray history = new JsonArray();
        for (VillagerState.ChatEntry e : state.chatHistory()) {
            JsonObject o = new JsonObject();
            o.addProperty("role", e.role());
            o.addProperty("content", e.content());
            history.add(o);
        }
        root.add("chatHistory", history);

        // facts 序列化成对象数组 [{text, ts, gossip}] —— 时间戳支撑 7 天遗忘、gossip 支撑大嘴巴豁免；
        // 读取侧仍兼容旧版纯字符串数组（见 restore）
        JsonArray facts = new JsonArray();
        for (VillagerState.Fact f : state.factEntries()) {
            JsonObject o = new JsonObject();
            o.addProperty("text", f.text());
            o.addProperty("ts", f.ts());
            o.addProperty("gossip", f.gossipExempt());
            // owner 仅在存在时写（null = 公共知识，读侧缺省也是 null）
            if (f.ownerUuid() != null) o.addProperty("owner", f.ownerUuid());
            facts.add(o);
        }
        root.add("facts", facts);

        JsonObject prices = new JsonObject();
        state.dynamicPrices().forEach(prices::addProperty);
        root.add("dynamicPrices", prices);

        JsonObject offers = new JsonObject();
        state.offeredItems().forEach((mat, item) -> {
            JsonObject o = new JsonObject();
            o.addProperty("price", item.price());
            o.addProperty("count", item.count());
            if (item.enchant() != null) {
                o.addProperty("enchant", item.enchant());
            }
            offers.add(mat, o);
        });
        root.add("offeredItems", offers);

        // 需求（SPEC 2.14）—— 五项数值原样落盘
        JsonObject needs = new JsonObject();
        state.needs().snapshot().forEach(needs::addProperty);
        root.add("needs", needs);

        // 权限等级（神使授予持久化）+ NPC 库存（送礼/放置/装备的账面物资）+ 背包投放标记
        root.addProperty("rank", state.rank().name());
        root.addProperty("tradeBornLevel", state.tradeBornLevel());
        root.addProperty("tradeCount", state.tradeCount());
        root.addProperty("inventorySeeded", state.isInventorySeeded());
        root.addProperty("walletSeeded", state.isWalletSeeded());
        root.addProperty("walletBoostV013", state.isWalletV013Boosted());
        JsonObject inv = new JsonObject();
        state.inventorySnapshot().forEach(inv::addProperty);
        root.add("inventory", inv);
        // 遇见过哪些玩家（首次见面判定，UUID→名字）
        JsonObject met = new JsonObject();
        state.metPlayersSnapshot().forEach(met::addProperty);
        root.add("metPlayers", met);
        // 每位玩家上次对话结束的时刻（开场说明定档用，UUID字符串→毫秒时间戳）
        JsonObject lastEnds = new JsonObject();
        state.lastConversationEndsSnapshot().forEach(lastEnds::addProperty);
        root.add("lastConversationEnds", lastEnds);
        // 每位玩家上次拿到稀有赠送的日期（送礼日账防刷，UUID字符串→yyyy-MM-dd）
        JsonObject rareDays = new JsonObject();
        state.rareGiftDaysSnapshot().forEach(rareDays::addProperty);
        root.add("rareGiftDays", rareDays);

        return root;
    }

    /** 标记脏 + 调度 debounce flush。任何修改记忆的地方都应该调这个 */
    public void markDirty(UUID villagerUuid) {
        dirtySet.add(villagerUuid);
        scheduleDebounceFlush();
    }

    /** Force flush —— 服务器关闭时调，确保所有脏数据都落盘（同步执行）。 */
    public void forceFlush() {
        // 之前有 debounce 任务在等 → 取消标志（任务自己跑完发现集合空了就无操作）
        flushScheduled.set(false);
        int count = 0;
        for (UUID villagerUuid : dirtySet) {
            writeToFile(villagerUuid);
            count++;
        }
        dirtySet.clear();
        plugin.getLogger().info("记忆已落盘 " + count + " 个村民");
    }

    // ===== 内部方法 =====

    private Path getMemoryDir() {
        return Paths.get(plugin.getDataFolder().getAbsolutePath(), "memory");
    }

    /**
     * 调度 debounce flush —— debounceFlushTicks 内多次 markDirty 只写一次。
     * 用 AtomicBoolean 保证不会同时挂多个 scheduleSyncDelayedTask。
     */
    private void scheduleDebounceFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;  // 已经有任务在等，不再挂新的
        }

        // 每次调度时动态读配置（而非构造时缓存）—— /ai reload 换了新 config 对象后立即生效
        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            try {
                // 先把脏集合"取走"再写：直接 for(dirtySet)+clear() 有竞态 ——
                // 遍历和 clear 之间新 markDirty 的村民会被 clear 掉且没写盘，
                // 关服时这部分记忆就丢了。快照+removeAll 让新标记的留到下一轮。
                Set<UUID> batch = new java.util.HashSet<>(dirtySet);
                dirtySet.removeAll(batch);
                for (UUID villagerUuid : batch) {
                    writeToFile(villagerUuid);
                }
            } finally {
                flushScheduled.set(false);
            }
        }, plugin.getPluginConfig().debounceFlushTicks());

        if (taskId == -1) {
            // 调度失败（可能服务器要关了），直接同步落盘
            flushScheduled.set(false);
            forceFlush();
        }
    }

    /** 把单个村民的记忆写入 JSON 文件（atomic write：先写 .tmp 再 rename） */
    private void writeToFile(UUID villagerUuid) {
        VillagerState state = villagers.get(villagerUuid);
        if (state == null) return;  // 村民已被移除

        Path file = getMemoryDir().resolve(villagerUuid + ".json");
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, buildSnapshot(state).toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("写记忆文件失败 " + villagerUuid + ": " + e.getMessage());
        }
    }
}
