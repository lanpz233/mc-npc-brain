package com.howard.mcai.ai;

/**
 * 对话开场说明（导演提示）的拼装器 —— 纯逻辑，不依赖 Bukkit，可独立单测。
 * <p>
 * 背景：旧实现每次右键开始对话都给模型发同一句写死的合成指令
 * （"just approached and wants to chat. Greet them warmly!"），于是哪怕玩家五秒前
 * 刚跟这个 NPC 聊完、误触右键结束了对话，再点开时 NPC 也会重新热情打招呼，
 * 像第一次见面 —— 记忆明明没清，是开场指令把模型带偏了。
 * <p>
 * 现在开场说明由插件按事实拼：见没见过、上次对话结束在多久之前、近期有没有
 * 聊天记录。模型收到的情况不同，开场自然不同：
 * <ul>
 *   <li>{@link Tier#FIRST_MEETING} 第一次见面 —— 才允许自我介绍；</li>
 *   <li>{@link Tier#RESUME} 刚聊完又回来（≤5 分钟）—— 明确禁止重新打招呼，
 *       按同一场对话的延续处理；</li>
 *   <li>{@link Tier#RETURN_SOON} 一小时内回来 —— 熟人式简短招呼，不自我介绍；</li>
 *   <li>{@link Tier#RETURN_TODAY} 一天内回来（或认识但无结束时间记录，
 *       如旧存档）—— 熟人招呼，不自我介绍；</li>
 *   <li>{@link Tier#LONG_ABSENCE} 超过一天 —— 好久不见的熟人寒暄，
 *       但仍不是陌生人式的自我介绍。</li>
 * </ul>
 * <p>
 * 产出的文本是一条「导演提示」而不是玩家发言：调用方必须以 systemNote 方式
 * 送入（跳过讨要/砍价/教学等玩家句式解析），且其中包含的事实（是否初见、
 * 间隔时长）全部来自插件状态，模型不得自行改写。
 */
public final class ConversationOpening {

    /** 刚聊完又回来的窗口：5 分钟内算同一场对话的延续 */
    public static final long RESUME_WITHIN_MS = 5L * 60_000L;

    /** 一小时内回来算“刚见过不久” */
    public static final long RETURN_SOON_WITHIN_MS = 60L * 60_000L;

    /** 一天内回来算“今天/近期见过” */
    public static final long RETURN_TODAY_WITHIN_MS = 24L * 60L * 60_000L;

    public enum Tier {
        FIRST_MEETING,
        RESUME,
        RETURN_SOON,
        RETURN_TODAY,
        LONG_ABSENCE
    }

    private ConversationOpening() {
    }

    /**
     * 按“是否见过 + 上次对话结束时间”定档。
     *
     * @param hasMet                  是否与该玩家互动过（VillagerState.hasMet，互动记录持久化）
     * @param lastConversationEndMs   上次与该玩家对话结束的时刻（毫秒），无记录传 ≤0
     * @param nowMs                   当前时刻（毫秒）
     */
    public static Tier tier(boolean hasMet, long lastConversationEndMs, long nowMs) {
        if (!hasMet) return Tier.FIRST_MEETING;
        // 认识但没有结束时间记录（旧存档、或只通过非对话方式见过）→ 按熟人处理，
        // 绝不能退回“第一次见面”的自我介绍。
        if (lastConversationEndMs <= 0) return Tier.RETURN_TODAY;
        long elapsed = Math.max(0L, nowMs - lastConversationEndMs);
        if (elapsed <= RESUME_WITHIN_MS) return Tier.RESUME;
        if (elapsed <= RETURN_SOON_WITHIN_MS) return Tier.RETURN_SOON;
        if (elapsed <= RETURN_TODAY_WITHIN_MS) return Tier.RETURN_TODAY;
        return Tier.LONG_ABSENCE;
    }

    /** 把间隔时长说成人话（英文，注入提示用）：less than a minute / 2 minutes / 3 hours / 2 days */
    public static String formatElapsed(long elapsedMs) {
        long ms = Math.max(0L, elapsedMs);
        if (ms < 60_000L) return "less than a minute";
        long minutes = ms / 60_000L;
        if (minutes < 60L) return minutes == 1L ? "1 minute" : minutes + " minutes";
        long hours = minutes / 60L;
        if (hours < 24L) return hours == 1L ? "1 hour" : hours + " hours";
        long days = hours / 24L;
        return days == 1L ? "1 day" : days + " days";
    }

    /**
     * 拼一条开场导演提示。
     *
     * @param playerName       玩家名
     * @param playerIdShort    玩家 ID 前 8 位（与既有合成消息的 ID 格式保持一致，
     *                         供模型按 ID 区分事实可见范围）
     * @param tier             {@link #tier} 的定档结果
     * @param elapsedMs        距上次对话结束的毫秒数，未知传负数（文案自动避开具体时长）
     * @param hasRecentHistory 该 NPC 当前是否还留有近期聊天记录（chatHistory 非空）
     */
    public static String buildNote(String playerName, String playerIdShort, Tier tier,
                                   long elapsedMs, boolean hasRecentHistory) {
        String who = "Player " + playerName + " (ID: player_" + playerIdShort + ")";
        StringBuilder sb = new StringBuilder("[Conversation note] ").append(who);
        switch (tier) {
            case FIRST_MEETING -> sb.append(" is talking to you for the FIRST time. "
                    + "Introduce yourself briefly (your name and what you do here) and greet them warmly.");
            case RESUME -> {
                sb.append(" talked with you just ").append(formatElapsed(elapsedMs))
                        .append(" ago — this is the SAME conversation continuing, not a new meeting. ")
                        .append("Do NOT greet them like a newcomer, do NOT introduce yourself again, ")
                        .append("and do NOT act like you are meeting for the first time. ")
                        .append("Pick up naturally where your recent chat left off.");
                if (hasRecentHistory) {
                    sb.append(" The chat history in this prompt is that same conversation.");
                }
            }
            case RETURN_SOON -> sb.append(" last talked with you ").append(formatElapsed(elapsedMs))
                    .append(" ago. Greet them briefly like someone you know — no self-introduction, ")
                    .append("no first-meeting talk. If your recent chat left something unfinished, ")
                    .append("you may pick it up.");
            case RETURN_TODAY -> {
                if (elapsedMs >= 0) {
                    sb.append(" last talked with you ").append(formatElapsed(elapsedMs)).append(" ago. ");
                } else {
                    sb.append(" already knows you — you have met before. ");
                }
                sb.append("Greet them like an acquaintance — no self-introduction, no first-meeting talk.");
            }
            case LONG_ABSENCE -> sb.append(" last talked with you ").append(formatElapsed(elapsedMs))
                    .append(" ago — it has been a long while. Greet them like an old acquaintance ")
                    .append("you have not seen in ages (a 'long time no see' feeling), but do NOT ")
                    .append("introduce yourself like a stranger.");
        }
        return sb.toString();
    }
}
