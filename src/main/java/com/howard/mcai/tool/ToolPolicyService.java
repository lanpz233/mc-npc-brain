package com.howard.mcai.tool;

import com.howard.mcai.security.NpcRank;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 工具调用策略：按调用方、NPC 状态、风险等级决定是否允许执行。
 * <p>
 * 这里是"模型不能绕过权限"的统一闸门。外部 HTTP/MCP 适配层也必须经过同一个实例。
 * 具体规则保持最小：调用方白名单、NPC 存在性、玩家上下文、对话移动锁、高风险等级。
 */
public final class ToolPolicyService {

    public record Decision(boolean allowed, ToolResult.ErrorCode code, String message) {
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision deny(ToolResult.ErrorCode code, String message) {
            return new Decision(false, code, message);
        }
    }

    private final Predicate<UUID> npcExists;
    private final Predicate<UUID> npcInConversation;
    private final BiFunction<UUID, UUID, NpcRank> rankOf;

    /**
     * @param npcExists          NPC 是否存在且实体有效
     * @param npcInConversation  NPC 是否正在与玩家对话
     * @param rankOf             (npcId, playerId) → 该玩家对此 NPC 的权限等级，允许返回 null
     */
    public ToolPolicyService(Predicate<UUID> npcExists,
                             Predicate<UUID> npcInConversation,
                             BiFunction<UUID, UUID, NpcRank> rankOf) {
        this.npcExists = npcExists;
        this.npcInConversation = npcInConversation;
        this.rankOf = rankOf;
    }

    public Decision check(ToolRequest request, NpcTool tool) {
        if (request == null || tool == null) {
            return Decision.deny(ToolResult.ErrorCode.INVALID_REQUEST, "请求或工具为空");
        }
        if (!tool.allowedCallers().contains(request.callerType())) {
            return Decision.deny(ToolResult.ErrorCode.TOOL_NOT_ALLOWED,
                    "调用方 " + request.callerType() + " 无权使用工具 " + tool.name());
        }
        UUID npcId = request.npcId();
        if (npcId == null) {
            return Decision.deny(ToolResult.ErrorCode.INVALID_REQUEST, "缺少 npc_id");
        }
        if (!npcExists.test(npcId)) {
            return Decision.deny(ToolResult.ErrorCode.NPC_NOT_FOUND,
                    "NPC 不存在或实体已失效: " + npcId);
        }
        if (tool.requiresPlayer() && request.playerUuid() == null) {
            return Decision.deny(ToolResult.ErrorCode.INVALID_REQUEST,
                    "工具 " + tool.name() + " 需要玩家上下文");
        }
        if (!tool.allowedWhileConversing() && npcInConversation.test(npcId)) {
            return Decision.deny(ToolResult.ErrorCode.TOOL_NOT_ALLOWED,
                    "NPC 正在对话中，工具 " + tool.name() + " 暂不可用");
        }
        if (tool.risk() == NpcTool.Risk.HIGH) {
            NpcRank rank = rankOf.apply(npcId, request.playerUuid());
            if (rank != NpcRank.ENVOY) {
                return Decision.deny(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED,
                        "高风险工具 " + tool.name() + " 需要神使权限");
            }
        }
        return Decision.allow();
    }
}
