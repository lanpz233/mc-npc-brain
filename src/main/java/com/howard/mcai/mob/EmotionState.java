package com.howard.mcai.mob;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 情绪状态 —— 每只生物的可量化"性格"数据。
 * <p>
 * SPEC 第 2.3 节：事件驱动更新（被打 → fear+10，送礼 → favor+5）。
 * M1 简化版本：只存原始值，更新逻辑在 Step 6 事件处理器里。
 * <p>
 * 用 record + 显式 setter 方法（而非直接暴露字段）：
 * - record 本身是不可变的，但 Paper 插件里每 tick 都要改值
 * - 所以用 mutable 字段，加上 bounds 校验（-100 ~ 100）
 */
public final class EmotionState {

    /** 恐惧值：0 = 淡定，100 = 吓坏了。被玩家攻击/怪物靠近时增加，远离威胁后慢慢衰减 */
    private int fear;

    /**
     * 好感度 —— <b>按玩家独立</b>：key = 玩家 UUID，value = -100 ~ 100。
     * <p>
     * 为什么不用全局单值：一个玩家对 NPC 好、另一个玩家打过它，全局单值无法区分，
     * 会变成"全村玩家共享一份好感"（打人的和送礼的都被当成同一关系）。按玩家分账后，
     * 每个旅者对每个 NPC 各算各的关系，索要物品/亲信判定都按"当前这位玩家"来。
     */
    private final Map<UUID, Double> favorByPlayer = new HashMap<>();

    /**
     * 旧存档迁移种子 + "认不出是谁"时的回退好感。
     * <p>
     * 旧记忆文件里只有一个标量 favor —— 迁移时把它读进 legacyFavor，
     * 仅在 <b>playerUuid == null</b>（没有玩家上下文，比如 /ai list 之类的管理命令）时作为回退值。
     * 构造默认 50 = 中性偏友好。
     */
    private double legacyFavor = 50.0;

    /**
     * 陌生关系的起始好感 —— 没记录过的玩家一律从这里起步。
     * <p>
     * 旧版这里回退到 legacyFavor（默认 50），而索要门槛只有 40 → 任何新玩家一上来就"够格"
     * 要东西，"关系够好不好白给"形同虚设。改成 0 之后，好感必须靠互动（聊天/送礼/成交/教学）
     * 一点点攒，门槛才是真的门槛。
     */
    private static final double STRANGER_FAVOR = 0.0;

    /** 好感分账上限 —— 超出按"数值最低者"淘汰，防长期运行无界增长 */
    private static final int MAX_FAVOR_ENTRIES = 32;

    public EmotionState() {
        this.fear = 0;
    }

    // ===== Fear =====

    public int fear() {
        return fear;
    }

    public void addFear(int delta) {
        this.fear = clamp(fear + delta, 0, 100);
    }

    /** 直接设恐惧值（MemoryStore 从 JSON 恢复用） */
    public void setFear(int fear) {
        this.fear = clamp(fear, 0, 100);
    }

    public void decayFear() {
        this.fear = clamp(fear - 1, 0, 100);
    }

    // ===== Favor（按玩家独立） =====

    /**
     * 某玩家对该 NPC 的好感度。
     * <ul>
     *   <li>没有记录（第一次见）→ {@link #STRANGER_FAVOR}（0，陌生人）。</li>
     *   <li>playerUuid 为 null（无法辨认是谁）→ legacyFavor（保守回退，不白给）。</li>
     * </ul>
     */
    public double favor(UUID playerUuid) {
        if (playerUuid == null) return legacyFavor;
        Double v = favorByPlayer.get(playerUuid);
        return v != null ? v : STRANGER_FAVOR;
    }

    // 旧 giveChance（好感→概率、调用方掷骰子定给不给）已于 2026-10-21 删除：
    // 送东西改由 security/GiftPolicy 按确定性规矩裁决，同样的局面永远同样的结果，不再掷骰子。

    /** 累加某玩家的好感（clamp 到 -100~100）；playerUuid 为 null 时改 legacyFavor */
    public synchronized void addFavor(UUID playerUuid, double delta) {
        if (playerUuid == null) {
            legacyFavor = clamp(legacyFavor + delta, -100.0, 100.0);
            return;
        }
        favorByPlayer.put(playerUuid, clamp(favor(playerUuid) + delta, -100.0, 100.0));
        evictIfOverflow();
    }

    /** 直接设某玩家的好感（MemoryStore 从 JSON 恢复用） */
    public synchronized void setFavor(UUID playerUuid, double value) {
        if (playerUuid == null) {
            legacyFavor = clamp(value, -100.0, 100.0);
            return;
        }
        favorByPlayer.put(playerUuid, clamp(value, -100.0, 100.0));
        evictIfOverflow();
    }

    /** 旧存档标量 favor 的迁移种子（MemoryStore 恢复 / 无记录玩家回退值） */
    public void setLegacyFavor(double value) {
        this.legacyFavor = clamp(value, -100.0, 100.0);
    }

    public double legacyFavor() {
        return legacyFavor;
    }

    /** per-player 好感快照（MemoryStore 持久化 / SocialLayer 逐玩家衰减用）：UUID字符串 → 好感 */
    public synchronized Map<String, Double> favorSnapshot() {
        Map<String, Double> out = new HashMap<>();
        favorByPlayer.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }

    /** MemoryStore 从 JSON 恢复 per-player 好感（覆盖式） */
    public synchronized void restoreFavors(Map<String, Double> restored) {
        favorByPlayer.clear();
        if (restored == null) return;
        for (var e : restored.entrySet()) {
            try {
                favorByPlayer.put(UUID.fromString(e.getKey()), clamp(e.getValue(), -100.0, 100.0));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID（旧档损坏）直接跳过，不影响其它条目
            }
        }
    }

    /** 条目超上限 → 淘汰好感最低的（最疏远的那个），保证常来常往的玩家不被挤掉 */
    private void evictIfOverflow() {
        while (favorByPlayer.size() > MAX_FAVOR_ENTRIES) {
            UUID lowest = null;
            double lowestVal = Double.MAX_VALUE;
            for (var e : favorByPlayer.entrySet()) {
                if (e.getValue() < lowestVal) {
                    lowestVal = e.getValue();
                    lowest = e.getKey();
                }
            }
            if (lowest == null) break;
            favorByPlayer.remove(lowest);
        }
    }

    // ===== Utils =====

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
