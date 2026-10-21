package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.mob.VillagerState.ChatEntry;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prompt 组装器 —— 把人设 system prompt + 场景状态 + 对话历史拼成 messages 数组。
 * <p>
 * 关键设计决策：**人设 system prompt 必须用英文写**。
 * 实测发现 qwen3.5-9b 量化模型在中文 system prompt 下指令跟随会崩（角色跳出、吐问号），
 * 但英文 prompt 能完美激活中文对话输出。所以所有 system prompt 都用英文指令，
 * 对话内容自然由模型输出中文。
 *
 * messages 结构（OpenAI chat completion 格式）：
 * <pre>
 * [
 *   {"role": "system", "content": "英文人设 + 行为约束"},
 *   {"role": "user", "content": "场景状态 JSON"},
 *   {"role": "assistant", "content": "历史回复 1"},
 *   {"role": "user", "content": "玩家消息 1"},
 *   {"role": "assistant", "content": "历史回复 2"},
 *   {"role": "user", "content": "玩家消息 2"}
 * ]
 * </pre>
 */
public final class PromptBuilder {

    /** 配方查表实例（onEnable 时加载好，这里注入） */
    private static RecipeLookup recipeLookup;

    public static void setRecipeLookup(RecipeLookup lookup) {
        recipeLookup = lookup;
    }

    // ==== 职业 → 工作站方块（原版 vanilla 对照） ====
    // 村民白天会去自己的工位干活，"站在工位上"是比时刻推断更真实的活动信号。
    // NITWIT/NONE 无工位（缺省 null → 不判定）。
    // 注意：条目超过 10 个，Map.of 有 10 对上限，必须用 Map.ofEntries。
    private static final Map<VillagerProfession, org.bukkit.Material> WORKSTATION_MATERIAL = Map.ofEntries(
            Map.entry(VillagerProfession.FARMER, org.bukkit.Material.COMPOSTER),
            Map.entry(VillagerProfession.TOOLSMITH, org.bukkit.Material.SMITHING_TABLE),
            Map.entry(VillagerProfession.WEAPONSMITH, org.bukkit.Material.SMITHING_TABLE),
            Map.entry(VillagerProfession.ARMORER, org.bukkit.Material.BLAST_FURNACE),
            Map.entry(VillagerProfession.CLERIC, org.bukkit.Material.BREWING_STAND),
            Map.entry(VillagerProfession.LIBRARIAN, org.bukkit.Material.LECTERN),
            Map.entry(VillagerProfession.CARTOGRAPHER, org.bukkit.Material.CARTOGRAPHY_TABLE),
            Map.entry(VillagerProfession.FLETCHER, org.bukkit.Material.FLETCHING_TABLE),
            Map.entry(VillagerProfession.FISHERMAN, org.bukkit.Material.BARREL),
            Map.entry(VillagerProfession.BUTCHER, org.bukkit.Material.SMOKER),
            Map.entry(VillagerProfession.LEATHERWORKER, org.bukkit.Material.CAULDRON),
            Map.entry(VillagerProfession.MASON, org.bukkit.Material.STONECUTTER),
            Map.entry(VillagerProfession.SHEPHERD, org.bukkit.Material.LOOM));

    /** 工作站方块 → 中文名（inferActivity / compactScene 输出用） */
    private static final Map<org.bukkit.Material, String> WORKSTATION_ZH = Map.ofEntries(
            Map.entry(org.bukkit.Material.COMPOSTER, "堆肥桶"),
            Map.entry(org.bukkit.Material.SMITHING_TABLE, "锻造台"),
            Map.entry(org.bukkit.Material.BLAST_FURNACE, "高炉"),
            Map.entry(org.bukkit.Material.BREWING_STAND, "酿造台"),
            Map.entry(org.bukkit.Material.LECTERN, "讲台"),
            Map.entry(org.bukkit.Material.CARTOGRAPHY_TABLE, "制图台"),
            Map.entry(org.bukkit.Material.FLETCHING_TABLE, "制箭台"),
            Map.entry(org.bukkit.Material.BARREL, "木桶"),
            Map.entry(org.bukkit.Material.SMOKER, "烟熏炉"),
            Map.entry(org.bukkit.Material.CAULDRON, "炼药锅"),
            Map.entry(org.bukkit.Material.STONECUTTER, "切石机"),
            Map.entry(org.bukkit.Material.LOOM, "织布机"));

    /** 脚下方块是否本职业工位（真实状态检测） */
    private static boolean onWorkstation(VillagerState vs, Villager e) {
        org.bukkit.Material ws = WORKSTATION_MATERIAL.get(vs.profession());
        if (ws == null) return false;
        return e.getLocation().getBlock().getType() == ws;
    }

    /** 头顶 1-2 格有实心屋顶 + 落地 → 判定在建筑内（比 lightLevel 更贴合"在屋子里"） */
    private static boolean isUnderRoof(Villager e) {
        org.bukkit.block.Block roof1 = e.getLocation().add(0, 1, 0).getBlock();
        org.bukkit.block.Block roof2 = e.getLocation().add(0, 2, 0).getBlock();
        return e.isOnGround() && (roof1.getType().isSolid() || roof2.getType().isSolid());
    }

    /** 脚下是否床（真实状态检测：睡在床上 / 站在床边） */
    private static boolean isOnBed(Villager e) {
        String m = e.getLocation().getBlock().getType().name();
        return m.endsWith("_BED") || m.equals("BED");
    }

