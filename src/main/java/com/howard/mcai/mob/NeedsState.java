package com.howard.mcai.mob;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NPC 需求状态（SPEC 2.14 需求系统）—— 主动性的来源。
 * <p>
 * 五项需求（0-100，越高越迫切），随时间自然增长；需求驱动 NPC 在没有玩家指令时
 * 也会自主行动（找吃/睡觉/闲逛/探索/找人聊天）。增长后由两条路径满足：
 * <ol>
 *   <li><b>规则满足</b>（不调 LLM）：吃东西（清 hunger）、睡觉恢复（回 energy）、
 *       移动/聊天中的自然回落 —— 见 NeedsLayer</li>
 *   <li><b>LLM 自主行动</b>：需求越过阈值时触发一次决策调用，模型选工具行动，
 *       行动完成后按 {@code SATISFY_ON_ACTION} 降低对应需求</li>
 * </ol>
 * 性格修正涨速：人格标签（personality_tags.json）决定谁饿得快、谁耐不住寂寞 ——
 * 吃货 hunger 涨得快、社牛 social 涨得快、社恐 social 涨得慢。
 * 数值持久化到记忆文件，重启后需求连续（不清零，保持"活着的连续感"）。
 */
public final class NeedsState {

    public static final String HUNGER = "hunger";      // 饥饿：涨得快，满了找吃的/做饭/买食物
    public static final String ENERGY = "energy";      // 精力：干活熬夜消耗，低了回家睡觉（反向：睡觉恢复）
    public static final String BOREDOM = "boredom";    // 无聊：无所事事涨，满了找朋友唠嗑/闲逛
    public static final String CURIOSITY = "curiosity";// 好奇：看到新事物涨，满了去探索/围观
    public static final String SOCIAL = "social";      // 社交：独处涨，满了主动找人聊天

    /** 需求键固定顺序（scene JSON / 持久化 / 调试输出共用，保证稳定） */
    public static final String[] ALL = {HUNGER, ENERGY, BOREDOM, CURIOSITY, SOCIAL};

    /** 自主行动完成后对应需求的削减量 —— "需求满足后清零/降低"（一次行动 ≈ 缓解近半） */
    public static final double SATISFY_ON_ACTION = 35.0;

    /** 吃东西一次降低的饥饿量（吃 1 个食物 ≈ 缓解六成饥饿） */
    public static final double SATISFY_ON_EAT = 60.0;

    /** 需求硬上限/下限 */
    private static final double MIN = 0.0, MAX = 100.0;

    private double hunger;
    private double energy;
    private double boredom;
    private double curiosity;
    private double social;

    /**
     * 人格标签 → 需求涨速倍率表。
     * 只列有"性格倾向"的标签，其余标签倍率恒为 1.0（行为与无标签一致）。
     * key = personality_tags.json 的 tag id，value = {需求键: 倍率}。
     */
    private static final Map<String, Map<String, Double>> TAG_NEED_MULT = Map.ofEntries(
            // 吃货：三句不离吃 → 饿得快
            Map.entry("adventurous_foodie", Map.of(HUNGER, 1.6)),
            // 社牛/话痨：耐不住寂寞 → social 涨得快；社恐/高冷/惜字如金 → 涨得慢
            Map.entry("social_butterfly", Map.of(SOCIAL, 1.6)),
            Map.entry("talkative", Map.of(SOCIAL, 1.3)),
            Map.entry("social_anxious", Map.of(SOCIAL, 0.5)),
            Map.entry("aloof", Map.of(SOCIAL, 0.6)),
            Map.entry("terse", Map.of(SOCIAL, 0.7)),
            // 天真烂漫：看什么都新鲜 → 好奇涨得快；胆小鬼不敢探索 → 涨得慢
            Map.entry("naive", Map.of(CURIOSITY, 1.5)),
            Map.entry("coward", Map.of(CURIOSITY, 0.6)),
            // 财迷闲不住（总想找赚钱的机会）→ 无聊涨得快
            Map.entry("money_grubber", Map.of(BOREDOM, 1.2)));

    public NeedsState() {
        this(30, 70, 20, 20, 30);
    }

    public NeedsState(double hunger, double energy, double boredom, double curiosity, double social) {
        this.hunger = clamp(hunger);
        this.energy = clamp(energy);
        this.boredom = clamp(boredom);
        this.curiosity = clamp(curiosity);
        this.social = clamp(social);
    }

    // ===== 读写 =====

    public double get(String need) {
        return switch (need) {
            case HUNGER -> hunger;
            case ENERGY -> energy;
            case BOREDOM -> boredom;
            case CURIOSITY -> curiosity;
            case SOCIAL -> social;
            default -> 0;
        };
    }

    /** 增长/降低某项需求（正涨负降，自动钳制 0-100） */
    public void add(String need, double delta) {
        switch (need) {
            case HUNGER -> hunger = clamp(hunger + delta);
            case ENERGY -> energy = clamp(energy + delta);
            case BOREDOM -> boredom = clamp(boredom + delta);
            case CURIOSITY -> curiosity = clamp(curiosity + delta);
            case SOCIAL -> social = clamp(social + delta);
            default -> { }
        }
    }

    /** 需求满足后的削减（等价 add(need, -amount)），语义化入口 */
    public void satisfy(String need, double amount) {
        add(need, -Math.abs(amount));
    }

    /** 全部需求快照（scene JSON / 持久化 / 调试输出用，LinkedHashMap 保持固定顺序） */
    public Map<String, Double> snapshot() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : ALL) m.put(k, get(k));
        return m;
    }

    /**
     * 最迫切且已越过阈值的需求（自主行动的触发依据）。
     * 多项同时越线时取值最大者；并列时按 ALL 的固定顺序（hunger 优先 —— 活命第一）。
     *
     * @param threshold 触发阈值（config needs.threshold，默认 80）
     * @return 需求键，如 "hunger"；没有越线需求返回 null
     */
    public String dominantUrgent(double threshold) {
        String best = null;
        double bestVal = threshold;
        for (String k : ALL) {
            double v = get(k);
            if (v >= bestVal) {
                bestVal = v;
                best = k;
            }
        }
        return best;
    }

    /** 需求中文名（气泡/日志/场景行用） */
    public static String zhName(String need) {
        return switch (need) {
            case HUNGER -> "饥饿";
            case ENERGY -> "疲惫";
            case BOREDOM -> "无聊";
            case CURIOSITY -> "好奇";
            case SOCIAL -> "想聊天";
            default -> need;
        };
    }

    /**
     * 人格标签对该需求的涨速倍率（未列出的标签/需求恒为 1.0）。
     * 静态查询：NeedsLayer 每 tick 对每个 NPC 的每个标签都要查，保持 O(标签数) 即可。
     */
    public static double tagMultiplier(String tagId, String need) {
        Map<String, Double> m = TAG_NEED_MULT.get(tagId);
        if (m == null) return 1.0;
        return m.getOrDefault(need, 1.0);
    }

    /** 该 NPC 所有标签对某需求的综合倍率（各标签倍率叠乘，钳到 [0.3, 2.0] 防极端） */
    public static double combinedMultiplier(Iterable<String> tagIds, String need) {
        double mult = 1.0;
        for (String id : tagIds) {
            mult *= tagMultiplier(id, need);
        }
        return Math.max(0.3, Math.min(2.0, mult));
    }

    private static double clamp(double v) {
        return Math.max(MIN, Math.min(MAX, v));
    }
}
