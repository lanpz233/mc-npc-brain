package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.NpcRank;
import com.howard.mcai.security.PermissionManager;
import com.howard.mcai.security.SwapPolicy;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * npc.accept_swap —— 同意玩家在背包界面提出的交换（统一柜版）。
 * <p>
 * 换什么、几个在玩家点击时已由代码钉死（{@link VillagerState.PendingSwap}）；
 * 值不值由 {@link SwapPolicy} 按双方总价值与好感算（交情越深 NPC 越肯吃亏），
 * 模型只有表态权：它调用本工具 = 同意，但裁决不划算时执行会被拦下；
 * 它不同意（不调用）就什么都不发生。真正转移仍由
 * {@code WorldInteractionService.swapWithPlayer} 在执行前复核双方持有后一次性完成。
 * <p>
 * 别名 {@code accept_swap} 桥接老工具名，对话模型菜单不变。
 */
public final class NpcAcceptSwapTool implements NpcTool {

    private final McaiPlugin plugin;

    public NpcAcceptSwapTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.accept_swap";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("accept_swap");
    }

    @Override
    public String description() {
        return "同意玩家在背包界面提出的交换（双方物品与数量已由系统钉死）。"
                + "值不值由插件按双方价值与好感裁决，不划算时会被拒绝；模型只负责表态与台词。";
    }

    @Override
    public Category category() {
        return Category.ACTION;
    }

    @Override
    public Risk risk() {
        return Risk.MEDIUM;
    }

    @Override
    public boolean requiresPlayer() {
        return true;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("message", ToolSchema.Param.string("同意交换时说的中文台词").maxLength(120))
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        VillagerState vs = plugin.getVillagers().get(context.npcId());
        if (vs == null) {
            return ToolResult.failed(ToolResult.ErrorCode.NPC_NOT_FOUND, "NPC 不存在");
        }
        Player player = context.playerUuid() != null ? Bukkit.getPlayer(context.playerUuid()) : null;
        if (player == null || !player.isOnline()) {
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST, "附近没有玩家可交换");
        }
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);

        VillagerState.PendingSwap swap = vs.peekPendingSwap(player.getUniqueId());
        if (swap == null) {
            // 没有待确认交换：与旧口径一致，静默无操作（CentralPlanner 据 swapped=false 处理台词）
            JsonObject data = new JsonObject();
            data.addProperty("swapped", false);
            data.addProperty("reason", "no_pending_swap");
            return ToolResult.completed(data);
        }

        // 值不值：插件裁决（双方总价值 × 好感折让）
        long playerValue = (long) PermissionManager.valueOf(swap.offerMat()) * swap.offerCount();
        long npcValue = (long) PermissionManager.valueOf(swap.npcMat()) * swap.npcCount();
        double favor = vs.emotion().favor(player.getUniqueId());
        boolean envoy = plugin.getPermissionManager().rankOf(vs, player.getUniqueId()) == NpcRank.ENVOY;
        if (!SwapPolicy.isFair(playerValue, npcValue, favor, envoy)) {
            plugin.getLogger().info("§c[MC-AI] accept_swap 拒绝(不划算): " + player.getName()
                    + " 出价价值 " + playerValue + " < " + npcName + " 物品价值 " + npcValue
                    + "（好感 " + String.format("%.0f", favor) + "，最低比例 "
                    + SwapPolicy.minRatio(favor) + "）");
            return ToolResult.failed(ToolResult.ErrorCode.CALLER_NOT_AUTHORIZED,
                    npcName + "摇摇头：" + SwapPolicy.unfairReason(favor));
        }

        // 划算 → 真正转移（执行前复核双方持有，失败不半执行）
        var result = plugin.getWorldInteraction().swapWithPlayer(vs, player);
        if (!result.success()) {
            String msg = result.error();
            if (msg == null) {
                // 交换在复核前消失（极少见竞态）：按无操作处理
                JsonObject data = new JsonObject();
                data.addProperty("swapped", false);
                data.addProperty("reason", "no_pending_swap");
                return ToolResult.completed(data);
            }
            return ToolResult.failed(ToolResult.ErrorCode.INVALID_REQUEST,
                    msg.startsWith("§e") ? msg.substring(2) : msg);
        }
        JsonObject data = new JsonObject();
        data.addProperty("swapped", true);
        data.addProperty("item", swap.npcMat().name());
        return ToolResult.completed(data);
    }
}
