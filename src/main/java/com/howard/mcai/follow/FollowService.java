package com.howard.mcai.follow;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.NpcTask;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolRequest;
import com.howard.mcai.tool.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 跟随服务（v0.5.0）：跟随这件事的“官府”——裁定、开关、台词都在这里，
 * 入口有三个：聊天认话（CentralPlanner 转交）、F 界面按钮、模型工具（走任务本身）。
 * <p>
 * 裁定规则在 {@link FollowConsent}（纯函数）：看心情/忙不忙，被磨烦了也会答应。
 * 答应/喊停由插件当场办掉（结束对话 → 掐掉他手头任务 → 经统一柜开跟随任务），
 * 台词按偷窃骂人话的同款路子：模型有空就请它按人设说一句，忙/失败就用兜底句。
 * 拒绝（聊天触发时）不办任何事，只把裁定理由注进这轮对话，让模型按人设婉拒。
 */
public final class FollowService {

    /** 聊天“叫他跟”的认话表：宁紧勿滥，日常闲聊提到“跟”不该误触发 */
    private static final Pattern ASK_PATTERN = Pattern.compile(
            "跟我来|跟着我|跟我走|随我来|陪我走|陪我去|跟上我|你跟我来");
    /** 聊天“叫他停”的认话表（只在他真跟着你的时候才接管） */
    private static final Pattern STOP_PATTERN = Pattern.compile(
            "别跟了|不用跟了|别跟着我|不要跟了|别再跟了");

    /** 被拒记录的记忆窗口：这么久之内的拒绝都算“在磨他” */
    private static final long REFUSAL_WINDOW_MS = 90_000L;

    private final McaiPlugin plugin;
    /** npc|player → 最近的被拒时刻（内存态，重启即散） */
    private final Map<String, List<Long>> refusals = new ConcurrentHashMap<>();

