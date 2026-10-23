package com.howard.mcai.bot;

import com.howard.mcai.ai.LLMClient;
import com.howard.mcai.ai.PersonalityTagLibrary;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 假人对话：聊天路由（凑近/喊名字/对话续接）→ 先过命令解析 → 再走模型聊天。
 * 模型只有表达权（老规矩）：派活、存货、盖房这类事全在 BotOrders 里由插件裁决。
 */
public final class BotChatService {

    private final BotService svc;

    public BotChatService(BotService svc) { this.svc = svc; }

    /**
     * PlayerChatListener 在村民路由之前调用。
     * @param inVillagerConversation 该玩家是否正跟村民对话中（防抢村民的话）
     * @return true = 已被假人接走
     */
    public boolean tryHandle(Player speaker, String message, boolean inVillagerConversation) {
        BotAgent best = null;
        // 1) 喊名字（中文名或账号名）—— 隔多远都接
        for (BotAgent a : svc.agents()) {
            if (!a.alive()) continue;
            if (message.contains(a.state.chineseName) || message.contains(a.state.accountName)) {
                if (a.player.getWorld().equals(speaker.getWorld())) { best = a; break; }
            }
        }
        // 2) 对话续接（90 秒内、10 格内）
        if (best == null) {
            for (BotAgent a : svc.agents()) {
                if (!a.alive()) continue;
                if (speaker.getUniqueId().equals(a.chatPartner)
                        && System.currentTimeMillis() - a.lastChatAt < 90_000
                        && a.player.getWorld().equals(speaker.getWorld())
                        && a.player.getLocation().distanceSquared(speaker.getLocation()) <= 100) {
                    best = a; break;
                }
            }
        }
        // 3) 凑近说话（5 格内最近的一个）；正跟村民聊着时不抢（除非上面 1/2 命中）
        if (best == null && !inVillagerConversation) {
            double bd = 25.0;
            for (BotAgent a : svc.agents()) {
                if (!a.alive() || !a.player.getWorld().equals(speaker.getWorld())) continue;
                double d = a.player.getLocation().distanceSquared(speaker.getLocation());
                if (d < bd) { bd = d; best = a; }
            }
        }
        if (best == null) return false;

        BotAgent a = best;
        a.chatPartner = speaker.getUniqueId();
        a.lastChatAt = System.currentTimeMillis();
        a.state.pushChat(speaker.getName() + ": " + message);
        // 转头看说话的人（活人感）
        BotJobs.lookAt(svc, a, speaker.getEyeLocation());

        if (BotOrders.tryOrder(svc, a, speaker, message)) {
            svc.storeDirtySoon();
            return true;
        }
        chatWithModel(a, speaker, message);
        return true;
    }

    private void chatWithModel(BotAgent a, Player speaker, String message) {
        if (a.llmBusy) return; // 上一句还没想完，不叠请求
        a.llmBusy = true;
        BotState s = a.state;
        String traits = "";
        try { traits = PersonalityTagLibrary.buildPromptTraits(s.personalityTagIds); } catch (Throwable ignored) {}
        StringBuilder sys = new StringBuilder();
        sys.append("你是在《Minecraft》世界里真实生活的一个居民，名字叫").append(s.chineseName)
           .append("，").append("female".equals(s.gender) || "F".equals(s.gender) ? "女性" : "男性")
           .append("。你有真实的身体和背包，会饿、会受伤、会害怕。")
           .append("你的性格：").append(traits == null || traits.isBlank() ? "随和、爱聊天" : traits)
           .append("。你正在和玩家 ").append(speaker.getName()).append(" 说话。")
           .append("用自然口语中文回复，一到两句话，像真人邻居聊天。")
           .append("不要说自己是 AI 或程序；不要随口承诺送东西、给钱、带路——那些事要对方正式吩咐你才会做。");
        if (s.fear >= 40) sys.append("你现在很害怕，说话会发抖、想求饶。");

        StringBuilder ctx = new StringBuilder();
        ctx.append(sceneLine(a)).append("\n");
        if (!s.facts.isEmpty()) ctx.append("你记得的事：").append(String.join("；", s.facts)).append("\n");
        if (!s.recentChat.isEmpty()) {
            ctx.append("最近的对话：\n");
            int from = Math.max(0, s.recentChat.size() - 6);
            for (int i = from; i < s.recentChat.size(); i++) ctx.append(s.recentChat.get(i)).append("\n");
        }
        ctx.append(speaker.getName()).append(" 对你说：").append(message);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> m1 = new HashMap<>(); m1.put("role", "system"); m1.put("content", sys.toString());
        Map<String, String> m2 = new HashMap<>(); m2.put("role", "user"); m2.put("content", ctx.toString());
        messages.add(m1); messages.add(m2);

        svc.plugin().getLlmClient().chat(messages, List.of()).whenComplete((resp, err) -> {
            Bukkit.getScheduler().runTask(svc.plugin(), () -> {
                a.llmBusy = false;
                if (err != null || resp == null) {
                    svc.plugin().getLogger().fine("[MC-AI] 假人聊天 LLM 失败: " + (err == null ? "null" : err.getMessage()));
                    return;
                }
                String text = LLMClient.stripToolCalls(resp.content() == null ? "" : resp.content()).trim();
                if (text.isEmpty()) return;
                if (text.length() > 300) text = text.substring(0, 300);
                s.pushChat(s.chineseName + ": " + text);
                s.addFavor(speaker.getUniqueId(), 0.5);
                svc.sayTo(a, speaker, text);
                svc.storeDirtySoon();
            });
        });
    }

    /** 场景简报：全是定性词（与村民"定性给词"的口径一致），事实由插件给准。 */
    private String sceneLine(BotAgent a) {
        Player bot = a.player;
        long t = bot.getWorld().getTime();
        String period = t < 1000 ? "清晨" : t < 6000 ? "上午" : t < 9000 ? "中午"
                : t < 12000 ? "下午" : t < 13000 ? "傍晚" : t < 22500 ? "夜里" : "深夜";
        String hunger = bot.getFoodLevel() >= 15 ? "你肚子不饿" : bot.getFoodLevel() >= 8 ? "你有点饿了" : "你饿得很";
        String health = bot.getHealth() >= 16 ? "身上没伤" : bot.getHealth() >= 9 ? "受了点伤" : "伤得不轻";
        String doing = switch (a.mode) {
            case WORK -> "你正在干活";
            case REST -> "你正在休息";
            case FOLLOW -> "你正跟着人走";
            default -> "你正闲着";
        };
        return "现在是" + period + "，" + hunger + "，" + health + "，" + doing + "。";
    }
}
