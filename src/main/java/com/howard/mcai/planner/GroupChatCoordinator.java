package com.howard.mcai.planner;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.bubble.BubbleManager;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家群体聊天协调器（混合方案：点名才回）。
 * <p>
 * 玩家发消息时，附近（groupChatRadius，默认 12 格）的其他 AI 村民：
 * <ol>
 *   <li><b>规则附和</b>（不调 LLM）：按关键词挑一句反应句冒气泡，每个 NPC 15s 冷却防刷屏；</li>
 *   <li><b>点名才回</b>（LLM）：消息点名某 NPC 的名字，或说"大家/你们/各位" → 被点名的 NPC
 *       单独调一次 LLM 短回复（一次性，<b>不写入该 NPC 的 chatHistory</b>，
 *       避免污染记忆与 user↔assistant 交替顺序）。</li>
 * </ol>
 * 线程模型：由 PlayerChatListener 在主线程 runTask 里调用；LLM 调用异步，回主线程冒泡。
 */
public final class GroupChatCoordinator {

    private final McaiPlugin plugin;
    private final Map<UUID, VillagerState> villagers;
    private final BubbleManager bubbleManager;

    /** 每个 NPC 的规则附和冷却（毫秒）—— 防止连续每条消息都全员冒泡刷屏 */
    private final Map<UUID, Long> reactionCooldown = new HashMap<>();
    private static final long REACTION_COOLDOWN_MS = 15_000L;

    /** 单条消息最多让几个 NPC 走 LLM 点名回复（防一次消息排队 4-5 个 LLM 请求） */
    private static final int MAX_NAMED_REPLIES = 2;

    /** 说"大家/你们/各位"时，回复者必须站在玩家身边 6 格内 —— 近处才有群体感，远处的别乱搭话 */
    private static final double GROUP_RESPOND_RADIUS = 6.0;

    /** 群聊回复时 NPC 站定不动的时长（10 秒），之后若无对话恢复原版 AI */
    private static final long STAND_STILL_TICKS = 200L;

    public GroupChatCoordinator(McaiPlugin plugin, Map<UUID, VillagerState> villagers, BubbleManager bubbleManager) {
        this.plugin = plugin;
        this.villagers = villagers;
        this.bubbleManager = bubbleManager;
    }

