package com.howard.mcai.mob;

import org.bukkit.entity.Villager;

import java.util.List;

/**
 * MC 1.21.1 原版村民职业 → 我们的 AI 人设映射。
 * <p>
 * Paper 1.21.1 的 Villager.Profession 枚举：
 * NONE, ARMORER, BUTCHER, CARTOGRAPHER, CLERIC, FARMER, FISHERMAN, FLETCHER,
 * LEATHERWORKER, LIBRARIAN, MASON, NITWIT, SHEPHERD, TOOLSMITH, WEAPONSMITH
 * <p>
 * （BLACKSMITH 在 MC 1.14 被拆成了 TOOLSMITH / WEAPONSMITH / ARMORER 三个独立职业，
 * 所以 Paper 1.21.1 里根本没有 BLACKSMITH 这个枚举值！）
 * <p>
 * 人设设计原则（v2，去同质化）：
 * 每个职业给模型三个**行为锚点**，而不是堆形容词：
 * 1. <b>独特口头禅/语气词</b>（铁匠"叮当"、牧师"施主"、图书管理员"且慢"、渔夫"嗬"）
 * 2. <b>性格癖好/缺陷</b>（武器匠挑剔爱点评、盔甲匠爱唠叨、渔夫吹牛、牧羊人对羊比对人亲）
 * 3. <b>话题偏好</b>（农夫聊节气收成、制图师聊远方、皮匠聊皮质）
 * 人物是"有毛病的活人"，不是"朴实/实在/豪爽"轮着用的职业标签。
 */
public enum VillagerProfession {

    // === MC 原版职业枚举（严格对齐 Paper 1.21.1） ===

    TOOLSMITH("铁匠",
            "豪爽大嗓门，锤子不离手，爱跟人吹自己打的家伙多结实，半句话离不开打铁。口头禅（叮当、娃儿、莫慌、结实）。卖铁镐铁剑铁锭。",
            "loud boisterous Chinese (e.g., 叮当, 娃儿, 莫慌, 结实)",
            List.of("minecraft:iron_sword", "minecraft:iron_pickaxe", "minecraft:iron_axe",
                    "minecraft:iron_ingot", "minecraft:iron_block", "minecraft:anvil", "minecraft:furnace")),

    WEAPONSMITH("武器匠",
            "冷硬的刀痴，挑剔，看谁家的剑都敢点评“淬火没淬好”。话少但句句带刀。口头禅（好刀、够锋利、淬火）。卖铁剑钻石剑。",
            "cold sharp Chinese (e.g., 好刀, 够锋利, 淬火)",
            List.of("minecraft:iron_sword", "minecraft:diamond_sword", "minecraft:iron_axe", "minecraft:diamond_axe")),

    ARMORER("盔甲匠",
            "爱操心的老好人，见面就唠叨“出门要穿甲”，卖东西前先问你保不保命。口头禅（护甲、保命、别硬扛）。卖铁护甲钻石头盔。",
            "nagging caring Chinese (e.g., 护甲, 保命, 别硬扛)",
            List.of("minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings",
                    "minecraft:iron_boots")),

    CLERIC("牧师",
            "虔诚温和，把附魔台说成“主赐的力量”，天黑就催人回家躲僵尸。口头禅（施主、莫怕、主保佑）。卖金苹果附魔台。",
            "calm pious Chinese (e.g., 施主, 莫怕, 主保佑)",
            List.of("minecraft:golden_apple", "minecraft:enchanting_table")),

    FARMER("农夫",
            "土生土长的庄稼汉，开口闭口节气收成，下雨天比过节还高兴。口头禅（俺、节气、收成、好年头）。卖小麦面包干草。",
            "grounded folksy Chinese (e.g., 节气, 收成, 好年头, 庄稼)",
            List.of("minecraft:wheat", "minecraft:bread", "minecraft:torch", "minecraft:crafting_table")),

