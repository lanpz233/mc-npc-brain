package com.howard.mcai.ai;

import java.util.EnumSet;

/**
 * 事实直答 —— 纯事实问题由插件直接回答，模型碰不到数字（2026-10-22，服主选定 C 路线）。
 * <p>
 * 背景：时间/天气这类事实，提示侧怎么加固都只能把对率拉到七八成 —— 每经一次
 * 模型复述就多一次翻面机会（实测 15:55 能说成“十四点五十五分”），且与模型
 * 听不听话强相关，换个模型就复发。这类问题改由插件拼句直答：事实归插件，
 * 模型只管其余聊天的表达。
 * <p>
 * 回答的“粒度”学活人（服主定）：问时段就按太阳位置答时段，不报点；
 * 问几点报约数 —— 取整由插件按真实时间做，又像活人又准确。
 * 范围卡死：只认又短又明确的事实问句，长句、叙事句一律不接管。
 */
public final class FactAnswers {

    public enum Kind {
        /** 问几点（要具体时间） */
        TIME_EXACT,
        /** 问时段（上午还是下午、天黑了没） */
        TIME_PERIOD,
        /** 问此刻天气（下没下雨、天气怎么样） */
        WEATHER_NOW,
        /** 问天气预报（雨啥时停、会不会下雨） */
        RAIN_FORECAST,
        /** 问离天黑还有多久 */
        DARK_SOON,
        /** 问村民自身状态（你饿不饿、困不困） */
        SELF_STATE
    }

    /** 拼句所需的事实快照（由接线层从真实世界/状态取，全部已翻译好） */
    public record Facts(
            long tickOfDay,
            LocalWeather.Kind weather,
            boolean dryBiome,
            boolean snowyBiome,
            int weatherDurationTicks,
            String hungerTier,
            String energyTier,
            String boredomTier,
            String healthTier,
            int approxStepMinutes) {
    }

    private FactAnswers() {
    }

    // ===== 识别 =====

    /** 事实问句都很短；长句多半是叙事或复合请求，不接管 */
    private static final int MAX_LEN = 30;

    public static EnumSet<Kind> detect(String message) {
        EnumSet<Kind> out = EnumSet.noneOf(Kind.class);
        if (message == null) return out;
        String m = message.trim();
        if (m.isEmpty() || m.length() > MAX_LEN) return out;

        // —— 时间：时段类（先判，避免“天黑了没”被当成精确时间）——
        if (containsAny(m, "天黑了没", "天黑了吗", "天黑没", "黑天了没", "天亮了没", "天亮了吗",
                "上午还是", "中午还是", "下午还是", "晚上还是", "白天还是", "现在是白天", "现在是晚上",
                "是上午", "是中午", "是下午")) {
            out.add(Kind.TIME_PERIOD);
        }
        // —— 时间：精确类 ——
        if (containsAny(m, "几点", "几时", "现在时间", "报个时", "到点了吗")) {
            out.add(Kind.TIME_EXACT);
        }
        // —— 离天黑 ——
        if (containsAny(m, "天快黑", "快天黑", "离天黑", "天黑还有", "天什么时候黑", "啥时候天黑",
                "什么时候天黑", "今晚安全", "天黑前")) {
            out.add(Kind.DARK_SOON);
        }
        // —— 天气：预报类（先于现状类判，“雨什么时候停”别落到现状）——
        if (containsAny(m, "什么时候停", "啥时候停", "何时停", "要下多久", "还要下多久",
                "什么时候放晴", "啥时候放晴", "会下雨吗", "会不会下雨", "要下雨吗", "会下雪吗", "会不会下雪")) {
            out.add(Kind.RAIN_FORECAST);
        }
        // —— 天气：现状类 ——
        if (containsAny(m, "下雨了吗", "下雨没", "下没下雨", "有没有下雨", "在下雨吗", "外面下雨",
                "下雪了吗", "下雪没", "下没下雪", "有没有下雪", "在下雪吗", "外面下雪",
                "打雷了吗", "打雷没", "有没有打雷", "天气怎么样", "天气如何", "今天天气", "外面天气")) {
            out.add(Kind.WEATHER_NOW);
        }
        // —— 自身状态 ——
        if (containsAny(m, "你饿", "饿了吗", "你困", "你累", "你无聊", "你想睡", "你身体", "你的伤", "伤好了")) {
            out.add(Kind.SELF_STATE);
        }
        return out;
    }

