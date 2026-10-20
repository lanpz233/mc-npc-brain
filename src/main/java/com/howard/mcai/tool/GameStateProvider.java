package com.howard.mcai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 服务端权威游戏状态的统一读取入口。
 * <p>
 * 对话模型、行为模型和外部协议只能通过这里读取 NPC / 世界事实，不能各自维护一份状态。
 * 所有实现都必须在 Paper 主线程调用，返回值是调用时刻的快照。
 */
public interface GameStateProvider {

    /**
     * NPC 是否存在、实体是否仍然有效。
     */
    boolean npcExists(UUID npcId);

    /**
     * NPC 状态快照；NPC 不存在时返回 null。
     *
     * @param playerUuid 互动玩家 UUID，用于读取该玩家对 NPC 的好感/权限；可为 null
     */
    JsonObject npcStatus(UUID npcId, UUID playerUuid);

    /**
     * NPC 背包快照；NPC 不存在时返回 null。
     */
    JsonObject npcInventory(UUID npcId);

    /**
     * NPC 周围的实体快照。
     *
     * @param radius 读取半径（会被限制在 0.5~32）
     * @param limit  最多返回条数（会被限制在 1~64）
     */
    JsonArray nearbyEntities(UUID npcId, double radius, int limit);

    /**
     * NPC 周围方块的数量统计（按方块类型聚合，从多到少）。
     * <p>
     * 扫描在主线程进行，所以半径有硬上限（实现里限制 16 格），避免一次调用把服务器卡住。
     *
     * @param radius 读取半径（会被限制在 1~16）
     * @param limit  最多返回多少种方块（会被限制在 1~64）
     * @return NPC 不存在时返回 null
     */
    JsonObject nearbyBlocks(UUID npcId, double radius, int limit);

    /**
     * NPC 所在世界的环境快照：生物群系、游戏内时间、时段、天气、降雪/降雨、光照。
     *
     * @return NPC 不存在时返回 null
     */
    JsonObject environment(UUID npcId);
}