    public FollowService(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    public enum ChatOutcome { NOT_FOLLOW, HANDLED, REFUSED }

    /** 聊天认话的结果：HANDLED = 插件已办完（调用方别再走模型）；REFUSED = 把 note 注进这轮消息 */
    public record ChatResult(ChatOutcome outcome, String note) {}

    private enum LineKind { AGREE, GRUDGING, STOP, ALREADY }

    // ===== 对外主入口 =====

    /** 他是不是正在跟这个玩家（标记 + 任务双核验，防标记过期误导界面） */
    public boolean isFollowing(VillagerState state, UUID playerUuid) {
        if (state == null || playerUuid == null) return false;
        if (!playerUuid.equals(state.followingPlayer())) return false;
        if (plugin.getNpcTaskManager() == null) return false;
        NpcTask active = plugin.getNpcTaskManager().activeFor(state.getUniqueId());
        return active != null && "npc.follow_player".equals(active.toolName());
    }

    /**
     * 请求跟随（F 界面按钮入口）：裁定并当场执行。
     * 答应（含被磨烦的）→ 立刻开跟随任务并说一句话；拒绝 → 记一笔“被磨”账。
     *
     * @return 裁定结果；NPC 实体不可用返回 null
     */
    public FollowConsent.Decision requestFollow(VillagerState state, Player player) {
        Villager v = state.entity();
        if (v == null || !v.isValid() || v.isDead() || player == null) return null;
        // 已经在跟我了：算答应，不重复开任务
        if (isFollowing(state, player.getUniqueId())) return FollowConsent.Decision.AGREE;
        FollowConsent.Decision d = evaluate(state, player);
        return act(state, player, d);
    }

    /** 只裁定、不执行（聊天入口要先把话记进历史再执行，保持对话顺序） */
    private FollowConsent.Decision evaluate(VillagerState state, Player player) {
        Villager v = state.entity();
        boolean followingOther = state.followingPlayer() != null
                && !state.followingPlayer().equals(player.getUniqueId())
                && isFollowing(state, state.followingPlayer());
        NpcTask active = plugin.getNpcTaskManager() != null
                ? plugin.getNpcTaskManager().activeFor(state.getUniqueId()) : null;
        double worstNeed = 0;
        for (double val : state.needs().snapshot().values()) {
            worstNeed = Math.max(worstNeed, val);
        }
        return FollowConsent.decide(v.isSleeping(), active != null, followingOther,
                state.emotion().fear(), worstNeed, countRefusals(state, player));
    }

    /** 执行裁定：答应就开任务 + 说出口；拒绝就记磨账。启动失败按“忙”回滚给调用方 */
    private FollowConsent.Decision act(VillagerState state, Player player, FollowConsent.Decision d) {
        switch (d) {
            case AGREE, AGREE_GRUDGING -> {
                if (!startFollow(state, player, d == FollowConsent.Decision.AGREE_GRUDGING)) {
                    // 任务没开成（策略拦了/实体刚失效）：当忙处理，但不记磨账
                    plugin.getLogger().warning("[跟随] 跟随任务启动失败: npc=" + state.getUniqueId()
                            + " player=" + player.getName());
                    return FollowConsent.Decision.REFUSE_BUSY;
                }
                clearRefusals(state, player);
                // 结果已定，他把答应说出口（模型现说或兜底句）——聊天与 F 界面两个入口都走这里
                speakLine(state, player,
                        d == FollowConsent.Decision.AGREE_GRUDGING ? LineKind.GRUDGING : LineKind.AGREE);
                return d;
            }
            default -> {
                recordRefusal(state, player);
                return d;
            }
        }
    }

    /** 停止跟随（F 界面取消 / 聊天喊停共用）；没在跟这个玩家返回 false */
    public boolean stopFollow(VillagerState state, Player player) {
        if (player == null || !isFollowing(state, player.getUniqueId())) return false;
        plugin.getNpcTaskManager().cancelForNpc(state.getUniqueId(), "玩家取消了跟随");
        // 取消会走任务的 onCancel 清标记；这里双保险
        if (player.getUniqueId().equals(state.followingPlayer())) {
            state.setFollowingPlayer(null);
        }
        clearRefusals(state, player);
        speakLine(state, player, LineKind.STOP);
        plugin.getLogger().info("[跟随] " + player.getName() + " 让村民停下了跟随 npc=" + state.getUniqueId());
        return true;
    }

    /**
     * 聊天认话（CentralPlanner 在走模型之前转交）：是跟随指令就接管。
     * 答应/喊停 → HANDLED（插件已办完、台词已安排，对话按需收尾，调用方直接 return）；
     * 拒绝 → REFUSED + 理由注记（调用方把 note 拼进这轮消息，让模型按人设婉拒）。
     */
    public ChatResult handleChat(VillagerState state, Player player, String message) {
        ChatResult none = new ChatResult(ChatOutcome.NOT_FOLLOW, null);
        if (state == null || player == null || message == null) return none;
        Villager v0 = state.entity();
        if (v0 == null || !v0.isValid() || v0.isDead()) return none;

        if (STOP_PATTERN.matcher(message).find()) {
            if (isFollowing(state, player.getUniqueId())) {
                state.addChatEntry("user", message);
                stopFollow(state, player);
                return new ChatResult(ChatOutcome.HANDLED, null);
            }
            return none;
        }
        if (!ASK_PATTERN.matcher(message).find()) return none;

        // 已经跟着我了还叫：回一句“跟着呢”，对话照常继续（不结束、不走完整模型轮）
        if (isFollowing(state, player.getUniqueId())) {
            state.addChatEntry("user", message);
            speakLine(state, player, LineKind.ALREADY);
            return new ChatResult(ChatOutcome.HANDLED, null);
        }
        FollowConsent.Decision d = evaluate(state, player);
        return switch (d) {
            case AGREE, AGREE_GRUDGING -> {
                // 先把玩家这句记进历史，他答应的那句才接得上顺序；拒绝时不记——
                // 调用方会把这句连同裁定注记一起记入（与砍价注记同款，避免重复两条）
                state.addChatEntry("user", message);
                FollowConsent.Decision acted = act(state, player, d);
                yield acted == d
                        ? new ChatResult(ChatOutcome.HANDLED, null)
                        : new ChatResult(ChatOutcome.REFUSED, refuseNote(acted));
            }
            default -> {
                recordRefusal(state, player);
                yield new ChatResult(ChatOutcome.REFUSED, refuseNote(d));
            }
        };
    }

    // ===== 执行 =====

    /** 开跟随任务：吵醒 → 掐掉手头任务 → 收尾对话 → 经统一柜以 PLUGIN 身份下单 */
    private boolean startFollow(VillagerState state, Player player, boolean grudging) {
        Villager v = state.entity();
        if (v == null || !v.isValid()) return false;
        if (v.isSleeping()) v.wakeup();
        if (plugin.getNpcTaskManager() != null) {
            NpcTask active = plugin.getNpcTaskManager().activeFor(state.getUniqueId());
            if (active != null) {
                plugin.getNpcTaskManager().cancel(active.id(),
                        grudging ? "被玩家磨烦了，撂下手头事去跟随" : "改去跟随玩家");
            }
        }
        // 跟随任务在对话中只会暂停站着：先把对话收尾，他才能立刻动身
        if (state.interactingPlayer() != null) {
            state.endConversation();
            plugin.getMemoryStore().markDirty(state.getUniqueId());
        }
        JsonObject args = new JsonObject();
        args.addProperty("player_id", player.getName());
        ToolRequest req = new ToolRequest(ToolRequest.PROTOCOL_VERSION,
                "follow-" + UUID.randomUUID(),
                ToolContext.CallerType.PLUGIN, "follow-service",
                state.getUniqueId(), player.getUniqueId(),
                "npc.follow_player", args);
        ToolResult result = plugin.getToolDispatcher().dispatchSync(req);
        return result != null && (result.status() == ToolResult.Status.ACCEPTED
                || result.status() == ToolResult.Status.COMPLETED);
    }

    /** 拒绝理由注记（拼进这轮玩家消息后面，模型照着婉拒，不许它嘴上答应） */
    private String refuseNote(FollowConsent.Decision d) {
        String reason = switch (d) {
            case REFUSE_BUSY -> "他手头正有事要忙，走不开";
            case REFUSE_SLEEPING -> "他正在睡觉，没醒";
            case REFUSE_SCARED -> "他现在很害怕，不敢到处乱走";
            case REFUSE_NEED -> "他有很迫切的需求没解决（饿坏了/困极了之类）";
            case REFUSE_FOLLOWING_OTHER -> "他正在跟随另一位玩家";
            default -> "他现在不方便跟人走";
        };
        return "（插件提示：玩家请你跟他走，但插件已经裁定你这次不能去——" + reason + "。"
                + "请用你自己的口气婉拒这件事，不要答应跟随、不要起身跟他走，就把话正常聊下去。）";
    }

    // ===== 磨人账 =====

    private String key(VillagerState state, Player player) {
        return state.getUniqueId() + "|" + player.getUniqueId();
    }

    private int countRefusals(VillagerState state, Player player) {
        List<Long> list = refusals.get(key(state, player));
        if (list == null) return 0;
        long now = System.currentTimeMillis();
        synchronized (list) {
            list.removeIf(t -> now - t > REFUSAL_WINDOW_MS);
            return list.size();
        }
    }

    private void recordRefusal(VillagerState state, Player player) {
        List<Long> list = refusals.computeIfAbsent(key(state, player), k -> new ArrayList<>());
        long now = System.currentTimeMillis();
        synchronized (list) {
            list.removeIf(t -> now - t > REFUSAL_WINDOW_MS);
            list.add(now);
        }
    }

    private void clearRefusals(VillagerState state, Player player) {
        refusals.remove(key(state, player));
    }

    // ===== 台词（插件定结果，模型只有表达权；忙/失败用兜底句） =====

    private static String canned(LineKind kind) {
        return switch (kind) {
            case AGREE -> "好，我跟你走。";
            case GRUDGING -> "哎呀，行行行，跟你走还不行吗，别催了。";
            case STOP -> "行，那我先回去了。";
            case ALREADY -> "我这不就跟着你呢嘛。";
        };
    }

    private static String eventText(LineKind kind, String playerName) {
        return switch (kind) {
            case AGREE -> "旅者 " + playerName + " 请你跟他一起走，你已经答应了，正要跟上。"
                    + "用你的口气说一句跟他走的话（不超过 25 个字），自然口语，只说话，不要解释。";
            case GRUDGING -> "旅者 " + playerName + " 一遍一遍催你跟他走，你被磨得不耐烦，"
                    + "只好撂下手头的事答应跟上。用你的口气不情不愿地说一句（不超过 25 个字），只说话。";
            case STOP -> "旅者 " + playerName + " 让你不用再跟着了，你停下脚步。"
                    + "用你的口气说一句道别的话（不超过 25 个字），只说话。";
            case ALREADY -> "旅者 " + playerName + " 又叫你跟他走，可你本来就在跟着他。"
                    + "用你的口气回一句（不超过 25 个字），只说话。";
        };
    }

    /**
     * 让他把已定的跟随结果说出口：气泡 + 聊天行 + 进对话历史。
     * 模型忙就直接用兜底句；不忙请它按人设现说一句（8 秒超时退兜底）。
     */
    private void speakLine(VillagerState state, Player player, LineKind kind) {
        Villager v = state.entity();
        if (v == null || !v.isValid()) return;
        String canned = canned(kind);
        if (state.llmBusy() || plugin.getLlmClient() == null) {
            deliverLine(state, player, kind, canned);
            return;
        }
        String name = Smalltalk.nameOf(state);
        var messages = List.of(
                Map.of("role", "system", "content", PromptBuilder.buildVillagerSystemPrompt(
                        state.profession(), name, state.personalityTagIds(), state)),
                Map.of("role", "user", "content", eventText(kind, player.getName())));
        plugin.getLlmClient().chat(messages, List.of())
                .orTimeout(8, TimeUnit.SECONDS)
                .whenComplete((resp, err) -> {
                    String line = null;
                    if (err == null && resp != null) {
                        line = Smalltalk.stripToolCall(resp.content());
                        if (line != null) {
                            line = line.trim();
                            if (line.length() > 40) line = line.substring(0, 40);
                            if (line.isBlank()) line = null;
                        }
                    }
                    String spoken = line;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!v.isValid() || v.isDead()) return;
                        deliverLine(state, player, kind, spoken != null ? spoken : canned);
                    });
                });
    }

    private void deliverLine(VillagerState state, Player player, LineKind kind, String line) {
        Villager v = state.entity();
        if (v == null || !v.isValid()) return;
        String name = Smalltalk.nameOf(state);
        plugin.getBubbleManager().showDialog(v, name, player.getName(), line);
        if (player.isOnline()) {
            String chat = "§6[" + name + ">" + player.getName() + "] §f" + line;
            if (com.howard.mcai.ai.ProfessionBadge.enabled()) {
                player.sendMessage(com.howard.mcai.ai.ProfessionBadge.prependBadge(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection().deserialize(chat), state.profession()));
            } else {
                player.sendMessage(chat);
            }
            if (kind == LineKind.AGREE || kind == LineKind.GRUDGING) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.0f);
            }
        }
        // 进对话历史：他说过的话下一轮聊天要接得上（动作摘要按既有约定附在句尾）
        String action = switch (kind) {
            case AGREE -> " [动作：开始跟随玩家]";
            case GRUDGING -> " [动作：被磨烦了，答应跟随玩家]";
            case STOP -> " [动作：停止跟随玩家]";
            case ALREADY -> "";
        };
        state.addChatEntry("assistant", line + action);
    }
}
