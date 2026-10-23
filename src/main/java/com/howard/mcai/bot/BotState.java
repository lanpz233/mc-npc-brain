package com.howard.mcai.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 假人（Leaves /bot）的身份与长期状态 —— 与 VillagerState 平行的一套轻量档案。
 * <p>
 * 假人的身体（血/饿/背包/位置）是真的玩家实体，插件直接从 Bukkit 读，不在这里存；
 * 这里只存"这个人是谁"：账号名、中文名、性格、好感、记性、据点、死过几次。
 * Gson 按字段序列化（见 BotStore），字段名即存档键，别随意改名。
 */
public final class BotState {

    /** Leaves 账号名（英文，游戏规则 [A-Za-z0-9_]{3,16}），全服唯一，招募时定死。 */
    public String accountName;
    /** 中文名（名字库生成），聊天与面板里用这个称呼他。 */
    public String chineseName;
    /** 性别："male" / "female"（名字库口径）。 */
    public String gender;
    /** 招募时用的皮肤来源名（借哪个真实玩家名的皮肤）。 */
    public String skinName;
    /** 人格标签 id 列表（PersonalityTagLibrary 抽取，出生抽一次不变）。 */
    public List<String> personalityTagIds = new ArrayList<>();
    /** 对各玩家的好感（-100~100，陌生 0 起步，与村民同口径）。 */
    public Map<String, Double> favorByPlayer = new LinkedHashMap<>();
    /** 恐惧 0~100（挨打/被威胁涨，慢慢回落）。 */
    public double fear;
    /** 记住的事（短句，新的在后，最多留 12 条）。 */
    public List<String> facts = new ArrayList<>();
    /** 最近对话（"玩家名: 话" 形式，最多 10 条，给模型续话用）。 */
    public List<String> recentChat = new ArrayList<>();

    /** 据点（床边站位）：世界名 + 坐标；null = 还没据点（跟人走/原地待命）。 */
    public String baseWorld;
    public double baseX, baseY, baseZ;
    /** 据点箱子坐标（整数格）；hasChest=false 表示还没箱子（存货先攒背包）。 */
    public boolean hasChest;
    public String chestWorld;
    public int chestX, chestY, chestZ;

    /** 累计死亡次数（死了档案不删，同名再招募算本人回来）。 */
    public int deaths;
    /** 出生（首次招募）的游戏日。 */
    public long bornDay = -1;
    /** 上次被插件认领到的现实时间戳（离线太久的档案面板里标灰）。 */
    public long lastSeenMillis;

    public BotState() { /* Gson */ }

    public BotState(String accountName, String chineseName, String gender, String skinName) {
        this.accountName = accountName;
        this.chineseName = chineseName;
        this.gender = gender;
        this.skinName = skinName;
    }

    public double favor(UUID player) {
        Double v = favorByPlayer.get(player.toString());
        return v == null ? 0.0 : v;
    }

    public void addFavor(UUID player, double delta) {
        double v = favor(player) + delta;
        v = Math.max(-100.0, Math.min(100.0, v));
        favorByPlayer.put(player.toString(), v);
    }

    public void addFact(String fact) {
        if (fact == null || fact.isBlank()) return;
        facts.add(fact.trim());
        while (facts.size() > 12) facts.remove(0);
    }

    public void pushChat(String line) {
        if (line == null || line.isBlank()) return;
        recentChat.add(line.trim());
        while (recentChat.size() > 10) recentChat.remove(0);
    }

    public boolean hasBase() { return baseWorld != null; }

    public void setBase(String world, double x, double y, double z) {
        baseWorld = world; baseX = x; baseY = y; baseZ = z;
    }

    public void setChest(String world, int x, int y, int z) {
        hasChest = true; chestWorld = world; chestX = x; chestY = y; chestZ = z;
    }
}
