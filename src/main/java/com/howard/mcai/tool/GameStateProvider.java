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
}