    /**
     * 通用村民人设 prompt —— 按职业 + 人格标签 + 家庭关系动态生成。
     * 每个职业有独立的 personality（从 VillagerProfession.personalityEn 取），
     * 每个 NPC 额外叠加从 personality_tags.json 抽出的 2-4 个独特性格标签，
     * 以及社会关系系统的性别 + 家庭关系（夫妻/兄弟姐妹）。
     *
     * @param profession        职业枚举（决定人设差异）
     * @param customName        村民的显示名（去掉 Minecraft 颜色代码 §x 后的纯文本）
     * @param personalityTagIds 已抽取的人格标签 id（决定"这个人是哪种人"）
     * @param state             村民运行时状态（取 gender + relationships 注入 Family & Social 段）
     */
    public static String buildVillagerSystemPrompt(VillagerProfession profession, String customName,
                                                   List<String> personalityTagIds,
                                                   VillagerState state) {
        String name = (customName != null && !customName.isBlank())
                ? customName.replaceAll("§.", "")  // 去掉颜色代码
                : switch (profession) {
                    case TOOLSMITH -> "Old Wang";
                    case WEAPONSMITH -> "Blade Li";
                    case ARMORER -> "Armorer Zhao";
                    case CLERIC -> "Cleric Zhang";
                    case FARMER -> "Old Li";
                    case LIBRARIAN -> "Scholar Chen";
                    case CARTOGRAPHER -> "Cartographer Sun";
                    case FLETCHER -> "Fletcher Zhou";
                    case FISHERMAN -> "Fisherman Qian";
                    case BUTCHER -> "Butcher Feng";
                    case LEATHERWORKER -> "Leather Wang";
                    case MASON -> "Mason Wu";
                    case SHEPHERD -> "Shepherd Chu";
                    case NITWIT -> "Nitwit";
                    case NONE -> "Villager";
                };

        String personality = profession.personalityEn;
        // 把中文的"说话风格"例子转成英文注释，LLM 更容易跟随（实测）
        String zhSpeechNotes = switch (profession) {
            case TOOLSMITH -> "loud boisterous Chinese (e.g., 叮当, 娃儿, 莫慌, 结实)";
            case WEAPONSMITH -> "cold sharp Chinese (e.g., 好刀, 够锋利, 淬火)";
            case ARMORER -> "nagging caring Chinese (e.g., 护甲, 保命, 别硬扛)";
            case CLERIC -> "calm pious Chinese (e.g., 施主, 莫怕, 主保佑)";
            case FARMER -> "grounded folksy Chinese (e.g., 节气, 收成, 好年头, 庄稼)";
            case LIBRARIAN -> "bookish pedantic Chinese (e.g., 阁下, 且慢, 按书上说)";
            case CARTOGRAPHER -> "dreamy adventurous Chinese (e.g., 远方, 宝藏, 没去过的地方)";
            case FLETCHER -> "terse practical Chinese (e.g., 好用, 结实, 一箭一个)";
            case FISHERMAN -> "boastful casual Chinese (e.g., 嗬, 好钓, 今儿个)";
            case BUTCHER -> "loud oily Chinese (e.g., 新鲜, 来块肉, 包你满意)";
            case LEATHERWORKER -> "quiet careful Chinese (e.g., 好皮, 耐磨, 能穿十年)";
            case MASON -> "stony blunt Chinese (e.g., 实心, 结实, 盖房子)";
            case SHEPHERD -> "soft warm Chinese (e.g., 咩, 小羊, 乖)";
            case NITWIT -> "silly confused Chinese (e.g., 啊? 啥? 嗯... 我想想... 又忘了)";
            case NONE -> "plain gossipy Chinese (e.g., 可不是, 你说咋整)";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(PromptTemplates.get("identity")
                .replace("{name}", name)
                .replace("{profession}", profession.zhName)
                .replace("{personality}", personality)
                .replace("{speech}", zhSpeechNotes));

        // 人格标签（personality_tags.json）—— 每个 NPC 独有的性格底色，中文直接拼进人设。
        // 没加载/没抽到标签时跳过，不污染 prompt（NPC 退回纯职业人设）。
        if (PersonalityTagLibrary.isLoaded() && personalityTagIds != null && !personalityTagIds.isEmpty()) {
            sb.append("\n").append(PersonalityTagLibrary.buildPromptTraits(personalityTagIds));
        }

        // 家庭 & 社交关系（社会关系系统）—— 性别 + 夫妻/兄弟姐妹。
        // 仅影响称呼与家庭话题（用户确认边界），不改变人格与职业人设。
        if (state != null) {
            sb.append("\n## Family & Social\n");
            sb.append("- You are a ").append(state.isFemale() ? "female" : "male")
                    .append(" villager — use fitting Chinese address for your gender (e.g. 老哥/大姐, 大叔/大婶), keep your profession's speech flavor.\n");
            if (state.spouse() != null) {
                sb.append("- Your spouse (丈夫/妻子) is ").append(state.spouse().targetName())
                        .append(" — you may mention them naturally in chat.\n");
            }
            if (!state.siblings().isEmpty()) {
                sb.append("- Your siblings (兄弟姐妹): ");
                List<VillagerState.Relationship> sibs = state.siblings();
                for (int i = 0; i < sibs.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(sibs.get(i).targetName());
                }
                sb.append(" — you may mention them naturally.\n");
            }
            if (state.spouse() == null && state.siblings().isEmpty()) {
                sb.append("- You are currently unmarried and have no siblings.\n");
            }
        }

        sb.append(PromptTemplates.get("world_facts"));
        sb.append(PromptTemplates.get("behavior_rules"));
        sb.append(PromptTemplates.get("shop_rules"));

        sb.append(PromptTemplates.get("knowledge"));

        sb.append(PromptTemplates.get("state_rules"));

        // 需求系统 + 权限等级（SPEC 2.14）—— 主动性的来源 + 行动边界，依赖运行时 state
        if (state != null) {
            var snap = state.needs().snapshot();
            StringBuilder needsEn = new StringBuilder();
            for (var e : snap.entrySet()) {
                if (needsEn.length() > 0) needsEn.append(", ");
                needsEn.append(e.getKey()).append("=").append(e.getValue().intValue());
            }
            sb.append("\n## Needs & Inner Drives (YOUR OWN BODY — ACT ON THEM)\n");
            sb.append("- Your needs (0-100, higher = more urgent): ").append(needsEn).append("\n");
            sb.append(PromptTemplates.get("needs_rules"));

            // 权限等级（平民/亲信/神使）—— 行动边界必须让模型知道，代码层也会硬拦
            // 无玩家上下文 → 传 null，用 legacyFavor 判定（保守）
            var rank = com.howard.mcai.security.PermissionManager.rankOfStatic(state, null);
            sb.append("\n## Permissions (rank: ").append(rank.zhName()).append(")\n");
            switch (rank) {
                case ENVOY -> sb.append(PromptTemplates.get("perm_envoy"));
                case CONFIDANT -> sb.append(PromptTemplates.get("perm_confidant"));
                case CIVILIAN -> sb.append(PromptTemplates.get("perm_civilian"));
            }

            // 背包（你的库存）—— 玩家索要物品时唯一能给的东西，防止模型凭空变出没库存的货。
            // 物品中文名 + 数量；规则与 give_item 的关系门槛一致（关系不够就婉拒）。
            sb.append("\n## My Backpack (你的背包 — 你真正拥有的物品)\n");
            var backpack = state.inventorySnapshot();
            if (backpack.isEmpty()) {
                sb.append("- Your backpack is empty right now.\n");
            } else {
                sb.append("- You currently own:");
                boolean firstBp = true;
                for (var e : backpack.entrySet()) {
                    sb.append(firstBp ? " " : ", ");
                    sb.append(materialToZh(org.bukkit.Material.matchMaterial(e.getKey())))
                            .append(" x").append(e.getValue());
                    firstBp = false;
                }
                sb.append("\n");
            }
            sb.append(PromptTemplates.get("backpack_rules"));
        }

        // 拼配方摘要（如果加载成功）
        if (recipeLookup != null && recipeLookup.isLoaded()) {
            sb.append("\n").append(recipeLookup.buildPromptSummary());
        }

        return sb.toString();
    }

    /**
     * 组装完整 messages 列表，直接传给 LLMClient.chat()。
     *
     * @param villagerState 村民的运行时状态（emotion + chatHistory）
     * @param nearPlayer    附近的玩家（可能是 null）
     * @return List<Map> of {role, content}，适合直接喂给 LLM
     */
    public static List<Map<String, String>> buildMessages(VillagerState villagerState, Player nearPlayer) {
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. System prompt（按职业 + 人格标签动态生成人设）
        String customName = villagerState.entity().getCustomName();
        String systemPrompt = buildVillagerSystemPrompt(villagerState.profession(), customName,
                villagerState.personalityTagIds(), villagerState);
        // 相关配方按需注入：全量配方表近 800 条不能整表塞，从最近对话里挑出刚提到的物品
        if (recipeLookup != null && recipeLookup.isLoaded()) {
            StringBuilder recent = new StringBuilder();
            var history = villagerState.chatHistory();
            for (int i = Math.max(0, history.size() - 8); i < history.size(); i++) {
                recent.append(history.get(i).content()).append('\n');
            }
            String relevant = recipeLookup.relevantFor(recent.toString(), 6);
            if (!relevant.isEmpty()) {
                systemPrompt = systemPrompt + "\n" + relevant;
            }
        }
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 2. 上下文（记忆 / 商店 / 场景）**合并成一条 user** ——
        // 旧版是 3 条连续 user（memory + shop + scene），头部 4 连 user，离 user↔assistant 交替更远；
        // 合并成一条后头部只剩"上下文 + 历史首条 user"2 连，prompt 也更紧凑。
        java.util.UUID pid = nearPlayer != null ? nearPlayer.getUniqueId() : null;
        StringBuilder ctx = new StringBuilder();

        // --- 2.0 环境速览（最高优先级）---
        // 场景 JSON 信息虽全，但埋在后面长文本里，弱模型常视而不见 → 编造昼夜/天气。
        // 这条中文大白话打在上下文最前面，并明确"以它为准"，堵住幻觉。
        ctx.append("### 当前情况 (CURRENT SITUATION — 开口前先以这条中文速览为准)\n");
        ctx.append(envBrief(villagerState)).append("\n");
        // 天气账本（全村共同记忆）：聊到过去的天气照账说，不许凭感觉编“昨晚下雨打雷”
        String ledgerSummary = WeatherLedgers.summaryFor(villagerState);
        if (ledgerSummary != null) ctx.append(ledgerSummary).append("\n");
        ctx.append("（下面的 json 只是更细的补充；凡与上面这条速览冲突的，一律以速览为准，"
                + "不许编造天气或昼夜。）\n");

        // --- 2.1 Memory (SPEC 3.4)：summary + facts（按玩家过滤），recent 自动从 chatHistory 取 ---
        // **空记忆也必须注入**：什么都不发时模型收到"你认识我？"会幻觉编造往事
        // （新村民被问认识不认 → 答"头回见"，而不是编"上次你拿铁剑"）
        ctx.append("\n### 我记得的事 (Memory)\n");
        String summary = villagerState.summary();
        List<String> facts = villagerState.factsFor(pid);   // 公共事实 + 该玩家自己的事实
        if (!summary.isEmpty()) {
            ctx.append("**Summary (earlier chat):** ").append(summary).append("\n");
        }
        if (!facts.isEmpty()) {
            ctx.append("**Key Facts (important events):**\n");
            for (int i = 0; i < facts.size(); i++) {
                ctx.append((i + 1)).append(". ").append(facts.get(i)).append("\n");
            }
        }
        if (summary.isEmpty() && facts.isEmpty()) {
            // 全新村民：明确告诉模型自己没跟任何人打过交道
            ctx.append("你是新来的村民，还没跟任何旅者打过交道。\n");
        } else if (nearPlayer != null && !villagerState.hasMet(nearPlayer.getUniqueId())) {
            // 有记忆但当前这位玩家没互动过（按 UUID 判定，替代旧的字串包含匹配）→ 声明第一次见面
            ctx.append("你对这位旅者(").append(nearPlayer.getName())
                    .append(")没有任何记忆——你们应该是第一次见面，不要编造往事。\n");
        }
        // 近期社交上下文（群聊/闲聊刚说过的话）—— 该 NPC 的 chatHistory 不含这些一次性回复，
        // 若不注入，模型会把刚在群体里说过话的村民当"初次见面"重新寒暄推销，衔接很突兀。
        String socialCtx = villagerState.recentSocialContext();
        if (socialCtx != null) {
            ctx.append("\n**你刚才在村里跟人聊天时说过:** ").append(socialCtx)
                    .append("\n（这位旅者刚才也在场。顺着刚才的话题自然接话即可，不必重新自我介绍或推销。)\n");
        }

        // --- 2.2 My Shop —— 把"当前生效"的配方列清单告诉 LLM ---
        // 必须用 buildCurrentRecipes()（深拷贝 + 动态价 + 上架商品），
        // 用 TradeRegistry 裸清单会导致 LLM 说基础价、GUI 却显示砍价后的价 → 对话与 GUI 不一致
        var recipes = villagerState.buildCurrentRecipes();
        if (!recipes.isEmpty()) {
            ctx.append("\n### 我的商店 (My Shop — REAL items in my GUI)\n");
            ctx.append("**These are the ACTUAL items you sell/buy. You MUST only mention items from this list.**\n\n");
            ctx.append("**我卖 (玩家给绿宝石换物品):**\n");
            for (var r : recipes) {
                if (r.getResult() != null && r.getResult().getType() != org.bukkit.Material.EMERALD) {
                    String zh = TradeRegistry.materialToZh(r.getResult().getType());
                    // 绿宝价 = 所有绿宝原料槽数量之和（>64 的价会拆成 64+余数两槽）
                    int emTotal = 0;
                    for (var ing : r.getIngredients()) {
                        if (ing.getType() == org.bukkit.Material.EMERALD) emTotal += ing.getAmount();
                    }
                    ctx.append("- ").append(zh).append(" x").append(r.getResult().getAmount())
                            .append(" → ").append(emTotal).append("绿宝\n");
                }
            }
            ctx.append("\n**我收 (玩家卖原料给我换绿宝石):**\n");
            for (var r : recipes) {
                if (r.getResult() != null && r.getResult().getType() == org.bukkit.Material.EMERALD
                        && !r.getIngredients().isEmpty()) {
                    String zh = TradeRegistry.materialToZh(r.getIngredients().get(0).getType());
                    ctx.append("- ").append(zh).append(" x").append(r.getIngredients().get(0).getAmount())
                            .append(" → ").append(r.getResult().getAmount()).append("绿宝\n");
                }
            }
            ctx.append("\n**规则:**");
            ctx.append("\n- 如果玩家问某件商品有没有 → 先查上面的清单！清单里没有的就说 '这个我这不卖哦'");
            ctx.append("\n- 说价格时必须用清单里的数字，别自己编");
            ctx.append("\n- 你只卖清单里有的东西，别超人设（农夫不卖铁剑！）");
            ctx.append("\n- 回答时自然说，别背清单\n");
        } else if (villagerState.profession() != VillagerProfession.NONE) {
            // 只有"该有交易清单却没有"才值得告警。
            // 无业/傻子村民（NONE）本来就没有工位和配方，每句对话都刷一条 WARN 会淹掉真正的错误。
            org.bukkit.Bukkit.getLogger().warning("§c[MC-AI] shopBlock 空！职业=" + villagerState.profession() + " 名字=" + villagerState.entity().getCustomName());
        }

        // --- 2.3 Scene context（场景状态 JSON，让模型知道现在发生了什么） ---
        ctx.append("\n").append(buildSceneContext(villagerState, nearPlayer));

        // --- 2.4 对话中"腿被锁住"硬约束 ---
        // 代码层在对话中冻结了 NPC 的移动（"站立不动 + 持续直视玩家"是项目的硬性要求，
        // 见 CentralPlanner 的站立不动约束），但模型不知道这件事，会答应"我这就过去"
        // 然后原地不动 —— 玩家看到的就是"NPC 光说不做"。所以必须在 prompt 里显式禁止
        // 它承诺任何移动类动作，让它老实说明自己走不开。
        if (villagerState.interactingPlayer() != null) {
            ctx.append("\n### 你现在动不了 (Movement lock — HARD RULE)\n");
            ctx.append("你正跟这位旅者面对面说话，**你的腿是锁住的**：这一轮你不能走过去、不能跟着走、"
                    + "不能跑开、不能换位置。call `move_to` / `follow_player` / `flee` / `find_shelter` "
                    + "也不会有任何效果。\n");
            ctx.append("所以玩家让你\"过来\"、\"跟我走\"、\"躲一下雨\"时，你要老实说明你现在走不开"
                    + "（比如说\"我这儿正跟你说着话呢，走不开\"），**绝不能答应\"我这就过去\"**，"
                    + "也不能说\"等下我再过去\"——你根本不会移动，这么说了就是在骗人。\n");
        }

        // --- 2.5 回复要求（钉在上下文最末尾，离生成最近 —— 小模型最听最后的话）---
        // 长度与防编造都放这里从源头约束（服主拍板：出口不砍字数，靠给模型的要求管住）。
        ctx.append("\n### 回复要求 (Reply requirements)\n");
        ctx.append("一到两句说完，总共不超过30个字，说完就停，不要为了凑话再补一句。\n");
        ctx.append("天气、昼夜、周围有谁，只认上面的速览和场景；没写的就不许编，"
                + "尤其不许凭空说下雨、天黑，也不许编对方身上带着什么东西。\n");
        ctx.append("对方指出你把看得见的事实（时间、天气）说错了，就照速览认了、改口，别嘴硬。\n");
        // 每轮都把“说出口的时间”钉进来（不只问时间时）：自发提到时间同样不许算。
        org.bukkit.World anchorWorld = villagerState.entity().getWorld();
        ctx.append("现在时间：").append(mcPeriodZh(anchorWorld.getTime()))
                .append(mcSpokenClock(anchorWorld.getTime()))
                .append("。要提时间就照这个说，不许自己估、不许换算。\n");
        // 玩家在问时间 → 把准确时间钉死在要求里：实测模型不看速览、非要“眯眼瞅天”
        // 估个错时间（如实际 08:10 却说六点二十六），被纠正还嘴硬 —— 问到就原样照报。
        String lastUserText = null;
        java.util.List<VillagerState.ChatEntry> hist = villagerState.chatHistory();
        for (int i = hist.size() - 1; i >= 0; i--) {
            if ("user".equals(hist.get(i).role())) { lastUserText = hist.get(i).content(); break; }
        }
        if (lastUserText != null && (lastUserText.contains("几点") || lastUserText.contains("几时")
                || lastUserText.contains("时间") || lastUserText.contains("什么时候"))) {
            ctx.append("玩家在问现在几点：现在就是 ").append(mcSpokenClock(anchorWorld.getTime()))
                    .append("，原样照着念这个时间，不许自己估、不许报别的时间。\n");
        }

        messages.add(Map.of("role", "user", "content", ctx.toString().trim()));

        // 4. Recent chat history（自动 ≤10 轮，addChatEntry 里已经压缩了旧的）
        for (ChatEntry entry : villagerState.chatHistory()) {
            messages.add(Map.of("role", entry.role(), "content", entry.content()));
        }

        return messages;
    }

    /**
     * 把当前场景状态压缩成 JSON —— 让模型"看懂"当前世界里发生了什么。
     * <p>
     * 包含：
     * 1. 村民自身状态（情绪、职业、位置）
     * 2. **游戏时间 + 时刻推断**（白天/黄昏/夜晚/日出）
     * 3. **附近怪物**（按类型计数）
     * 4. **村民当前在做什么**（职业 + 时刻 → 推断行为：白天铁匠在打铁，晚上牧师在教堂祈祷）
     * 5. 附近玩家状态
     */
    private static String buildSceneContext(VillagerState villagerState, Player nearPlayer) {
        org.bukkit.entity.Villager entity = villagerState.entity();
        org.bukkit.World world = entity.getWorld();
        org.bukkit.Location vLoc = entity.getLocation();

        StringBuilder sb = new StringBuilder();
        sb.append("```json\n{");
        sb.append("\n  \"entity\": \"").append(entity.getUniqueId().toString(), 0, 8).append("\",");
        sb.append("\n  \"profession\": \"").append(villagerState.profession().zhName).append("\",");

        // === NPC 自身状态 ===
        double healthPct = entity.getHealth() / entity.getMaxHealth() * 100;
        // 受击记录必须用 VillagerState 自己存的时间戳 ——
        // Entity.getLastDamage() 返回的是上次伤害的数值（如 5.0 颗心），不是时间，混用会算出垃圾值
        boolean wasRecentlyHit = villagerState.wasHitRecently();
        sb.append("\n  \"self\": {");
        sb.append("\n    \"health\": \"").append(StateTiers.health(healthPct)).append("\",");
        sb.append("\n    \"was_hit_recently\": ").append(wasRecentlyHit).append(",");
        sb.append("\n    \"on_fire\": ").append(entity.getFireTicks() > 0).append(",");
        sb.append("\n    \"in_water\": ").append(entity.isInWater()).append(",");
        sb.append("\n    \"in_lava\": ").append(entity.isInLava()).append(",");
        sb.append("\n    \"is_on_ground\": ").append(entity.isOnGround()).append(",");
        // 真实状态检测（情境感知核心）—— 睡觉时绝不推断"在地里干活"
        sb.append("\n    \"sleeping\": ").append(entity.isSleeping()).append(",");
        sb.append("\n    \"moving\": ").append(entity.getVelocity().lengthSquared() > 0.01).append(",");
        sb.append("\n    \"on_bed\": ").append(isOnBed(entity)).append(",");
        sb.append("\n    \"on_workstation\": ").append(onWorkstation(villagerState, entity)).append("");
        sb.append("\n  },");

        // === 情绪（原有的 fear/favor 压进 self 里） ===
        sb.append("\n  \"emotion\": {");
        sb.append("\n    \"fear\": \"").append(StateTiers.fear(villagerState.emotion().fear())).append("\",");
        sb.append("\n    \"favor\": \"").append(StateTiers.favor(
                villagerState.emotion().favor(nearPlayer != null ? nearPlayer.getUniqueId() : null))).append("\",");
        // 开放度：决定你对"别人教你东西"的接受程度（高=好奇容易信，低=固执慢热）
        sb.append("\n    \"openness\": \"").append(villagerState.openness() >= 0.7 ? "容易接受新事物"
                : villagerState.openness() >= 0.4 ? "还算开放" : "比较固执").append("\"");
        sb.append("\n  },");

        // === 需求系统（SPEC 2.14）—— 内在驱动，LLM 按需求 + 性格自主选行动 ===
        sb.append("\n  \"needs\": {");
        var needSnap = villagerState.needs().snapshot();
        boolean firstNeed = true;
        for (var e : needSnap.entrySet()) {
            if (!firstNeed) sb.append(",");
            // 定性状态给词不给数（v0.3.0 服主定）：给模型“饥饿 73”它要自己换算还常算错，
            // 给“有点饿”任何模型都不会理解错。数字只留给账目（库存数量、价格）
            sb.append("\n    \"").append(e.getKey()).append("\": \"")
                    .append(StateTiers.needTier(e.getKey(), e.getValue())).append("\"");
            firstNeed = false;
        }
        sb.append("\n  },");
        // === 权限等级（平民/亲信/神使）—— 行动边界的唯一事实来源 ===
        sb.append("\n  \"rank\": \"").append(com.howard.mcai.security.PermissionManager.rankOfStatic(
                villagerState, nearPlayer != null ? nearPlayer.getUniqueId() : null)).append("\",");
        // === NPC 库存（give_item 送礼只能出这里的货）===
        sb.append("\n  \"inventory\": {");
        var invSnap = villagerState.inventorySnapshot();
        if (invSnap.isEmpty()) {
            sb.append("}");
        } else {
            boolean firstInv = true;
            for (var e : invSnap.entrySet()) {
                if (!firstInv) sb.append(",");
                org.bukkit.Material invMat = org.bukkit.Material.matchMaterial(e.getKey());
                sb.append("\n    \"").append(materialToZh(invMat)).append("\": ").append(e.getValue());
                firstInv = false;
            }
            sb.append("\n  }");
        }
        sb.append(",");

        // === 位置 + 脚下/头顶环境 ===
        org.bukkit.block.Block below = vLoc.getBlock();
        org.bukkit.block.Block above = vLoc.clone().add(0, 2, 0).getBlock();
        String terrainTag = inferTerrain(world, vLoc);
        int lightLevel = world.getBlockAt(vLoc).getLightLevel();
        sb.append("\n  \"environment\": {");
        sb.append("\n    \"pos\": [").append(vLoc.getBlockX()).append(", ").append(vLoc.getBlockY())
                .append(", ").append(vLoc.getBlockZ()).append("],");
        sb.append("\n    \"biome\": \"").append(world.getBiome(vLoc).name().toLowerCase()).append("\",");
        sb.append("\n    \"terrain\": \"").append(terrainTag).append("\",");
        sb.append("\n    \"block_below\": \"").append(below.getType().name().toLowerCase()).append("\",");
        sb.append("\n    \"block_above\": \"").append(above.getType().name().toLowerCase()).append("\",");
        sb.append("\n    \"light_level\": ").append(lightLevel).append(",");
        sb.append("\n    \"indoors\": ").append(lightLevel < 8).append(",");
        // 建筑内判定升级：头顶实心屋顶 + 落地（比 lightLevel<8 更能代表"在屋子里"）
        sb.append("\n    \"real_indoors\": ").append(isUnderRoof(entity)).append("");
        sb.append("\n  },");

        // === 游戏时间（严格按 MC 官方时间规则换算）===
        // MC 的 1 天 = 24000 tick：tick 0 = 06:00 日出，6000 = 12:00 正午，
        // 12000 = 18:00 日落，13000 = 19:00 入夜（怪物开始刷新），18000 = 00:00 午夜。
        // 换算/分段统一走 mcClock / mcPeriod / mcIsDay —— 场景 JSON 与上下文最前面的
        // 中文速览（envBrief）必须同一口径，否则模型会同时看到两套互相矛盾的时间。
        long timeOfDay = world.getTime();
        String period = mcPeriod(timeOfDay);
        boolean isDaylight = mcIsDay(timeOfDay);
        boolean isRaining = world.hasStorm();
        // 脚下天气（维度→群系→世界开关的统一判定，见 LocalWeather）：场景里的
        // raining 从此是“这个村民头顶上真实的天气”，沙漠村民不再被告知在下雨
        LocalWeather.Kind localKind = LocalWeather.resolve(world.getEnvironment().name(),
                world.getBiome(vLoc).name(), isRaining, world.isThundering());
        sb.append("\n  \"world\": {");
        // 原始刻度（time_tick）已删（v0.3.0）：一天 24000 刻的原始数字模型根本换算不明白，
        // 只会瞎换算成错时间，是喂给它的干扰源 —— 时间只给人话（time_human + 口语锚点）
        sb.append("\n    \"time_human\": \"").append(mcClock(timeOfDay)).append("\",");
        sb.append("\n    \"period\": \"").append(period).append("\",");
        sb.append("\n    \"is_day\": ").append(isDaylight).append(",");
        sb.append("\n    \"raining\": ").append(LocalWeather.isPrecipitating(localKind)).append(",");
        sb.append("\n    \"local_weather\": \"").append(LocalWeather.zhNow(localKind)).append("\"");
        sb.append("\n  },");

        // === 附近怪物（8 格内，按类型计数） ===
        int zombies = 0, skeletons = 0, creepers = 0, endermen = 0, spiders = 0;
        for (org.bukkit.entity.LivingEntity e : world.getNearbyLivingEntities(entity.getLocation(), 8)) {
            if (e == entity || e instanceof Player) continue;
            switch (e.getType()) {
                case ZOMBIE -> zombies++;
                case SKELETON -> skeletons++;
                case CREEPER -> creepers++;
                case ENDERMAN -> endermen++;
                case SPIDER -> spiders++;
                default -> {}
            }
        }
        sb.append("\n  \"nearby_mobs\": {");
        sb.append("\n    \"zombie\": ").append(zombies).append(",");
        sb.append("\n    \"skeleton\": ").append(skeletons).append(",");
        sb.append("\n    \"creeper\": ").append(creepers).append(",");
        sb.append("\n    \"enderman\": ").append(endermen).append(",");
        sb.append("\n    \"spider\": ").append(spiders);
        sb.append("\n  },");

        // === 邻居村民（12 格内的其他村民，让 NPC 说"刚才跟老张聊过"；上限 3 个防 prompt 膨胀） ===
        StringBuilder neighbors = new StringBuilder();
        int neighborCount = 0;
        for (org.bukkit.entity.LivingEntity e : world.getNearbyLivingEntities(vLoc, 12)) {
            if (e == entity || !(e instanceof org.bukkit.entity.Villager v)) continue;
            if (neighborCount++ >= 3) break;
            if (!neighbors.isEmpty()) neighbors.append(", ");
            neighbors.append("{\"name\":\"").append(v.getCustomName() != null ? v.getCustomName().replaceAll("§.", "") : "Villager");
            neighbors.append("\", \"prof\":\"").append(VillagerProfession.fromMinecraftProfession(v.getProfession()).zhName);
            neighbors.append("\", \"dist\":\"").append(distWord(vLoc.distance(v.getLocation()))).append("\"}");
        }
        sb.append("\n  \"neighbor_villagers\": [").append(neighbors).append("],");

        // === 村民当前在做什么（真实状态优先 → 职业 + 时刻推断） ===
        String currentActivity = inferActivity(villagerState, period,
                zombies + skeletons + creepers + endermen + spiders,
                LocalWeather.isPrecipitating(localKind));
        sb.append("\n  \"current_activity\": \"").append(currentActivity).append("\",");

        // === 玩家状态 ===
        if (nearPlayer != null) {
            org.bukkit.Location pLoc = nearPlayer.getLocation();
            double distance = vLoc.distance(pLoc);
            sb.append("\n  \"player\": {");
            sb.append("\n    \"id\": \"player_").append(nearPlayer.getUniqueId().toString(), 0, 8).append("\",");
            sb.append("\n    \"name\": \"").append(nearPlayer.getName()).append("\",");
            sb.append("\n    \"distance\": \"").append(distWord(distance)).append("\",");
            sb.append("\n    \"health\": \"").append(StateTiers.playerHealth(
                    nearPlayer.getHealth() / Math.max(1.0, nearPlayer.getMaxHealth()) * 100)).append("\",");
            sb.append("\n    \"hunger\": \"").append(nearPlayer.getFoodLevel() >= 18 ? "看着不饿"
                    : nearPlayer.getFoodLevel() >= 10 ? "有点饿的样子" : "饿得脸色发青").append("\",");
            sb.append("\n    \"holding\": \"").append(getHoldingItemName(nearPlayer)).append("\",");
            sb.append("\n    \"equipment\": ").append(getEquipmentSummary(nearPlayer)).append(",");
            sb.append("\n    \"valuables\": ").append(getValuablesSummary(nearPlayer)).append(",");
            sb.append("\n    \"interacting\": ").append(villagerState.interactingPlayer() != null
                    && villagerState.interactingPlayer().equals(nearPlayer.getUniqueId())).append(",");
            // 当前这位玩家与我的关系：好感 + 等级（模型据此决定亲疏、要不要给东西）
            sb.append("\n    \"favor\": \"").append(StateTiers.favor(
                    villagerState.emotion().favor(nearPlayer.getUniqueId()))).append("\",");
            sb.append("\n    \"relation\": \"").append(com.howard.mcai.security.PermissionManager.rankOfStatic(
                    villagerState, nearPlayer.getUniqueId()).name()).append("\",");
            // 原版村庄声望（Paper API，服务端原生持久化）—— 与自存的 favor 是两个维度：
            // favor 是"我跟这个人的私人交情"，reputation 是"全村对他的看法"。
            // 只读注入，不改动。有这条第三方事实，模型就不会自己编"村里人都说你坏"。
            sb.append("\n    \"reputation\": ").append(describeReputation(entity, nearPlayer.getUniqueId()));
            sb.append("\n  }");
        }

        sb.append("\n}\n```\n");
        sb.append("\nBased on this state, decide ONE action to take NOW. Call a tool.");
        return sb.toString();
    }

    /**
     * 把原版村庄声望压成一行 JSON 注入场景上下文。
     * <p>
     * Paper 的 {@code Villager#getReputation(UUID)} 返回的就是服务端原生持久化的
     * "全村对这个玩家的看法"（交易/被治愈/被攻击都会自动累积），**零存储成本**，
     * 我们不需要自己再存一份。这里只读不改。
     * <p>
     * 折算方式与原版打分保持一致：MAJOR 档一次 ±5、MINOR 档一次 ±1，再给一个中文口语标签，
     * 让模型可以直接引用"村里人对他的印象"这类第三方事实，而不是凭空编造。
     * <p>
     * 村民已被卸载或接口异常时返回中性值 —— 不能因为读声望失败就打断整轮对话。
     */
    private static String describeReputation(org.bukkit.entity.Villager entity, java.util.UUID pid) {
        int majorPos = 0, minorPos = 0, majorNeg = 0, minorNeg = 0, trading = 0;
        try {
            var rep = entity.getReputation(pid);
            if (rep != null) {
                majorPos = rep.getReputation(com.destroystokyo.paper.entity.villager.ReputationType.MAJOR_POSITIVE);
                minorPos = rep.getReputation(com.destroystokyo.paper.entity.villager.ReputationType.MINOR_POSITIVE);
                majorNeg = rep.getReputation(com.destroystokyo.paper.entity.villager.ReputationType.MAJOR_NEGATIVE);
                minorNeg = rep.getReputation(com.destroystokyo.paper.entity.villager.ReputationType.MINOR_NEGATIVE);
                trading = rep.getReputation(com.destroystokyo.paper.entity.villager.ReputationType.TRADING);
            }
        } catch (Exception ignored) {
            // 读不到就当没有口碑，继续走原流程
        }
        int score = majorPos * 5 + minorPos - majorNeg * 5 - minorNeg;
        String tag = score >= 20 ? "村里人很感激他"
                : score >= 5 ? "村民们对他印象不错"
                : score <= -20 ? "全村基本都恨他"
                : score <= -5 ? "村民们对他有怨气"
                : "没什么特别的口碑";
        return "{\"score\":" + score + ", \"tag\":\"" + tag + "\""
                + ", \"major_positive\":" + majorPos + ", \"minor_positive\":" + minorPos
                + ", \"major_negative\":" + majorNeg + ", \"minor_negative\":" + minorNeg
                + ", \"trading\":" + trading + "}";
    }

    /**
     * 根据脚下 5 格方块类型推断地形标签。
     * 简单计数：草地多=grassland，石头多=cave，沙子多=desert，水附近=river/ocean 等。
     * 让 LLM 能说"在河边干活"、"这山洞里好黑"、"沙漠里太热了"。
     */
    private static String inferTerrain(org.bukkit.World world, org.bukkit.Location loc) {
        int grass = 0, sand = 0, stone = 0, water = 0, snow = 0, wood = 0, leaves = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    org.bukkit.Material m = world.getBlockAt(loc.clone().add(dx, dy, dz)).getType();
                    String name = m.name();
                    if (name.startsWith("GRASS") || name.equals("DIRT") || name.equals("PODZOL")) grass++;
                    else if (name.startsWith("SAND") || name.startsWith("RED_SAND")) sand++;
                    else if (name.startsWith("STONE") || name.startsWith("DEEPSLATE") || name.startsWith("COBBLESTONE")) stone++;
                    else if (name.startsWith("WATER") || name.startsWith("ICE")) water++;
                    else if (name.startsWith("SNOW") || name.startsWith("ICE") || name.equals("POWDER_SNOW")) snow++;
                    else if (name.startsWith("OAK_LOG") || name.startsWith("BIRCH_LOG") || name.startsWith("SPRUCE_LOG") || name.startsWith("JUNGLE_LOG")
                            || name.startsWith("DARK_OAK_LOG") || name.startsWith("ACACIA_LOG")) wood++;
                    else if (name.endsWith("LEAVES")) leaves++;
                }
            }
        }
        // 优先级：水 > 雪 > 石 > 沙 > 草 > 木 > 叶
        if (water > 10) return "near water";
        if (snow > 10) return "snowy";
        if (stone > 15 && grass < 5) return "rocky/cave";
        if (sand > 10) return "sandy/desert";
        if (grass > 15) return "grassland";
        if (wood + leaves > 10) return "in forest";
        return "plains";
    }

    /**
     * 根据【真实状态优先】+ 职业 + 时刻 + 怪物密度 → 推断村民"现在在干啥"。
     * 这个推断会塞进 scene JSON，让 LLM 把行为融入对话：
     * - 真实状态：在睡觉 → "在床上睡觉"（修复"睡觉时聊收麦"幻觉）
     * - 真实状态：站在自己工位上 → "在X边干活"
     * - 铁匠 + noon + 0 怪物 → "正在打铁"
     * - 农夫 + dawn → "准备去地里干活"
     */
    private static String inferActivity(VillagerState vs, String period, int mobCount, boolean raining) {
        Villager e = vs.entity();
        // 优先级 0（真实状态）：睡觉就是睡觉，绝不推断成"在地里干活"
        if (e.isSleeping()) return "在床上睡觉";
        // 优先级 0.5（真实状态）：站在本职业工位上 = 正在干活（工位比时刻推断更可信）
        if (onWorkstation(vs, e)) {
            org.bukkit.Material ws = WORKSTATION_MATERIAL.get(vs.profession());
            return "在" + WORKSTATION_ZH.getOrDefault(ws, "工位") + "边干活";
        }
        // 优先级 1：夜晚有怪物 → 想躲
        if ("night".equals(period) && mobCount > 0) {
            return "害怕夜里的怪物，想回家躲起来";
        }
        // 优先级 2：下雨 → 进屋躲雨
        if (raining) {
            return "在屋里躲雨";
        }
        // 优先级 3：黎明/黄昏 → 准备回家
        if ("dawn".equals(period) || "dusk".equals(period)) {
            return "准备回家休息了";
        }

        // 按职业分时间段的典型行为
        return switch (vs.profession()) {
            case TOOLSMITH, WEAPONSMITH, ARMORER -> switch (period) {
                case "morning", "noon", "afternoon" -> "正在打铁";
                case "night" -> "在铺子里休息";
                default -> "没事做";
            };
            case CLERIC -> switch (period) {
                case "morning", "noon", "afternoon" -> "在教堂帮忙";
                case "night" -> "在教堂祈祷";
                default -> "在教堂";
            };
            case FARMER -> switch (period) {
                case "dawn" -> "准备去地里干活";
                case "morning", "noon", "afternoon" -> "在地里干活";
                case "night" -> "在家里";
                default -> "休息";
            };
            case LIBRARIAN -> switch (period) {
                case "morning", "noon", "afternoon" -> "在看书";
                case "night" -> "在整理书架";
                default -> "在图书馆";
            };
            case CARTOGRAPHER -> switch (period) {
                case "morning", "noon", "afternoon" -> "在画地图";
                default -> "在铺子里";
            };
            case FLETCHER -> switch (period) {
                case "morning", "noon", "afternoon" -> "在削箭";
                default -> "在铺子里";
            };
            case FISHERMAN -> switch (period) {
                case "morning", "noon", "afternoon" -> "在钓鱼";
                default -> "在家";
            };
            case BUTCHER -> switch (period) {
                case "morning" -> "在准备开店";
                case "noon", "afternoon" -> "在卖肉";
                default -> "在家";
            };
            case LEATHERWORKER -> switch (period) {
                case "morning", "noon", "afternoon" -> "在做皮甲";
                default -> "在家";
            };
            case MASON -> switch (period) {
                case "morning", "noon", "afternoon" -> "在砌砖";
                default -> "在家";
            };
            case SHEPHERD -> switch (period) {
                case "morning", "noon", "afternoon" -> "在放羊";
                default -> "在家";
            };
            case NITWIT -> "啥也不会，瞎溜达";
            default -> "没事做";
        };
    }

    /**
     * 轻量场景行 —— 供结对闲聊 / 群聊注入的一行中文状态描述（≤30 字）。
     * 完整对话场景用 {@link #buildSceneContext} 的 JSON；闲聊/群聊 prompt 刻意保持简短，
     * 只取当前最相关的一条真实状态，让模型"聊当下在做的事"，避免对牛弹琴。
     */
    public static String compactScene(VillagerState vs) {
        Villager e = vs.entity();
        String base;
        if (e == null || !e.isValid()) {
            base = "你在村里闲逛";
        } else if (e.isSleeping()) {
            base = "你正在床上睡觉";
        } else {
            org.bukkit.Material ws = WORKSTATION_MATERIAL.get(vs.profession());
            if (ws != null && e.getLocation().getBlock().getType() == ws) {
                base = "你正站在" + WORKSTATION_ZH.getOrDefault(ws, "工位") + "旁边干活";
            } else if (isUnderRoof(e)) {
                base = "你在屋子里";
            } else {
                long timeOfDay = e.getWorld().getTime();
                // 夜晚边界同样按 MC 规则走 mcIsDay（tick 13000 = 19:00 入夜）
                base = mcIsDay(timeOfDay) ? "你在村里闲逛" : "夜里你在村里";
            }
        }
        // 天气与精确时段（与 envBrief 同口径）：简版场景原先只字不提天气，
        // 群聊里 NPC 只能瞎编（实测：外面下着雨，屋里的 NPC 张口说"雨刚停"，
        // 还把错话写进自己的聊天历史，后面正式对话也跟着圆谎）。
        // 群聊同样把真实天气钉死，源头上不给编造留空。
        if (e != null && e.isValid()) {
            org.bukkit.World w = e.getWorld();
            String bName = w.getBiome(e.getLocation()).name();
            base += "，现在是" + mcPeriodZh(w.getTime()) + "（" + mcSpokenClock(w.getTime()) + "），"
                    + LocalWeather.zhNow(LocalWeather.resolve(w.getEnvironment().name(), bName,
                            w.hasStorm(), w.isThundering()));
        }
        // 需求提示（SPEC 2.14）：最迫切需求越过 60 就附一句，让闲聊也有"身体感"
        String urgent = vs.needs().dominantUrgent(60);
        if (urgent != null) {
            base += "，你" + com.howard.mcai.mob.NeedsState.zhName(urgent) + "得厉害";
        }
        return base;
    }

    /** 冷群系判定（降水渲染成雪）：雪原/冻原/冰原/雪林/针叶林 —— envBrief 与 compactScene 共用 */
    static boolean isColdBiome(String biomeName) {
        return biomeName.contains("SNOW") || biomeName.contains("FROZEN")
                || biomeName.contains("ICE") || biomeName.contains("GROVE")
                || biomeName.contains("TAIGA");
    }

    /** 降水一句话：冷群系说雪、暖群系说雨（与玩家眼睛看到的渲染一致） */
    static String precipPhrase(boolean raining, boolean cold) {
        return raining ? (cold ? "正在下雪" : "正在下雨") : (cold ? "没有下雪" : "没有下雨");
    }

    // ===== MC 官方时间规则（tick → 时钟 / 时段），场景 JSON 与中文速览共用一套口径 =====
    // 1 个 MC 日 = 24000 tick：tick 0 = 06:00 日出，6000 = 12:00 正午，
    // 12000 = 18:00 日落，13000 = 19:00 入夜（怪物开始刷新），18000 = 00:00 午夜，
    // 23000 = 05:00 天边泛白，24000 回到 06:00。

    /** tick → 24 小时制时钟（"HH:MM"）。例：tick 13000 → "19:00"，tick 18000 → "00:00"。 */
    public static String mcClock(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        int hour = (int) ((t / 1000 + 6) % 24);
        int minute = (int) ((t % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hour, minute);
    }

    /** 0-59 的中文数字（一、十二、二十六……），口语化时间用 */
    private static String zhNum(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 10) return d[n];
        int tens = n / 10, ones = n % 10;
        return (tens == 1 ? "十" : d[tens] + "十") + (ones == 0 ? "" : d[ones]);
    }

    /**
     * tick → 说出口的时间（"八点十分" / "六点整" / "零点零五分"）。
     * <p>
     * 为什么需要：速览里是数字写法 08:10，模型要自己换算成中文口语再说出来，
     * 这一步换算正是翻车点（实测 MiMo 把 08:10 说成六点多，Qwen 正常）。
     * 直接把说出口的版本钉给模型，照着念就行，不给它算的机会。
     */
    public static String mcSpokenClock(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        int hour = (int) ((t / 1000 + 6) % 24);
        int minute = (int) ((t % 1000) * 60 / 1000);
        if (minute == 0) return zhNum(hour) + "点整";
        if (minute < 10) return zhNum(hour) + "点零" + zhNum(minute) + "分";
        return zhNum(hour) + "点" + zhNum(minute) + "分";
    }

    /** 距离（格）→ 活人话：定性信息给词不给数（与 StateTiers 同一原则） */
    public static String distWord(double d) {
        if (d <= 2) return "就在跟前";
        if (d <= 5) return "几步远";
        if (d <= 10) return "有点距离";
        if (d <= 20) return "挺远的";
        return "很远";
    }

    /**
     * tick → 英文时段标识。system prompt 的 State→Dialog Rules 就是按这些名字举例的
     * （如 {@code world.period="night"}），所以名字集合必须保持稳定。
     */
    public static String mcPeriod(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        return switch ((int) (t / 1000)) {
            case 0, 1 -> "dawn";                                        // 06:00-07:59 清晨
            case 2, 3, 4, 5 -> "morning";                               // 08:00-11:59 上午
            case 6, 7, 8 -> "noon";                                     // 12:00-14:59 中午
            case 9, 10, 11 -> "afternoon";                              // 15:00-17:59 下午
            case 12 -> "dusk";                                          // 18:00-18:59 黄昏（太阳正在落）
            case 13, 14, 15, 16, 17, 18, 19, 20, 21, 22 -> "night";      // 19:00-04:59 夜里
            case 23 -> "pre-dawn";                                      // 05:00-05:59 黎明前
            default -> "dawn";
        };
    }

    /** tick → 时段中文名（中文速览用）。 */
    public static String mcPeriodZh(long tick) {
        return switch (mcPeriod(tick)) {
            case "dawn" -> "清晨";
            case "morning" -> "上午";
            case "noon" -> "中午";
            case "afternoon" -> "下午";
            case "dusk" -> "黄昏";
            case "pre-dawn" -> "黎明前";
            default -> "夜里";
        };
    }

    /**
     * MC 时间规则：此刻算不算"白天"。
     * tick 13000（19:00）天色转暗、怪物开始刷新 → 从这一刻起算夜里，直到次日 06:00。
     * （旧版写 {@code tick < 14000}，会把 19:00-19:59 这段已经黑透的时间说成"天还亮着"。）
     */
    public static boolean mcIsDay(long tick) {
        long t = ((tick % 24000) + 24000) % 24000;
        return t < 13000;
    }

    /**
     * 中文环境速览（一句话大白话）—— 给弱模型的高优先级锚点。
     * <p>
     * {@link #buildSceneContext} 的 JSON 字段全，但埋在 Memory + Shop 之后的长文本里，
     * 小模型经常"看不见"，于是凭空编造昼夜/天气（实测：明明是夜里、没下雨，NPC 却说"下雨""天多亮"）。
     * 这里把最容易编错的几项 —— 昼夜（含精确时刻）、下雨、在屋里/露天、附近怪物、当前在干什么 ——
     * 用最直白的中文拼成一句，放在上下文最前面（也给主动招呼当锚点），让模型没机会算错或忽略。
     * 时间一律走 {@link #mcClock}/{@link #mcPeriod}/{@link #mcIsDay}，与场景 JSON 同口径。
     */
    public static String envBrief(VillagerState vs) {
        org.bukkit.entity.Villager e = vs.entity();
        if (e == null || !e.isValid()) return "你现在不在村里（实体未加载）。";

        org.bukkit.World world = e.getWorld();
        long timeOfDay = world.getTime();
        String periodEn = mcPeriod(timeOfDay);
        boolean night = !mcIsDay(timeOfDay);
        boolean raining = world.hasStorm();

        // 群系可能下雪还是下雨 —— MC 里冷群系把降水渲染成雪（"正在下雪"），暖群系才是雨。
        // 不区分的话，站在雪原上 NPC 会对着飘雪说"正在下雨"，玩家一眼就觉得假。
        // 另外雪地群系**平时**地上就有积雪（与"此刻正在下雪"无关），
        // 实测玩家会说"这边有雪"，NPC 便顺着把地形积雪当成天气（"雪天儿确实冷"），
        // 所以雪地群系要单独补一句"地上都是积雪"，把两种情况分清楚。
        String biomeName = world.getBiome(e.getLocation()).name();
        boolean snowy = biomeName.contains("SNOW") || biomeName.contains("FROZEN")
                || biomeName.contains("ICE") || biomeName.contains("GROVE");
        boolean cold = isColdBiome(biomeName);

        // 附近怪物（8 格内，与场景 JSON 口径一致）—— 决定"怕不怕"的锚点
        int zombies = 0, others = 0;
        for (org.bukkit.entity.LivingEntity le : world.getNearbyLivingEntities(e.getLocation(), 8)) {
            if (le == e || le instanceof Player) continue;
            switch (le.getType()) {
                case ZOMBIE -> zombies++;
                case SKELETON, CREEPER, ENDERMAN, SPIDER -> others++;
                default -> {}
            }
        }

        LocalWeather.Kind kind = LocalWeather.resolve(world.getEnvironment().name(), biomeName,
                raining, world.isThundering());
        StringBuilder sb = new StringBuilder();
        sb.append("现在是").append(mcPeriodZh(timeOfDay)).append("（").append(mcClock(timeOfDay)).append("）")
                .append(night ? "，天已经黑了" : "，天是亮的")
                // 天气走“脚下天气”统一判定（LocalWeather）：干旱群系在世界暴雨时也没雨，
                // 旧版只看世界开关 precipPhrase 会跟沙漠村民说“下雨了”
                .append("，").append(LocalWeather.zhNow(kind))
                .append(LocalWeather.isDryBiome(biomeName) ? "（这儿是干旱地带，从来不下雨）" : "")
                .append(isUnderRoof(e) ? "，你在屋内" : "，你在露天");
        // 雪地群系：地面本身就有积雪（≠ 此刻正在下雪），补一句免得模型拿它当天气
        if (snowy) sb.append("，这里是雪地群系，地上到处都是积雪");
        if (e.isSleeping()) sb.append("，你正躺在床上睡觉");
        if (zombies + others > 0) {
            sb.append("，附近有怪物（僵尸 ").append(zombies).append("，其它 ").append(others).append("）");
        }
        // 村历：天气账本里的“昨天/前天”要有锚，村民说“来这儿第几天”也对得上
        sb.append("。今天是这个世界第 ").append(world.getFullTime() / 24000 + 1).append(" 天");
        sb.append("。你现在").append(inferActivity(vs, periodEn, zombies + others,
                LocalWeather.isPrecipitating(kind))).append("。");
        return sb.toString();
    }

    /** 取玩家主手物品的可读名称（全中文映射，null 兜底） */
    private static String getHoldingItemName(Player player) {
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null) return "空";
        return materialToZh(hand.getType());
    }

    /**
     * Minecraft Material → 中文俗名。
     * NPC 要能说出"你拿着钻石剑"、"你身上有绿宝石"，所以物品名必须全中文。
     * 映射不到时 fallback 英文 ID（防 NPE）。
     *
     * 覆盖范围：武器/工具/盔甲四件套/货币矿物/常见食物/方块/怪物掉落。
     * 配方相关物品参考 RecipeLookup.MATERIAL_ZH（那边只覆盖合成配方用到的），
     * 这里做一张更大的表覆盖玩家身上可能出现的所有东西。
     */
    public static String materialToZh(org.bukkit.Material mat) {
        if (mat == null || mat.isAir()) return "空";
        // 官方中文名全表优先（与商店显示同一套名，玩家和村民对得上号）；手写 ITEM_ZH 只作兜底
        String official = ItemZhTable.zhOf(mat);
        if (official != null) return official;
        String key = mat.name().toLowerCase();
        return ITEM_ZH.getOrDefault(key, key.replace('_', ' '));
    }

    /**
     * 提取玩家装备摘要 —— 全套 6 件物品的中文名列表。
     * Paper 的 Player.getEquipment() 返回 PlayerEquipmentView 里主手/副手/头盔/胸甲/护腿/靴子。
     */
    private static String getEquipmentSummary(Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        java.util.List<String> parts = new java.util.ArrayList<>();
        // Paper 1.21.1 的盔甲方法（getHelmet 等）没穿时返回 null，不是 AIR → null + isAir() 会 NPE
        // 主手/副手 getType() 也可能先 null（极少见，兜底）
        org.bukkit.inventory.ItemStack mainHand = inv.getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir())
            parts.add("主手持" + materialToZh(mainHand.getType()));
        org.bukkit.inventory.ItemStack offHand = inv.getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir())
            parts.add("副手持" + materialToZh(offHand.getType()));
        org.bukkit.inventory.ItemStack helmet = inv.getHelmet();
        if (helmet != null && !helmet.getType().isAir())
            parts.add("头戴" + materialToZh(helmet.getType()));
        org.bukkit.inventory.ItemStack chestplate = inv.getChestplate();
        if (chestplate != null && !chestplate.getType().isAir())
            parts.add("身穿" + materialToZh(chestplate.getType()));
        org.bukkit.inventory.ItemStack leggings = inv.getLeggings();
        if (leggings != null && !leggings.getType().isAir())
            parts.add("腿穿" + materialToZh(leggings.getType()));
        org.bukkit.inventory.ItemStack boots = inv.getBoots();
        if (boots != null && !boots.getType().isAir())
            parts.add("脚穿" + materialToZh(boots.getType()));
        return "[" + String.join("，", parts) + "]";
    }

    /**
     * 扫玩家背包 —— 挑值钱的/有意义的物品（货币 + 关键工具 + 食物）。
     * 不返回全部 inventory（太杂），只返回"值得 NPC 注意"的那些。
     * 按 MC 世界观铁律：绿宝石/铁锭/金锭/钻石 是交易货币，必须优先列。
     */
    private static String getValuablesSummary(Player player) {
        org.bukkit.inventory.Inventory inv = player.getInventory();
        // 累计货币/物品数量（相同 Material 合并）
        java.util.Map<org.bukkit.Material, Integer> counts = new java.util.HashMap<>();
        // 值得列出来的材料白名单（不在名单里的背包杂物跳过）
        java.util.Set<org.bukkit.Material> watchList = java.util.Set.of(
                // === 交易货币（世界观铁律） ===
                org.bukkit.Material.EMERALD, org.bukkit.Material.IRON_INGOT,
                org.bukkit.Material.GOLD_INGOT, org.bukkit.Material.DIAMOND,
                org.bukkit.Material.IRON_BLOCK, org.bukkit.Material.GOLD_BLOCK,
                org.bukkit.Material.DIAMOND_BLOCK, org.bukkit.Material.EMERALD_BLOCK,
                // === 钻石级工具/武器（值钱） ===
                org.bukkit.Material.DIAMOND_SWORD, org.bukkit.Material.DIAMOND_PICKAXE,
                org.bukkit.Material.DIAMOND_AXE, org.bukkit.Material.DIAMOND_SHOVEL,
                org.bukkit.Material.DIAMOND_HOE,
                // === 铁级工具 ===
                org.bukkit.Material.IRON_SWORD, org.bukkit.Material.IRON_PICKAXE,
                org.bukkit.Material.IRON_AXE, org.bukkit.Material.IRON_SHOVEL,
                org.bukkit.Material.IRON_HOE,
                // === 盔甲 ===
                org.bukkit.Material.DIAMOND_HELMET, org.bukkit.Material.DIAMOND_CHESTPLATE,
                org.bukkit.Material.DIAMOND_LEGGINGS, org.bukkit.Material.DIAMOND_BOOTS,
                org.bukkit.Material.IRON_HELMET, org.bukkit.Material.IRON_CHESTPLATE,
                org.bukkit.Material.IRON_LEGGINGS, org.bukkit.Material.IRON_BOOTS,
                org.bukkit.Material.GOLDEN_HELMET, org.bukkit.Material.GOLDEN_CHESTPLATE,
                org.bukkit.Material.GOLDEN_LEGGINGS, org.bukkit.Material.GOLDEN_BOOTS,
                // === 食物 ===
                org.bukkit.Material.GOLDEN_APPLE, org.bukkit.Material.APPLE,
                org.bukkit.Material.BREAD, org.bukkit.Material.COOKED_BEEF,
                org.bukkit.Material.COOKED_PORKCHOP, org.bukkit.Material.COOKED_CHICKEN,
                org.bukkit.Material.BAKED_POTATO,
                // === 怪物掉落 ===
                org.bukkit.Material.ENDER_PEARL, org.bukkit.Material.BLAZE_ROD,
                org.bukkit.Material.STRING, org.bukkit.Material.FEATHER,
                org.bukkit.Material.BONE, org.bukkit.Material.GUNPOWDER,
                org.bukkit.Material.SLIME_BALL, org.bukkit.Material.ROTTEN_FLESH,
                // === 其他值钱物 ===
                org.bukkit.Material.SHIELD, org.bukkit.Material.BOW,
                org.bukkit.Material.ARROW, org.bukkit.Material.TOTEM_OF_UNDYING,
                org.bukkit.Material.ELYTRA,
                org.bukkit.Material.OBSIDIAN, org.bukkit.Material.ANVIL,
                org.bukkit.Material.CRAFTING_TABLE, org.bukkit.Material.FURNACE
        );
        for (org.bukkit.inventory.ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (!watchList.contains(stack.getType())) continue;
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        if (counts.isEmpty()) return "[]";

        // 按价值排序：钻石 > 金 > 铁 > 其他
        java.util.List<Map.Entry<org.bukkit.Material, Integer>> sorted = new java.util.ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int va = materialValue(a.getKey()), vb = materialValue(b.getKey());
            return Integer.compare(vb, va);
        });

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<org.bukkit.Material, Integer> e : sorted) {
            if (!first) sb.append(", ");
            sb.append(materialToZh(e.getKey())).append("x").append(e.getValue());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /** 物品价值权重 —— 用于背包物品排序（钻石 > 金 > 铁 > 其他） */
    private static int materialValue(org.bukkit.Material m) {
        return switch (m) {
            case DIAMOND, DIAMOND_BLOCK, DIAMOND_SWORD, DIAMOND_PICKAXE,
                 DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_HOE,
                 DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS,
                 TOTEM_OF_UNDYING, ELYTRA -> 400;
            case EMERALD, EMERALD_BLOCK -> 300;
            case GOLD_INGOT, GOLD_BLOCK, GOLDEN_APPLE,
                 GOLDEN_HELMET, GOLDEN_CHESTPLATE, GOLDEN_LEGGINGS, GOLDEN_BOOTS -> 200;
            case IRON_INGOT, IRON_BLOCK, IRON_SWORD, IRON_PICKAXE,
                 IRON_AXE, IRON_SHOVEL, IRON_HOE,
                 IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS -> 100;
            case BOW, SHIELD, ANVIL -> 50;
            default -> 10;
        };
    }

    /**
     * 扩展版物品中文映射表 —— 覆盖玩家身上可能出现的所有关键物品。
     * 比 RecipeLookup.MATERIAL_ZH 更全（那个只覆盖合成配方用到的）。
     * 用 Minecraft Material.name().toLowerCase() 作为 key。
     */
    private static final java.util.Map<String, String> ITEM_ZH = java.util.Map.ofEntries(
            // === 交易货币 ===
            java.util.Map.entry("emerald", "绿宝石"),
            java.util.Map.entry("emerald_block", "绿宝石块"),
            java.util.Map.entry("iron_ingot", "铁锭"),
            java.util.Map.entry("iron_block", "铁块"),
            java.util.Map.entry("gold_ingot", "金锭"),
            java.util.Map.entry("gold_block", "金块"),
            java.util.Map.entry("diamond", "钻石"),
            java.util.Map.entry("diamond_block", "钻石块"),

            // === 钻石武器 ===
            java.util.Map.entry("diamond_sword", "钻石剑"),
            java.util.Map.entry("diamond_pickaxe", "钻石镐"),
            java.util.Map.entry("diamond_axe", "钻石斧"),
            java.util.Map.entry("diamond_shovel", "钻石锹"),
            java.util.Map.entry("diamond_hoe", "钻石锄"),

            // === 铁武器 ===
            java.util.Map.entry("iron_sword", "铁剑"),
            java.util.Map.entry("iron_pickaxe", "铁镐"),
            java.util.Map.entry("iron_axe", "铁斧"),
            java.util.Map.entry("iron_shovel", "铁锹"),
            java.util.Map.entry("iron_hoe", "铁锄"),

            // === 石武器 ===
            java.util.Map.entry("stone_sword", "石剑"),
            java.util.Map.entry("stone_pickaxe", "石镐"),
            java.util.Map.entry("stone_axe", "石斧"),
            java.util.Map.entry("stone_shovel", "石锹"),

            // === 木武器 ===
            java.util.Map.entry("wooden_sword", "木剑"),
            java.util.Map.entry("wooden_pickaxe", "木镐"),
            java.util.Map.entry("wooden_axe", "木斧"),

            // === 盔甲（铁） ===
            java.util.Map.entry("iron_helmet", "铁头盔"),
            java.util.Map.entry("iron_chestplate", "铁胸甲"),
            java.util.Map.entry("iron_leggings", "铁护腿"),
            java.util.Map.entry("iron_boots", "铁靴子"),

            // === 盔甲（钻石） ===
            java.util.Map.entry("diamond_helmet", "钻石头盔"),
            java.util.Map.entry("diamond_chestplate", "钻石胸甲"),
            java.util.Map.entry("diamond_leggings", "钻石护腿"),
            java.util.Map.entry("diamond_boots", "钻石靴子"),

            // === 盔甲（金） ===
            java.util.Map.entry("golden_helmet", "金头盔"),
            java.util.Map.entry("golden_chestplate", "金胸甲"),
            java.util.Map.entry("golden_leggings", "金护腿"),
            java.util.Map.entry("golden_boots", "金靴子"),

            // === 盔甲（皮革/链甲） ===
            java.util.Map.entry("leather_helmet", "皮帽"),
            java.util.Map.entry("leather_chestplate", "皮甲"),
            java.util.Map.entry("leather_leggings", "皮护腿"),
            java.util.Map.entry("leather_boots", "皮靴"),
            java.util.Map.entry("chainmail_helmet", "锁子头盔"),
            java.util.Map.entry("chainmail_chestplate", "锁子胸甲"),
            java.util.Map.entry("chainmail_leggings", "锁子护腿"),
            java.util.Map.entry("chainmail_boots", "锁子靴子"),
            java.util.Map.entry("netherite_helmet", "下界合金头盔"),
            java.util.Map.entry("netherite_chestplate", "下界合金胸甲"),
            java.util.Map.entry("netherite_sword", "下界合金剑"),
            java.util.Map.entry("netherite_pickaxe", "下界合金镐"),

            // === 弓/弩/盾牌 ===
            java.util.Map.entry("bow", "弓"),
            java.util.Map.entry("crossbow", "弩"),
            java.util.Map.entry("arrow", "箭"),
            java.util.Map.entry("shield", "盾牌"),

            // === 食物 ===
            java.util.Map.entry("golden_apple", "金苹果"),
            java.util.Map.entry("apple", "苹果"),
            java.util.Map.entry("bread", "面包"),
            java.util.Map.entry("cooked_beef", "牛排"),
            java.util.Map.entry("cooked_porkchop", "熟猪排"),
            java.util.Map.entry("cooked_chicken", "熟鸡肉"),
            java.util.Map.entry("cooked_mutton", "熟羊肉"),
            java.util.Map.entry("cooked_cod", "熟鳕鱼"),
            java.util.Map.entry("cooked_salmon", "熟鲑鱼"),
            java.util.Map.entry("baked_potato", "烤土豆"),
            java.util.Map.entry("carrot", "胡萝卜"),
            java.util.Map.entry("golden_carrot", "金胡萝卜"),
            java.util.Map.entry("rotten_flesh", "腐肉"),
            java.util.Map.entry("sugar", "糖"),
            java.util.Map.entry("cake", "蛋糕"),

            // === 怪物掉落 ===
            java.util.Map.entry("ender_pearl", "末影珍珠"),
            java.util.Map.entry("blaze_rod", "烈焰棒"),
            java.util.Map.entry("string", "线"),
            java.util.Map.entry("feather", "羽毛"),
            java.util.Map.entry("bone", "骨头"),
            java.util.Map.entry("gunpowder", "火药"),
            java.util.Map.entry("slime_ball", "粘液球"),
            java.util.Map.entry("ghast_tear", "恶魂之泪"),
            java.util.Map.entry("magma_cream", "岩浆膏"),
            java.util.Map.entry("blaze_powder", "烈焰粉"),
            java.util.Map.entry("spider_eye", "蜘蛛眼"),
            java.util.Map.entry("ink_sac", "墨囊"),
            java.util.Map.entry("glowstone_dust", "荧石粉"),

            // === 矿物原材料 ===
            java.util.Map.entry("coal", "煤炭"),
            java.util.Map.entry("coal_block", "煤炭块"),
            java.util.Map.entry("lapis_lazuli", "青金石"),
            java.util.Map.entry("lapis_block", "青金石块"),
            java.util.Map.entry("redstone", "红石粉"),
            java.util.Map.entry("redstone_block", "红石块"),
            java.util.Map.entry("quartz", "石英"),
            java.util.Map.entry("amethyst_shard", "紫水晶碎片"),
            java.util.Map.entry("copper_ingot", "铜锭"),

            // === 工具 ===
            java.util.Map.entry("shears", "剪刀"),
            java.util.Map.entry("fishing_rod", "钓鱼竿"),
            java.util.Map.entry("flint_and_steel", "打火石"),
            java.util.Map.entry("water_bucket", "水桶"),
            java.util.Map.entry("lava_bucket", "岩浆桶"),
            java.util.Map.entry("bucket", "铁桶"),
            java.util.Map.entry("torch", "火把"),

            // === 方块/功能物品 ===
            java.util.Map.entry("stick", "木棍"),
            java.util.Map.entry("obsidian", "黑曜石"),
            java.util.Map.entry("anvil", "铁砧"),
            java.util.Map.entry("chipped_anvil", "受损铁砧"),
            java.util.Map.entry("damaged_anvil", "严重受损铁砧"),
            java.util.Map.entry("chest", "箱子"),
            java.util.Map.entry("crafting_table", "工作台"),
            java.util.Map.entry("furnace", "熔炉"),
            java.util.Map.entry("blast_furnace", "高炉"),
            java.util.Map.entry("smoker", "烟熏炉"),
            java.util.Map.entry("enchanting_table", "附魔台"),
            java.util.Map.entry("lectern", "讲台"),
            java.util.Map.entry("loom", "织布机"),
            java.util.Map.entry("stonecutter", "切石机"),
            java.util.Map.entry("cauldron", "炼药锅"),
            java.util.Map.entry("barrel", "木桶"),
            java.util.Map.entry("cobblestone", "圆石"),
            java.util.Map.entry("stone", "石头"),
            java.util.Map.entry("oak_log", "橡木原木"),
            java.util.Map.entry("oak_planks", "橡木板"),

            // === 珍贵 ===
            java.util.Map.entry("totem_of_undying", "不死图腾"),
            java.util.Map.entry("elytra", "鞘翅"),
            java.util.Map.entry("ender_chest", "末影箱"),
            java.util.Map.entry("book", "书"),
            java.util.Map.entry("enchanted_book", "附魔书"),
            java.util.Map.entry("experience_bottle", "附魔之瓶"),
            java.util.Map.entry("name_tag", "命名牌"),
            java.util.Map.entry("lead", "拴绳"),
            java.util.Map.entry("saddle", "鞍"),
            java.util.Map.entry("melon_slice", "西瓜片"),
            java.util.Map.entry("pumpkin_pie", "南瓜派"),
            java.util.Map.entry("honey_bottle", "蜂蜜瓶")
    );
}