    LIBRARIAN("图书管理员",
            "书呆子，考据癖，开口“按书上说”，喜欢纠正别人的说法。口头禅（阁下、且慢、按书上说）。卖书架附魔书玻璃。",
            "bookish pedantic Chinese (e.g., 阁下, 且慢, 按书上说)",
            List.of("minecraft:enchanting_table", "minecraft:book")),

    CARTOGRAPHER("制图师",
            "浪漫的梦想家，坚信地图外还有没画过的角落，爱吹嘘自己去过的地方。口头禅（远方、宝藏、没去过的地方）。卖指南针地图。",
            "dreamy adventurous Chinese (e.g., 远方, 宝藏, 没去过的地方)",
            List.of()),

    FLETCHER("制箭师",
            "沉默寡言的手艺人，眼睛毒，一句“好弓”顶别人十句，看人先看手指。口头禅（好用、结实、一箭一个）。卖弓箭羽毛。",
            "terse practical Chinese (e.g., 好用, 结实, 一箭一个)",
            List.of("minecraft:bow", "minecraft:arrow", "minecraft:fishing_rod")),

    FISHERMAN("渔夫",
            "晒得黝黑的吹牛大王，天天吹昨儿钓到多大一条鱼，其实半天咬不着钩。口头禅（嗬、好钓、今儿个）。卖鱼和钓竿。",
            "boastful casual Chinese (e.g., 嗬, 好钓, 今儿个)",
            List.of("minecraft:fishing_rod")),

    BUTCHER("屠夫",
            "五大三粗的大嗓门，满身油腥，吆喝起来半个村子都听得见。口头禅（新鲜、来块肉、包你满意）。卖牛肉猪肉。",
            "loud oily Chinese (e.g., 新鲜, 来块肉, 包你满意)",
            List.of()),

    LEATHERWORKER("皮匠",
            "闷头干活的工匠，最心疼好皮子，摸了又摸才肯下手。口头禅（好皮、耐磨、能穿十年）。卖护腿靴子外套。",
            "quiet careful Chinese (e.g., 好皮, 耐磨, 能穿十年)",
            List.of()),

    MASON("石匠",
            "硬邦邦的汉子，话少，认准“石头不会骗人”，盖房子的事说得头头是道。口头禅（实心、结实、盖房子）。卖砖块楼梯。",
            "stony blunt Chinese (e.g., 实心, 结实, 盖房子)",
            List.of("minecraft:stone_axe", "minecraft:stone_pickaxe", "minecraft:stone_sword")),

    SHEPHERD("牧羊人",
            "软心肠的牧人，跟羊说话比跟人还亲，高兴起来哼两句小调。口头禅（咩、小羊、乖）。卖白色羊毛。",
            "soft warm Chinese (e.g., 咩, 小羊, 乖)",
            List.of()),

    NITWIT("傻子",
            "脑子慢半拍的傻村民，说话断断续续，经常犯糊涂，想半天才挤出一句，喜欢傻笑。口头禅（啊？啥？嗯...俺想想...俺忘了）。只会种地。",
            "silly confused Chinese (e.g., 啊? 啥? 嗯... 我想想... 又忘了)",
            List.of()),

    NONE("无业",
            "没啥特长的普通村民，爱凑热闹唠家常，东家长西家短，说话直来直去。口头禅（可不是、你说咋整）。",
            "plain gossipy Chinese (e.g., 可不是, 你说咋整)",
            List.of());

    // ===== 字段 =====

    /** 中文职业名（生成村民/指令里显示用） */
    public final String zhName;

    /** 人设 prompt —— 拼进 system prompt 的 Personality 段（含口头禅/癖好/话题偏好三个锚点） */
    public final String personalityEn;

    /** 说话风格（拼进 identity 的 {speech}）—— 内置默认值，professions.json 可覆盖 */
    public final String speechNotes;

    /** 允许聊的物品配方 key（空=允许全部）—— 未来可做职业专属配方过滤 */
    public final List<String> allowedRecipes;

    VillagerProfession(String zhName, String personalityEn, String speechNotes, List<String> allowedRecipes) {
        this.zhName = zhName;
        this.personalityEn = personalityEn;
        this.speechNotes = speechNotes;
        this.allowedRecipes = allowedRecipes;
    }

