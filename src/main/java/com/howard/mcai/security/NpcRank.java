package com.howard.mcai.security;

/**
 * NPC 权限三档（SPEC 2.14）—— 平民 / 亲信 / 神使。
 * <ul>
 *   <li>{@link #CIVILIAN} 平民（默认）：2.13 白名单 —— 职业限定破坏、送礼看库存、传送只传自己</li>
 *   <li>{@link #CONFIDANT} 亲信：与玩家 favor ≥ 阈值（默认 60）时自动获得 ——
 *       可进入该玩家领地、使用其工作台/箱子（只许用，不许拿走 rare 以上物品）</li>
 *   <li>{@link #ENVOY} 神使：玩家用指令/仪式授予、可撤销 —— 完整权限（破坏任意方块、
 *       传送任意实体、刷物品），所有操作写 audit.log 审计</li>
 * </ul>
 * 神使是持久化字段（记忆文件 rank），亲信是 favor 派生的动态身份（不持久化）。
 */
public enum NpcRank {

    CIVILIAN("平民"),
    CONFIDANT("亲信"),
    ENVOY("神使");

    private final String zhName;

    NpcRank(String zhName) {
        this.zhName = zhName;
    }

    public String zhName() {
        return zhName;
    }

    /** 记忆文件反序列化用：非法值回退平民（防止脏数据直接刷出神使） */
    public static NpcRank fromString(String s) {
        if ("ENVOY".equalsIgnoreCase(s)) return ENVOY;
        if ("CONFIDANT".equalsIgnoreCase(s)) return CONFIDANT;
        return CIVILIAN;
    }
}