    private static boolean containsAny(String m, String... keys) {
        for (String k : keys) {
            if (m.contains(k)) return true;
        }
        return false;
    }

    // ===== 时间换算（与 PromptBuilder 同公式，本类自带以保持纯净） =====

    private static String zhNum(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 10) return d[n];
        int tens = n / 10, ones = n % 10;
        return (tens == 1 ? "十" : d[tens] + "十") + (ones == 0 ? "" : d[ones]);
    }

    private static int hourOf(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        return (int) ((t / 1000 + 6) % 24);
    }

    private static int minuteOf(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        return (int) ((t % 1000) * 60 / 1000);
    }

    private static String periodZh(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        return switch ((int) (t / 1000)) {
            case 0, 1 -> "清晨";
            case 2, 3, 4, 5 -> "上午";
            case 6, 7, 8 -> "中午";
            case 9, 10, 11 -> "下午";
            case 12 -> "黄昏";
            case 23 -> "黎明前";
            default -> "夜里";
        };
    }

    /**
     * 约数报时 —— 插件按真实时间取整，活人味 + 准确兼得。
     * 例：08:10 → “八点十分左右”；08:51 → “快九点了”；08:30 → “八点半左右”；
     * 08:41 → “八点四十左右”（按 step 取整）。
     */
    public static String approxClock(long tick, int stepMinutes) {
        int h = hourOf(tick);
        int min = minuteOf(tick);
        int step = Math.max(1, stepMinutes);
        // 口语按 12 小时说：下午的 15 点叫“三点”，配上时段词才像人话
        // （“下午十五点”正是模型之前产出“下午十四点五十五分”的怪味来源）
        int sh = h % 12 == 0 ? 12 : h % 12;
        int shNext = (h + 1) % 12 == 0 ? 12 : (h + 1) % 12;
        if (min == 0) return zhNum(sh) + "点整";
        if (min >= 60 - Math.max(5, step)) return "快" + zhNum(shNext) + "点了";
        if (min <= 3) return zhNum(sh) + "点刚过一会儿";
        if (Math.abs(min - 30) <= step / 2) return zhNum(sh) + "点半左右";
        int rounded = Math.round((float) min / step) * step;
        if (rounded >= 60) return "快" + zhNum(shNext) + "点了";
        if (rounded == 0) return zhNum(sh) + "点刚过一会儿";
        if (rounded == 30) return zhNum(sh) + "点半左右";
        return zhNum(sh) + "点" + zhNum(rounded) + "分左右";
    }

    /** 时段回答 —— 按太阳位置说话，不报点 */
    public static String periodAnswer(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        int seg = (int) (t / 1000);
        return switch (seg) {
            case 0, 1 -> "清晨，太阳刚升起来没多久。";
            case 2, 3, 4, 5 -> "上午，太阳升起来有一会儿了。";
            case 6, 7 -> "中午了，日头正高。";
            case 8 -> "过了正午，往下午去了。";
            case 9, 10, 11 -> "下午了，太阳往西边偏了。";
            case 12 -> "黄昏了，太阳正往下落，天快黑了。";
            case 18, 19, 20, 21, 22 -> "半夜了，天黑透了。";
            case 23 -> "快天亮了，黎明前这阵子最黑。";
            default -> "天已经黑了，是夜里了。";
        };
    }

    /** 离天黑还有多久（游戏分钟数；已入夜返回 -1） */
    public static int minutesToDark(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        if (t >= 13000) return -1;
        long ticksLeft = 13000 - t;
        return (int) (ticksLeft * 60 / 1000);
    }

    // ===== 拼句 =====

    public static String compose(EnumSet<Kind> kinds, Facts f) {
        StringBuilder sb = new StringBuilder();
        if (kinds.contains(Kind.TIME_PERIOD)) {
            sb.append(periodAnswer(f.tickOfDay()));
        }
        if (kinds.contains(Kind.TIME_EXACT)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("现在是").append(periodZh(f.tickOfDay()))
                    .append(approxClock(f.tickOfDay(), f.approxStepMinutes())).append("。");
        }
        if (kinds.contains(Kind.WEATHER_NOW)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(weatherNowAnswer(f));
        }
        if (kinds.contains(Kind.RAIN_FORECAST)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(forecastAnswer(f));
        }
        if (kinds.contains(Kind.DARK_SOON)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(darkSoonAnswer(f.tickOfDay()));
        }
        if (kinds.contains(Kind.SELF_STATE)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(selfStateAnswer(f));
        }
        return sb.toString();
    }

    private static String weatherNowAnswer(Facts f) {
        return switch (f.weather()) {
            case NONE -> "这地方没有天气一说，一直就这样。";
            case CLEAR -> f.dryBiome()
                    ? "晴着呢。这儿是干旱地带，天上再大的雨也下不到这儿。"
                    : (f.snowyBiome() ? "晴着呢，没下雪。" : "晴着呢，没下雨。");
            case RAIN -> "下着雨呢，出门当心路滑。";
            case SNOW -> "下着雪呢，路上积雪了。";
            case THUNDER_RAIN -> "下着暴雨，还打着雷，尽量别在空旷地方待着。";
            case THUNDER_SNOW -> "下着大雪，还打着雷，怪天气。";
        };
    }

    private static String forecastAnswer(Facts f) {
        if (f.weather() == LocalWeather.Kind.NONE) {
            return "这地方天气不会变，一直这样。";
        }
        // 世界剩余天气时长（tick）→ 游戏分钟
        int mins = Math.max(0, f.weatherDurationTicks()) * 60 / 1000;
        if (LocalWeather.isPrecipitating(f.weather())) {
            if (mins < 25) return "这场" + (f.snowyBiome() ? "雪" : "雨") + "再有一小会儿就停了。";
            if (mins < 90) return "还得下半个多时辰才停。";
            return "这场" + (f.snowyBiome() ? "雪" : "雨") + "一时半会儿停不了。";
        }
        // 晴着：时长很长 = 稳定；较短 = 可能转天气（原版时长到点会随机切，不保证必下）
        if (f.dryBiome()) return "这儿旱，放心，不会下雨的。";
        if (mins > 240) return "这晴天稳着呢，短时间里不会变天。";
        return "眼下还晴着，过阵子说不好会变天。";
    }

    private static String darkSoonAnswer(long tick) {
        int mins = minutesToDark(tick);
        if (mins < 0) return "已经天黑了，夜里在外面当心点。";
        if (mins < 20) return "马上就天黑了，太阳都落到山边了。";
        if (mins < 60) return "快了，还有半个时辰左右天黑。";
        int shichen = (int) Math.round(mins / 120.0); // 一个时辰 ≈ 2 游戏小时
        if (shichen <= 1) return "还有一个多时辰才天黑。";
        return "离天黑还早，还有" + zhNum(shichen) + "个多时辰。";
    }

    private static String selfStateAnswer(Facts f) {
        // 哪项最难受先说哪项；档位词拼句，像人话不像读仪表
        StringBuilder sb = new StringBuilder("我？");
        sb.append("这会儿").append(f.energyTier()).append("，肚子").append(f.hungerTier()).append("。");
        if (f.hungerTier().equals("饿得慌") || f.hungerTier().equals("饿了")) {
            sb.append("正想找口吃的。");
        } else if (f.energyTier().equals("困得睁不开眼") || f.energyTier().equals("困了")) {
            sb.append("正想找个地方眯一会儿。");
        } else if (f.boredomTier().equals("闲得发慌") || f.boredomTier().equals("无聊")) {
            sb.append("就是有点闲得慌。");
        }
        return sb.toString();
    }

    /**
     * 偶尔的活人式追问（接线层按概率决定要不要缀上）。
     * seed 用于在候选里换着挑，避免每次同一句。
     */
    public static String followUp(EnumSet<Kind> kinds, Facts f, int seed) {
        boolean night = minutesToDark(f.tickOfDay()) < 0;
        String[] pool;
        if (kinds.contains(Kind.WEATHER_NOW) || kinds.contains(Kind.RAIN_FORECAST)) {
            pool = LocalWeather.isPrecipitating(f.weather())
                    ? new String[]{"问这个干嘛，你要出门？", "你没淋着吧？"}
                    : new String[]{"问这个干嘛，你有什么打算吗？", "怎么了，有事要办？"};
        } else if (night) {
            pool = new String[]{"这么晚了，你还不歇着？", "夜里问时间，你睡不着啊？"};
        } else if (kinds.contains(Kind.TIME_EXACT) || kinds.contains(Kind.TIME_PERIOD)) {
            pool = new String[]{"怎么了，有什么打算吗？", "你问这个，是赶时间？"};
        } else {
            pool = new String[]{"你呢，你怎么样？", "你也早点歇着。"};
        }
        return pool[Math.floorMod(seed, pool.length)];
    }
}
