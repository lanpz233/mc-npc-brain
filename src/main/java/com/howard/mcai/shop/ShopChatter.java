package com.howard.mcai.shop;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.ai.Smalltalk;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 商店里的「说话」（Fong 2026-10-21 要求：店里也要会聊天）。
 * <p>
 * 分工照本插件的铁律——**结果由插件定死，模型只有表达权**：价、成不成、记什么事实
 * 都在裁决/结算层落定后，才请对话模型按村民的人设把这个结果说成一句他自己的话。
 * 异步生成，回来后替换界面台词栏；模型忙（正在对话回复）或失败就保留系统台词，
 * 不等、不报错、不影响买卖本身。
 * <p>
 * 「记忆」不在本类：成交事实由 ShopSettlement 在成交当场 addFact 落账（确定的事
 * 不需要模型加工），本类只负责把话说得像他本人。
 */
public final class ShopChatter {

    private ShopChatter() {
    }

    /**
     * 请模型把一件已定下的买卖结果说成村民的一句话，回来后替换会话台词。
     *
     * @param cannedLine 界面当前已显示的系统台词（调用方刚 setNpcLine 的那句）；
     *                   回调时台词栏若已不是这句（玩家又做了新动作），不覆盖
     * @param eventText  发生了什么（插件口径的事实描述，含数字——模型只许转述，不许改）
     */
    public static void speak(McaiPlugin plugin, VillagerState state, Player player,
                             ShopSession session, String cannedLine, String eventText) {
        if (plugin == null || state == null || player == null || session == null) return;
        if (state.llmBusy()) return;   // 正在对话回复中，不抢模型
        String npcName = Smalltalk.nameOf(state);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", PromptBuilder.buildVillagerSystemPrompt(
                        state.profession(), npcName, state.personalityTagIds(), state)),
                Map.of("role", "user", "content",
                        "[场景] 你在自己的小店里，旅者 " + player.getName() + " 正在跟你做买卖。\n"
                                + "刚刚定下的事：" + eventText + "\n"
                                + "结果已经定死了，不用再谈价、不要改动里面的数字。用你的口气对他说一句话"
                                + "（不超过 30 个字），自然口语，只说话，不要解释、不要列表。"));
        plugin.getLlmClient().chat(messages, List.of())
                .thenAccept(response -> {
                    String line = Smalltalk.stripToolCall(response.content());
                    if (line == null || line.isBlank()) return;   // 没说出来就保留系统台词
                    if (line.length() > 40) line = line.substring(0, 40);
                    String spoken = line;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // 台词栏还是那句系统台词才替换——玩家已经做了新动作就别抢
                        if (!cannedLine.equals(session.npcLine())) return;
                        session.setNpcLine(spoken);
                        refreshOpenGui(player, state);
                    });
                })
                .exceptionally(ex -> null);   // 模型挂了静默保留系统台词
    }

    /** 玩家正开着这家店的界面时，把新台词刷上去（关着就不管，下次打开自然是新台词） */
    private static void refreshOpenGui(Player player, VillagerState state) {
        if (!player.isOnline()) return;
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof com.howard.mcai.gui.ShopGui gui
                && gui.villagerUuid().equals(state.getUniqueId())) {
            gui.rebuild();
        }
    }
}
