package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerState;

/**
 * 群聊/闲聊的轻量规则工具 —— 只在"规则化附和"场景用，不调 LLM。
 * <ul>
 *   <li>{@link #reactToPlayerMessage} 玩家群聊时，旁听 NPC 的关键词反应句（冒气泡）</li>
 *   <li>{@link #stripToolCall} 剥掉模型惯性输出的 tool_call 片段（一次性回复没给 tools，但它习惯性会吐）</li>
 *   <li>{@link #nameOf} 村民显示名（去颜色码），多个地方共用</li>
 * </ul>
 */
public final class Smalltalk {

    private Smalltalk() {}

    /**
     * 玩家群聊时，旁听 NPC 的规则化附和句。
     * 按消息关键词挑一句，让 NPC 看起来"在听、有反应"。
     *
     * @param message      玩家刚才说的话
     * @param listenerName 旁听 NPC 的名字（备用，当前规则未用到，保留参数防后续扩展）
     */
    public static String reactToPlayerMessage(String message, String listenerName) {
        if (message == null) message = "";
        String m = message;
        // 群体招呼 → 凑过来听
        if (m.contains("大家") || m.contains("你们") || m.contains("各位")) {
            return pick("（凑过来听）", "（探头）说啥呢？", "（插一嘴）俺也听着呢", "（放下手里的活）啥事？");
        }
        // 先提取话题片段 —— 任何消息都优先"贴话题"附和。
        // 之前关键词分支在前，"你不是在睡觉吗"会永远走问句模板（俺也想知道/咋回事），
        // 既不着边又全员重复。片段引用优先解决这两个问题。
        String snippet = extractSnippet(m);
        if (snippet != null) {
            return pick("（听到说" + snippet + "，凑过来）", "（" + snippet + "？俺寻思着也是）",
                    "（插一嘴）说到" + snippet + "俺可来劲", "（若有所思）" + snippet + "……",
                    "（抬眼）" + snippet + "？", "（接话）" + snippet + "的事俺知道点");
        }
        // 片段提取失败 → 按主题词兜底（此时消息基本只剩语气词，如"吃了吗？""在吗？"）
        if (m.contains("吃") || m.contains("肉") || m.contains("面包") || m.contains("饭") || m.contains("喝")) {
            return pick("（咽口水）说到吃俺就来劲", "（摸摸肚子）好馋啊", "（乐）今儿个吃了没？",
                    "（咂嘴）给俺也留一口", "（凑近）啥好吃的？");
        }
        if (m.contains("卖") || m.contains("买") || m.contains("价") || m.contains("绿宝") || m.contains("便宜")) {
            return pick("（盘算）多少绿宝？", "（凑近看货）这价公道", "（嘀咕）买卖的事可得算仔细",
                    "（搓手）要不俺也掺一脚？");
        }
        if (m.contains("睡") || m.contains("困")) {
            return pick("（揉眼睛）俺也困得慌", "（哈欠）夜里的觉最香", "（点头）可不，站着都能睡着");
        }
        if (m.contains("你好") || m.contains("嗨") || m.contains("hello") || m.contains("早")) {
            return pick("（挥手）来了啊", "（笑）好哇", "（点头）今儿个真热闹", "（乐）正念叨你呢");
        }
        if (m.contains("？") || m.contains("?") || m.contains("吗") || m.contains("么")) {
            return pick("（歪头想了想）", "（好奇）咋回事？", "（点头）俺也想知道", "（挠头）这俺可说不好");
        }
        return pick("（点点头）", "（哦？）", "（听了一耳朵）", "（嘀咕）有道理", "（眯眼琢磨）");
    }

    /** 常见语气词/指代词 —— 提取原话片段前先剔除，让片段保留有意义的内容 */
    private static final String[] STOP_WORDS = {
            "请问", "那个", "这个", "什么", "怎么", "为啥", "大家", "你们", "各位", "旅者",
            "你好", "哈喽", "hello", "嗨", "俺", "我", "你", "了", "的", "吗", "呢", "啊", "呀", "吧"
    };

    /**
     * 从玩家消息里提取一个有意义的关键片段（2-6 字）供附和句引用。
     * 去掉停用词后取剩余内容的开头；片段过短或整句被停用词清空则返回 null。
     */
    private static String extractSnippet(String m) {
        String cleaned = m;
        for (String w : STOP_WORDS) {
            cleaned = cleaned.replace(w, " ");
        }
        cleaned = cleaned.trim();
        if (cleaned.length() < 2) return null;
        return cleaned.length() <= 6 ? cleaned : cleaned.substring(0, 6);
    }

    /** 随机挑一个候选项 */
    private static String pick(String... options) {
        return options[(int) (Math.random() * options.length)];
    }

    /**
     * 剥掉模型可能输出的 tool_call 片段。
     * 一次性回复（村民闲聊/群聊点名）没传 tools，但模型有 tool_call 惯性，
     * 偶尔会在正文里吐调用片段，不剥掉会显示在气泡里很难看。
     * <p>
     * 直接复用 {@link LLMClient#stripToolCalls}：模型除了 XML 标签
     * （&lt;tool_call&gt;...），还会吐裸 JSON（{"name":"say",...}）和
     * Python 风格（.say(message="...")）—— 只剥 XML 的旧实现会让后两类原样进气泡。
     * 三种格式的剥离逻辑统一收在 LLMClient 一处，避免两份实现不同步。
     */
    public static String stripToolCall(String line) {
        if (line == null) return null;
        return LLMClient.stripToolCalls(line);
    }

    /** 村民显示名（去 Minecraft 颜色码），多个地方共用 */
    public static String nameOf(VillagerState vs) {
        String n = vs.entity().getCustomName();
        return n != null && !n.isBlank() ? n.replaceAll("§.", "") : vs.profession().zhName;
    }
}