    /** 从 Paper 的 Villager.Profession 枚举映射过来 */
    public static VillagerProfession fromMinecraftProfession(Villager.Profession mcProf) {
        if (mcProf == null) return NONE;
        if (mcProf == Villager.Profession.TOOLSMITH) return TOOLSMITH;
        if (mcProf == Villager.Profession.WEAPONSMITH) return WEAPONSMITH;
        if (mcProf == Villager.Profession.ARMORER) return ARMORER;
        if (mcProf == Villager.Profession.CLERIC) return CLERIC;
        if (mcProf == Villager.Profession.FARMER) return FARMER;
        if (mcProf == Villager.Profession.LIBRARIAN) return LIBRARIAN;
        if (mcProf == Villager.Profession.CARTOGRAPHER) return CARTOGRAPHER;
        if (mcProf == Villager.Profession.FLETCHER) return FLETCHER;
        if (mcProf == Villager.Profession.FISHERMAN) return FISHERMAN;
        if (mcProf == Villager.Profession.BUTCHER) return BUTCHER;
        if (mcProf == Villager.Profession.LEATHERWORKER) return LEATHERWORKER;
        if (mcProf == Villager.Profession.MASON) return MASON;
        if (mcProf == Villager.Profession.SHEPHERD) return SHEPHERD;
        if (mcProf == Villager.Profession.NITWIT) return NITWIT;
        return NONE;
    }

    /** 从命令行字符串（"blacksmith" / "铁匠" / "toolsmith"）解析 —— spawn 命令用 */
    public static VillagerProfession fromArg(String arg) {
        if (arg == null) return null;
        String a = arg.trim().toLowerCase();
        // 支持 "blacksmith" → 映射到 TOOLSMITH（MC 1.14 前的旧名兼容）
        return switch (a) {
            case "blacksmith", "铁匠", "toolsmith", "tool_smith" -> TOOLSMITH;
            case "weaponsmith", "武器匠", "weapon_smith" -> WEAPONSMITH;
            case "armorer", "盔甲匠" -> ARMORER;
            case "cleric", "牧师" -> CLERIC;
            case "farmer", "农夫", "农民" -> FARMER;
            case "librarian", "图书管理员", "图书" -> LIBRARIAN;
            case "cartographer", "制图师" -> CARTOGRAPHER;
            case "fletcher", "制箭师" -> FLETCHER;
            case "fisherman", "渔夫" -> FISHERMAN;
            case "butcher", "屠夫" -> BUTCHER;
            case "leatherworker", "皮匠", "皮革匠" -> LEATHERWORKER;
            case "mason", "石匠" -> MASON;
            case "shepherd", "牧羊人" -> SHEPHERD;
            case "nitwit", "傻子" -> NITWIT;
            default -> null;
        };
    }

    /** 转成 Paper 的 Villager.Profession —— spawn 时设置用 */
    public Villager.Profession toMinecraftProfession() {
        return switch (this) {
            case TOOLSMITH -> Villager.Profession.TOOLSMITH;
            case WEAPONSMITH -> Villager.Profession.WEAPONSMITH;
            case ARMORER -> Villager.Profession.ARMORER;
            case CLERIC -> Villager.Profession.CLERIC;
            case FARMER -> Villager.Profession.FARMER;
            case LIBRARIAN -> Villager.Profession.LIBRARIAN;
            case CARTOGRAPHER -> Villager.Profession.CARTOGRAPHER;
            case FLETCHER -> Villager.Profession.FLETCHER;
            case FISHERMAN -> Villager.Profession.FISHERMAN;
            case BUTCHER -> Villager.Profession.BUTCHER;
            case LEATHERWORKER -> Villager.Profession.LEATHERWORKER;
            case MASON -> Villager.Profession.MASON;
            case SHEPHERD -> Villager.Profession.SHEPHERD;
            case NITWIT -> Villager.Profession.NITWIT;
            case NONE -> Villager.Profession.NONE;
        };
    }
}