    /**
     * 玩家群聊入口 —— 玩家发消息后调用（主线程）。
     *
     * @param player   发消息的玩家
     * @param message  玩家消息原文
     * @param primary  正在和玩家对话的主 NPC（排除在附和名单外，避免重复气泡）
     */
    public void handleGroupChat(Player player, String message, VillagerState primary) {
        if (!plugin.getPluginConfig().groupChatEnabled()) return;
        double radius = plugin.getPluginConfig().groupChatRadius();

        // 收集玩家附近的其他 AI 村民（排除主 NPC）
        List<VillagerState> near = new ArrayList<>();
        for (VillagerState vs : villagers.values()) {
            if (vs == primary) continue;
            Villager v = vs.entity();
            if (!v.isValid()) continue;
            // 跨世界不能直接 distance（不同 world 的 Location.distance 会抛
            // IllegalArgumentException），先比对世界：不同世界视为"不在范围内"
            if (v.getWorld().equals(player.getWorld())
                    && v.getLocation().distance(player.getLocation()) <= radius) near.add(vs);
        }
        if (near.isEmpty()) return;

        long now = System.currentTimeMillis();

        // === 0. 先算"被点名"名单 ===
        // 必须在选附和 speaker 之前算：被点名的 NPC 后面要走链式 LLM 回复，
        // 若它同时被选成附和 speaker，同一条消息里会又附和又链式回复、一口气双发言
        List<VillagerState> named = new ArrayList<>();
        for (VillagerState vs : near) {
            if (message.contains(Smalltalk.nameOf(vs))) {
                named.add(vs);
                if (named.size() >= MAX_NAMED_REPLIES) break;
            }
        }
        Set<UUID> namedIds = new HashSet<>();
        for (VillagerState vs : named) namedIds.add(vs.getUniqueId());

        // === 1. 规则附和（最多 1 人说话 + 其余做小动作） ===
        // 之前全员冒附和气泡 → 一群人说同质的模板句，既不着边又重复。
        // 现在只挑最近的一个 NPC 说话（延迟 3.5s，等主 NPC 的 LLM 回复先上屏，
        // 营造"听了再回应"的节奏），其余旁观者只轻跳一下（有反应但不重复说话）。
        VillagerState speaker = null;
        double bestDist = Double.MAX_VALUE;
        for (VillagerState vs : near) {
            // 被点名的 NPC 让给链式回复，不再兼附和 —— 防同一 NPC 双回复
            if (namedIds.contains(vs.getUniqueId())) continue;
            Long last = reactionCooldown.get(vs.getUniqueId());
            if (last != null && now - last < REACTION_COOLDOWN_MS) continue;
            // 忙着的 NPC 让位（正在 LLM 回复中 / 正在和玩家对话）
            if (vs.llmBusy() || vs.interactingPlayer() != null) continue;
            if (!vs.entity().isValid()) continue;
            double d = vs.entity().getLocation().distanceSquared(player.getLocation());
            if (d < bestDist) {
                bestDist = d;
                speaker = vs;
            }
        }
        if (speaker != null) {
            VillagerState vs = speaker;
            String npcName = Smalltalk.nameOf(vs);
            // 主 NPC（玩家正在对话的对象）最近一条回复 —— 让旁观者"听到了对话"再接话，而非在真空里反应
            String primaryLine = lastAssistantLine(primary);
            // 延迟 3.5s 等主 NPC 的回复上屏后再发起 LLM 附和；失败时用规则兜底
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String userContent = "[场景] " + PromptBuilder.compactScene(vs) + "。\n"
                        + "[旁观附和] 你听到了旅者 " + player.getName() + " 说: " + message;
                if (primaryLine != null) {
                    userContent += "\n刚又听到 " + Smalltalk.nameOf(primary) + " 说: " + primaryLine;
                }
                userContent += "\n你离得最近，随口接一句中文（≤8字，自然口语，别用括号动作、别复述原话、别反问）。";
                List<Map<String, String>> messages = List.of(
                        Map.of("role", "system", "content",
                                "你是" + npcName + "，" + vs.profession().zhName
                                        + "。你现在只是旁听了一句村里的对话，随口接一句就好，别长篇大论。"),
                        Map.of("role", "user", "content", userContent)
                );
                plugin.getLlmClient().chat(messages, List.of())
                        .thenAccept(response -> {
                            String line = Smalltalk.stripToolCall(response.content());
                            if (line == null || line.isBlank()) line = Smalltalk.reactToPlayerMessage(message, npcName);
                            if (line.length() > 30) line = line.substring(0, 30);
                            String finalLine = line;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (vs.entity().isValid()) {
                                    // 旁听附和也写入近期社交上下文：玩家切过来对话时能衔接
                                    vs.addRecentSocialLine(finalLine);
                                    bubbleManager.showDialog(vs.entity(), npcName, player.getName(), finalLine);
                                }
                            });
                        })
                        .exceptionally(ex -> {
                            // LLM 挂了 → 规则兜底，不报空气泡
                            String fallback = Smalltalk.reactToPlayerMessage(message, npcName);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (vs.entity().isValid()) {
                                    bubbleManager.showDialog(vs.entity(), npcName, player.getName(), fallback);
                                }
                            });
                            plugin.getLogger().warning("旁观附和 LLM 失败，规则兜底: " + ex.getMessage());
                            return null;
                        });
            }, 70L);
            reactionCooldown.put(speaker.getUniqueId(), now);
        }
        // 其余旁观者：轻跳一下表示在听（无文字，避免同质气泡刷屏）
        for (VillagerState vs : near) {
            if (vs == speaker) continue;
            Long last = reactionCooldown.get(vs.getUniqueId());
            if (last != null && now - last < REACTION_COOLDOWN_MS) continue;
            if (vs.llmBusy() || vs.interactingPlayer() != null) continue;
            Villager v = vs.entity();
            if (!v.isValid()) continue;
            reactionCooldown.put(vs.getUniqueId(), now);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (v.isValid()) {
                    v.setVelocity(v.getVelocity().add(new org.bukkit.util.Vector(0, 0.35, 0)));
                }
            }, 50L);
        }

        // === 2. 点名 → LLM 一次性回复 ===
        // （named 名单已在第 0 步算好，供附和选人排除）
        // 说了"大家/你们/各位"但没点名 → 让身边 6 格内的最近一人接话（范围外的不搭话）
        boolean groupWord = message.contains("大家") || message.contains("你们") || message.contains("各位");
        if (named.isEmpty() && groupWord) {
            VillagerState closest = null;
            double best = GROUP_RESPOND_RADIUS;
            for (VillagerState vs : near) {
                double d = vs.entity().getLocation().distance(player.getLocation());
                if (d < best) {
                    best = d;
                    closest = vs;
                }
            }
            if (closest != null) named.add(closest);
        }
        // === 3. LLM 回复（串行链式） ===
        // 过滤掉忙中的，剩下的按顺序一人一句：每个人都能看到前面的人说了什么，
        // 顺着话题接，避免"各说各的"
        List<VillagerState> responders = new ArrayList<>();
        for (VillagerState vs : named) {
            if (vs.llmBusy() || vs.interactingPlayer() != null) continue;  // 忙中让位
            responders.add(vs);
        }
        if (!responders.isEmpty()) {
            oneShotReplyChain(player, message, responders, 0, null, null);
        }
    }

    /**
     * 取主 NPC（玩家正在对话的对象）最近一条 assistant 台词，供旁观附和注入参考；
     * 该 NPC 不在对话中则返回 null。
     */
    private static String lastAssistantLine(VillagerState primary) {
        if (primary == null) return null;
        var hist = primary.chatHistory();
        if (hist == null || hist.isEmpty()) return null;
        for (int i = hist.size() - 1; i >= 0; i--) {
            if ("assistant".equals(hist.get(i).role())) {
                return hist.get(i).content();
            }
        }
        return null;
    }

    /**
     * 串行链式一次性回复 —— 与 {@link #startAmbientTurn} 同构：当前 NPC 回复上屏后，
     * 把"刚才 XX 说了什么"拼进下一个的 prompt 让它顺着接，形成真的对话而非各说各的。
     * <p>
     * <b>身份锚定</b>：上一位的名字会出现在 prompt 里，量化模型跟随差，容易把自己说成上一位
     * （实测出现过 NPC 自称"俺叫XX"却实为另一个人）。所以注入时显式钉死"你是自己、别混淆"。
     *
     * @param idx       当前第几个（0 起）
     * @param lastLine  上一位说的话，null 表示这是第一个
     * @param lastName  上一位的名字（和 lastLine 成对出现）
     */
    private void oneShotReplyChain(Player player, String message, List<VillagerState> responders,
                                   int idx, String lastLine, String lastName) {
        if (idx >= responders.size()) return;
        VillagerState vs = responders.get(idx);
        String npcName = Smalltalk.nameOf(vs);

        String userContent = "[场景] " + PromptBuilder.compactScene(vs) + "。\n"
                + "[群体对话] 旅者 " + player.getName() + " 对大家说: " + message
                + "。你被点名了，简短地接一句中文（≤20字），自然口语，别用列表，别复述原话。";
        if (lastLine != null) {
            userContent += "\n（你是【" + npcName + "】本人。刚才是【" + lastName + "】说了：\""
                    + lastLine + "\"。顺着这个话题接，但别把自己当成他、别改口自称别的名字。）";
        }
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system",
                        "content", PromptBuilder.buildVillagerSystemPrompt(vs.profession(), npcName, vs.personalityTagIds(), vs)),
                Map.of("role", "user", "content", userContent)
        );
        plugin.getLlmClient().chat(messages, List.of())
                .thenAccept(response -> {
                    // 硬约束：禁止硬编码兜底台词 —— 模型没给内容就透传 "..."（结构占位），
                    // 不再编"（嗯嗯）"假台词；气泡/聊天框在下方按 "..." 跳过显示
                    String line = Smalltalk.stripToolCall(response.content());
                    if (line == null || line.isBlank()) line = "...";
                    if (line.length() > 40) line = line.substring(0, 40);
                    String finalLine = line;
                    // 回主线程：气泡 + 玩家聊天框；回复时站定不动（减少移动）
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (vs.entity().isValid()) {
                            // 标记"刚有群聊回复"—— ambient 自动闲聊让位 15s，别覆盖这条回复气泡
                            plugin.markGroupReplyAt();
                            // 台词写入近期社交上下文 —— 玩家切换到跟这个 NPC 正式对话时，
                            // prompt 能注入"刚才你在群里说过…"让模型自然衔接，不重新寒暄推销
                            vs.addRecentSocialLine(finalLine);
                            // 模型没说话（占位 "..."）→ 没有可显示的内容：
                            // 不冒气泡、不发聊天框、也不必站定；链仍继续推进
                            if (!"...".equals(finalLine)) {
                                // 冻结原版寻路让 NPC 停在原地把话说完，10 秒后若无对话恢复游荡
                                vs.entity().setAI(false);
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    if (vs.entity().isValid() && vs.interactingPlayer() == null) {
                                        vs.entity().setAI(true);
                                    }
                                    // 若这期间玩家开始跟它对话，AI 开关交给 startConversation/endConversation 管理
                                }, STAND_STILL_TICKS);
                                bubbleManager.showDialog(vs.entity(), npcName, player.getName(), finalLine);
                                if (player.isOnline()) {
                                    player.sendMessage("§6[" + npcName + ">" + player.getName() + "] §f" + finalLine);
                                }
                            }
                        }
                        // 串行推进下一位 —— 带上这位说的话，让下一位顺着接
                        oneShotReplyChain(player, message, responders, idx + 1, finalLine, npcName);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("群体点名回复失败: " + ex.getMessage());
                    // 失败的 NPC 视为"没发言"，链不能断在这里 —— 否则后续 responder 永不回复
                    // （违反群聊串行链式约束）。回主线程再推进：oneShotReplyChain 里
                    // compactScene/buildVillagerSystemPrompt 会读实体与世界状态（Bukkit API），
                    // 必须在主线程调；lastLine/lastName 保持上一位的，失败者没说过话
                    Bukkit.getScheduler().runTask(plugin, () ->
                            oneShotReplyChain(player, message, responders, idx + 1, lastLine, lastName));
                    return null;
                });
    }
}
