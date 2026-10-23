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
        // 0) 右键对话锁定中 —— 聊天直达，不用贴脸；走太远（>16 格）自动散
        for (BotAgent a : svc.agents()) {
            if (speaker.getUniqueId().equals(a.convPartner)) {
                if (System.currentTimeMillis() > a.convUntil || !a.alive()) { a.convPartner = null; break; }
                if (!a.player.getWorld().equals(speaker.getWorld())
                        || a.player.getLocation().distanceSquared(speaker.getLocation()) > 16 * 16) {
                    a.convPartner = null;
                    speaker.sendMessage("§7[MC-AI] 你走远了，和 " + a.state.chineseName + " 的对话结束了。");
                    break;
                }
                best = a;
                break;
            }
        }
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
        // 锁定中就续期（3 分钟没说话才散）
        if (speaker.getUniqueId().equals(a.convPartner)) a.convUntil = a.lastChatAt + 180_000;
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

    /**
     * 右键假人 = 开始/结束对话（Fong 要求，跟村民一个待遇，不再靠"凑近说话"）。
     * 开始：锁定对话（聊天直达、16 格内有效、3 分钟没说话自动散），他先停下手头的活，
     * 转头看着你并开口打招呼；再右键一次 = 结束。聊完想让他接着干活，重新吩咐一句即可。
     */
    public void toggleConversation(Player player, BotAgent a) {
        if (!a.alive()) return;
        long now = System.currentTimeMillis();
        if (player.getUniqueId().equals(a.convPartner) && now <= a.convUntil) {
            a.convPartner = null;
            svc.sayTo(a, player, "那我先忙去了，有事喊我。");
            player.sendMessage("§7[MC-AI] 和 " + a.state.chineseName + " 的对话结束了。");
            return;
        }
        // 别人正锁着他 → 先抢过来（同一时刻只跟一个人右键对话）
        a.convPartner = player.getUniqueId();
        a.convUntil = now + 180_000;
        a.chatPartner = player.getUniqueId();
        a.lastChatAt = now;
        if (a.mode != BotAgent.Mode.IDLE) {
            a.haltAll(svc);
            player.sendMessage("§7[MC-AI] " + a.state.chineseName + " 先停下手里的事跟你聊（聊完要他接着干，重新吩咐一句）。");
        } else {
            player.sendMessage("§7[MC-AI] 你在和 " + a.state.chineseName + " 对话（打字就是跟他说；再右键他或走远就结束）。");
        }
        BotJobs.lookAt(svc, a, player.getEyeLocation());
        greet(a, player);
    }

    /** 开始对话时的开场白：由模型以他的身份先开口（插件只给情境，话还是他自己说）。 */
    private void greet(BotAgent a, Player speaker) {
        chatWithModel(a, speaker, null);
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
        if (message != null) {
            ctx.append(speaker.getName()).append(" 对你说：").append(message);
        } else {
            // 开场白（右键开始对话时）：情境由插件给，话由他自己说
            ctx.append("（").append(speaker.getName())
               .append(" 走到你面前，主动想跟你聊聊。你先开口跟他打个招呼：")
               .append("如果你们很熟、或者记得关于他的事，就像老朋友那样自然；不熟就简单自我介绍一句。）");
        }

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
