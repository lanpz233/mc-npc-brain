package com.howard.mcai.mob;

import org.bukkit.entity.LivingEntity;

import java.util.UUID;

/**
 * 非村民生物状态 —— 动物/怪物的情绪化行为数据（SPEC 2.3 翻译官模式的基础）。
 * <p>
 * 与 VillagerState 不同：动物/怪物不做 LLM 对话，只走**规则行为树**（SPEC 2.2
 * "行为树打底，LLM 只处理 10% 社交时刻"），情绪事件驱动更新：
 * <ul>
 *   <li>被打 → 动物 fear +20（逃跑）；僵尸 aggression +20 + 记仇（追击）</li>
 *   <li>喂食 → 动物 favor +5、hunger 回满</li>
 * </ul>
 * <p>
 * 纯内存态，不持久化 —— 动物/怪物是瞬态实体，chunk 卸载即消失，没有长期记忆价值。
 */
public final class MobState {

    /** Paper 实体引用 */
    private final LivingEntity entity;

    /** 物种（MobSpecies 常量）：COW / SHEEP / PIG / CHICKEN / ZOMBIE */
    private final String species;

    /** 恐惧值 0-100：动物被打/被追时升高，驱动逃跑行为 */
    private int fear;

    /** 敌意值 0-100：僵尸专用，"记仇"程度，驱动追击行为 */
    private int aggression;

    /** 好感度 -100~100：动物被喂食增加（玩家投喂 → 更亲近） */
    private double favor;

    /** 饥饿度 0-100：100=饱。喂食回满，随时间缓慢下降（动物专用） */
    private int hunger;

    /** 记仇目标玩家 UUID（僵尸）：攻击过我的玩家，我要追着他打 */
    private UUID grudgeTarget;

    /** 上次冒泡时间戳 —— 短气泡也要节流，避免同一生物每轮刷屏 */
    private long lastSoundAtMs;

    public MobState(LivingEntity entity, String species) {
        this.entity = entity;
        this.species = species;
        this.fear = 0;
        this.aggression = 0;
        this.favor = 50.0;
        this.hunger = 80;
    }

    // ===== Getters =====

    public LivingEntity entity() {
        return entity;
    }

    public String species() {
        return species;
    }

    public int fear() {
        return fear;
    }

    public int aggression() {
        return aggression;
    }

    public double favor() {
        return favor;
    }

    public int hunger() {
        return hunger;
    }

    public UUID grudgeTarget() {
        return grudgeTarget;
    }

    // ===== 事件驱动更新 =====

    /** 被玩家打：动物加恐惧，僵尸加敌意并记仇 */
    public void markAttacked(UUID attacker) {
        if (MobSpecies.ZOMBIE.equals(species)) {
            aggression = Math.min(100, aggression + 20);
            grudgeTarget = attacker;
        } else {
            fear = Math.min(100, fear + 20);
        }
    }

    /** 被玩家喂食：好感 +5、饥饿回满、恐惧安抚 */
    public void markFed() {
        favor = Math.max(-100, Math.min(100, favor + 5));
        hunger = 100;
        fear = Math.max(0, fear - 10);
    }

    /** 每轮（3 秒）衰减：恐惧/敌意缓慢消退 —— 模拟"气消了"，避免永久记仇 */
    public void decay() {
        fear = Math.max(0, fear - 2);
        aggression = Math.max(0, aggression - 3);
        hunger = Math.max(0, hunger - 1);
        if (aggression == 0) {
            grudgeTarget = null;
        }
    }

    /**
     * 气泡节流：距上次冒泡超过 cooldownMs 才允许再冒（并刷新计时）。
     * 逃跑/空闲/咆哮各有不同冷却，防止高频刷屏。
     *
     * @return true = 可以冒泡
     */
    public boolean trySound(long cooldownMs) {
        long now = System.currentTimeMillis();
        if (now - lastSoundAtMs < cooldownMs) return false;
        lastSoundAtMs = now;
        return true;
    }
}
