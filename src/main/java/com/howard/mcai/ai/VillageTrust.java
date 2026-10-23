package com.howard.mcai.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 村庄信任账 —— “这个村子对某个玩家的整体信任”，按（玩家 × 村）记一本账。
 * <p>
 * 起因（Fong 2026-10-22 设计）：偷窃被抓现行不能只是当事村民掉好感——
 * 事情会在村里传开，抓得多了整个村都提防你。这本账先由偷窃接入，
 * 以后的救急系统（被救多了信任降、太低不救）共用同一本。
 * <p>
 * “村”怎么划：插件没有原版村庄边界概念，这里用锚点制——第一次在某地
 * 发生记账事件，就以那儿为锚建一个“村”（半径 {@link #VILLAGE_RADIUS} 格内
 * 的村民都归这个村）；之后村民按自己位置找最近的锚。锚点随账一起落盘。
 * <p>
 * 分数 0~100，陌生人从 {@link #DEFAULT_SCORE} 起。被抓：-12，有醒着的
 * 目击者传得更快：-18。赎回：在村里做成买卖慢慢涨（+1/笔、每天最多 +3），
 * 涨得比掉得慢——名声臭了要花很久洗，这是故意的。
 * <p>
 * 纯数据类（不碰 Bukkit 实体），可单测；落盘由 McaiPlugin 负责（gson JSON）。
 */
public final class VillageTrust {

    /** 村的半径：村民离锚点这个距离内算同一个村 */
    public static final double VILLAGE_RADIUS = 64.0;
    /** 陌生人初始信任 */
    public static final int DEFAULT_SCORE = 50;
    /** 被抓一次的基础扣分 */
    public static final int CAUGHT_PENALTY = 12;
    /** 有目击者时的额外扣分（传得更快） */
    public static final int WITNESS_EXTRA = 6;
    /** 每天靠做买卖最多赎回多少分 */
    public static final int DAILY_TRADE_GAIN_CAP = 3;

    public record Village(int id, String world, double x, double y, double z) {
    }

    private static final class Entry {
        int score = DEFAULT_SCORE;
        int caughtCount = 0;
        int robbedCount = 0;
        long gainDay = Long.MIN_VALUE;
        int gainToday = 0;
    }

    private final List<Village> villages = new ArrayList<>();
    private final Map<String, Entry> entries = new HashMap<>();
    private int nextId = 1;

    private static String key(UUID player, int villageId) {
        return player + "@" + villageId;
    }

    /**
     * 按位置找村：最近且在半径内的既有村；没有就以此处为锚新建一个。
     * 记账事件（被抓、成交）发生时调用——村是被“事情”定义出来的。
     */
    public synchronized int villageIdFor(String world, double x, double y, double z) {
        Village best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Village v : villages) {
            if (!v.world().equals(world)) continue;
            double dx = v.x() - x, dz = v.z() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = v;
            }
        }
        if (best != null && bestD2 <= VILLAGE_RADIUS * VILLAGE_RADIUS) return best.id();
        Village created = new Village(nextId++, world, x, y, z);
        villages.add(created);
        return created.id();
    }

    /** 只查不建：这个位置属于哪个村（没有返回 -1）——状态卡展示用，不该凭空建村 */
    public synchronized int findVillageId(String world, double x, double z) {
        Village best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Village v : villages) {
            if (!v.world().equals(world)) continue;
            double dx = v.x() - x, dz = v.z() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = v;
            }
        }
        return best != null && bestD2 <= VILLAGE_RADIUS * VILLAGE_RADIUS ? best.id() : -1;
    }

    public synchronized int score(UUID player, int villageId) {
        Entry e = entries.get(key(player, villageId));
        return e == null ? DEFAULT_SCORE : e.score;
    }

    public synchronized int caughtCount(UUID player, int villageId) {
        Entry e = entries.get(key(player, villageId));
        return e == null ? 0 : e.caughtCount;
    }

    /**
     * 记一次“被抓现行”：扣分 + 累计次数。
     *
     * @return 扣完之后的信任分
     */
    public synchronized int onCaught(UUID player, int villageId, boolean witnessed) {
        Entry e = entries.computeIfAbsent(key(player, villageId), k -> new Entry());
        int delta = CAUGHT_PENALTY + (witnessed ? WITNESS_EXTRA : 0);
        e.score = Math.max(0, e.score - delta);
        e.caughtCount++;
        return e.score;
    }

    /** 打劫得手一次的信任扣分 —— 比偷窃被抓更招恨：人家是明抢 */
    public static final int ROBBED_PENALTY = 20;

    public synchronized int robbedCount(UUID player, int villageId) {
        Entry e = entries.get(key(player, villageId));
        return e == null ? 0 : e.robbedCount;
    }

    /**
     * 记一次“被抢成功”：信任大跌 + 累计被抢次数。
     *
     * @return 扣完之后的信任分
     */
    public synchronized int onRobbed(UUID player, int villageId) {
        Entry e = entries.computeIfAbsent(key(player, villageId), k -> new Entry());
        e.score = Math.max(0, e.score - ROBBED_PENALTY);
        e.robbedCount++;
        return e.score;
    }

    /**
     * 记一次做成的买卖（赎回）：+1，每天最多 {@link #DAILY_TRADE_GAIN_CAP} 分。
     *
     * @param gameDay 世界游戏日（fullTime/24000），由调用方给——本类不读世界时钟
     */
    public synchronized void onTrade(UUID player, int villageId, long gameDay) {
        Entry e = entries.computeIfAbsent(key(player, villageId), k -> new Entry());
        if (e.gainDay != gameDay) {
            e.gainDay = gameDay;
            e.gainToday = 0;
        }
        if (e.gainToday >= DAILY_TRADE_GAIN_CAP) return;
        if (e.score >= 100) return;
        e.score++;
        e.gainToday++;
    }

    /** 信任分 → 人话档位（状态卡/台词提示用） */
    public static String tierZh(int score) {
        if (score >= 75) return "深受信任";
        if (score >= 55) return "街坊邻里";
        if (score >= 35) return "有点生分";
        if (score >= 15) return "村里人对他有看法";
        return "人人提防";
    }

    // ===== 序列化（village-trust.json） =====

    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("nextId", nextId);
        JsonArray vs = new JsonArray();
        for (Village v : villages) {
            JsonObject o = new JsonObject();
            o.addProperty("id", v.id());
            o.addProperty("world", v.world());
            o.addProperty("x", v.x());
            o.addProperty("y", v.y());
            o.addProperty("z", v.z());
            vs.add(o);
        }
        root.add("villages", vs);
        JsonObject es = new JsonObject();
        for (Map.Entry<String, Entry> en : entries.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("score", en.getValue().score);
            o.addProperty("caught", en.getValue().caughtCount);
            o.addProperty("robbed", en.getValue().robbedCount);
            o.addProperty("gainDay", en.getValue().gainDay);
            o.addProperty("gainToday", en.getValue().gainToday);
            es.add(en.getKey(), o);
        }
        root.add("entries", es);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** 读盘恢复；坏内容返回 null（调用方当新账本处理） */
    public static VillageTrust fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            VillageTrust t = new VillageTrust();
            t.nextId = root.has("nextId") ? root.get("nextId").getAsInt() : 1;
            if (root.has("villages")) {
                for (var el : root.getAsJsonArray("villages")) {
                    JsonObject o = el.getAsJsonObject();
                    t.villages.add(new Village(o.get("id").getAsInt(), o.get("world").getAsString(),
                            o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()));
                }
            }
            if (root.has("entries")) {
                JsonObject es = root.getAsJsonObject("entries");
                for (String k : es.keySet()) {
                    JsonObject o = es.getAsJsonObject(k);
                    Entry e = new Entry();
                    e.score = o.get("score").getAsInt();
                    e.caughtCount = o.has("caught") ? o.get("caught").getAsInt() : 0;
                    e.robbedCount = o.has("robbed") ? o.get("robbed").getAsInt() : 0;
                    e.gainDay = o.has("gainDay") ? o.get("gainDay").getAsLong() : Long.MIN_VALUE;
                    e.gainToday = o.has("gainToday") ? o.get("gainToday").getAsInt() : 0;
                    t.entries.put(k, e);
                }
            }
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
