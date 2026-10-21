package com.howard.mcai.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行为模块注册表 —— 定义 LLM 可以调用的所有 tool（JSON Schema 格式）。
 * <p>
 * SPEC 第 2.6 节要求：函数数量 ≤ 15 个，参数只用 ID 和枚举，不让模型算坐标、拼字符串。
 * 原因：模型在高频 tick 里做"选择题"比"填空题"准确率高得多，M1 的 function calling 实测也证实了这点。
 *
 * M1 定义 8 个 tool：
 * <pre>
 * say(message)       → 说一句话（气泡 + 聊天框）
 * move_to(x,y,z)     → 平滑移动（简化版：走向目标方向）
 * flee()             → 朝反方向 setVelocity
 * wave()             → 挥手动画
 * offer_trade()      → 打开交易 GUI
 * look_at(target_id) → setRotation 看向目标
 * shake_head()       → 摇头动画 + 气泡 "Nah..."
 * idle()             → 什么都不做
 * </pre>
 *
 * 安全网：execute 阶段（DecisionExecutor）会再次校验 tool name 和参数，
 * 即使 LLM 输出了注册表里没有的函数名，也会被硬性校验拦截 → fallback idle。
 */
public final class BehaviorRegistry {

    /** 所有合法的 tool name 集合 —— DecisionExecutor 用来做硬性校验 */
    private static final Set<String> VALID_TOOL_NAMES = Set.of(
            "say", "move_to", "flee", "wave", "offer_trade", "look_at", "shake_head", "idle",
            "follow_player", "find_shelter", "learn", "nod",
            // SPEC 2.14 世界交互类 —— 落地在 WorldInteractionService，权限三档约束
            // 注意：没有 take_item —— 从玩家身上拿东西必须走"玩家亲口确认"的两步流程
            // （CentralPlanner.annotateGift → resolvePendingGift），不给模型直接扣货的口子
            "give_item", "break_block", "place_block",
            "teleport_to", "teleport", "equip",
            // GUI 右键交换的"同意"信号（参数由代码层钉死，模型只表态）
            "accept_swap"
    );

    /** 自主行动（需求驱动）允许的工具面 —— 收窄防误用：自主行动不该开店/拆家/学习 */
    private static final Set<String> AUTONOMY_TOOLS = Set.of(
            "say", "move_to", "follow_player", "look_at", "wave", "nod", "idle");

    /**
     * 生成 OpenAI /v1/chat/completions 所需的 tools 参数。
     * 返回 List<Map<String, Object>>，直接塞给 LLMClient.chat() 的第二个参数。
     *
     * 每个 tool 的结构：
     * <pre>
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "say",
     *     "description": "说一句话...",
     *     "parameters": { "type": "object", "properties": {...}, "required": [...] }
     *   }
     * }
     * </pre>
     */
    public static List<Map<String, Object>> toolSchemas() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // say
        tools.add(func("say", "说一句话给玩家听。头顶冒会话框 + 进聊天框。日常聊天、回答问题、闲扯都用这个。",
                Map.of("message", Map.of(
                        "type", "string",
                        "description", "要对玩家说的中文台词。最多两短句、≤30个中文字，自然口语，像真人面对面说话。"
                )),
                List.of("message")));

        // move_to
        tools.add(func("move_to", "移动到指定坐标附近。系统会用正经寻路自己绕墙、上台阶（单次最远 32 格）；"
                        + "这是个持续任务，你不用等它走完。对话中不能移动（要站着跟人说话）。",
                Map.of(
                        "x", Map.of("type", "number", "description", "目标 X 坐标"),
                        "y", Map.of("type", "number", "description", "目标 Y 坐标"),
                        "z", Map.of("type", "number", "description", "目标 Z 坐标")
                ),
                List.of("x", "y", "z")));

        // flee
        tools.add(func("flee", "从威胁源处逃跑。朝玩家反方向快跑几步。",
                Map.of(), List.of()));

        // wave
        tools.add(func("wave", "挥手打招呼。做一个招手的动画动作。",
                Map.of(), List.of()));

        // offer_trade —— 开自家商店界面 + 上架/下架管理。
        // 砍价不在这里：玩家在界面里自己选砍幅、插件守底线，模型无权改价（discount 已退役）。
        // 参数全可选：只开 GUI 就不传；要加/删商品才传
        tools.add(func("offer_trade",
                """
                对玩家打开你的商店界面。**只在玩家明确要看货/谈价格时使用！**
                日常聊天、打招呼、闲聊时调用它非常失礼 —— 那种情况一律用 say。
                这个函数可以动态改你的商店：
                - action="open"：打开商店界面（不传参数时默认）
                - action="offer"：上架一件商品（item 中文名，price 绿宝价格，count 给几个）
                - action="offer" + price=0：**免费送**（东西会直接放进对方背包，不进商店；但必须从你库存里出——没有库存就送不了，不许凭空变出来）
                - action="remove"：下架商品（item）
                **你不能改价、不能答应折扣**：砍价由玩家在商店界面里点按钮完成，系统守底线。
                玩家在聊天里跟你砍价时，告诉他打开商店用里面的砍价按钮，不要许诺任何价格。
                """,
                Map.of(
                        "action", Map.of("type", "string", "description", "操作类型：open(默认,打开商店), offer(上架), remove(下架)"),
                        "item", Map.of("type", "string", "description", "商品中文名，例如 铁剑/钻石镐/面包/绿宝石"),
                        "price", Map.of("type", "number", "description", "绿宝石价格（整数）。仅 offer 用：0=免费赠送"),
                        "count", Map.of("type", "number", "description", "一次给几个（offer 时可选，默认 1）"),
                        "enchant", Map.of("type", "string", "description",
                                "附魔类型（可选，中文）：锋利/效率/保护/力量/耐久/火焰附加/击退/海之眷顾 等。卖附魔物品时填；不填时也可把 item 名写成'附魔钻石剑'，系统会自动加合适附魔")
                ),
                List.of()));  // 全可选 —— 不传就是 open

        // look_at
        tools.add(func("look_at", "转头看向目标方向。",
                Map.of(
                        "target_id", Map.of(
                                "type", "string",
                                "description", "目标实体 ID，如 player_1"
                        )
                ),
                List.of("target_id")));

        // shake_head
        tools.add(func("shake_head", "摇头表示不赞同或拒绝。",
                Map.of(), List.of()));

        // nod —— 点头同意（肢体语言：NPC 会做个前跳的"点头"动作）
        tools.add(func("nod", "点头表示同意、认可。玩家说的事你觉得对/答应时用。",
                Map.of(), List.of()));

        // idle
        tools.add(func("idle", "什么都不做。表示当前这个 tick 不需要行为。",
                Map.of(), List.of()));

        // follow_player —— 让 NPC 持续跟随玩家（玩家让他"过来"、"跟我走"时调用）
        tools.add(func("follow_player", "持续跟着指定玩家走（保持约 3 格距离，最长跟 5 分钟；玩家离线、跑太远或你进入对话就自动停）。"
                        + "玩家让你跟我走时调用此函数。对话中不能启动跟随。",
                Map.of(
                        "player_id", Map.of("type", "string", "description", "目标玩家的 player_id (例如 player_xxx)，不传则默认附近的玩家")
                ),
                List.of()));  // 可选参数，不传就用默认

        // find_shelter —— 下雨时找遮雨处
        tools.add(func("find_shelter", "找最近的遮雨处（例如屋檐下、洞里）。下雨时玩家让你避雨调用此函数。",
                Map.of(), List.of()));

        // learn —— 学习机制：玩家/其他村民教会你的新知识，存进永久记忆
        tools.add(func("learn",
                """
                把刚从别人那里学到的新知识记到脑子里（永久记忆）。
                什么时候用：玩家或别的村民教会你一件你之前不知道的事（常识、见闻、小知识），
                而你决定相信/记住它时调用。
                注意：不感兴趣或半信半疑就别调；system 提示里会说明你现在信不信对方。
                存储时会自动加上出处（"听XX说"），content 只写知识本身，用短中文（≤30字）。
                重要：调用本工具时，你必须同时在回复正文（content）里输出一句中文台词，
                不能只调工具不说话。
                """,
                Map.of(
                        "content", Map.of(
                                "type", "string",
                                "description", "学到的新知识本体，短中文（≤30字）。例：夜影怪物怕光，举火把能赶走"
                        )
                ),
                List.of("content")));

        // ===== SPEC 2.14 世界交互类（真动手）—— 能做，但有限制：不能拆家、平民不能刷物品 =====

        // give_item —— 执行已迁入统一柜（NpcGiveItemTool），给不给由插件按规矩裁决；这里只保留模型可见的菜单项
        tools.add(func("give_item",
                "把你库存里的东西送给眼前玩家。给不给、给几个由系统按规矩定（好感门槛：普通物品≥10、稀有物品≥60；"
                        + "普通物品一次最多3个、稀有的一天最多1件；送食物要给自己留口粮），系统不达标会拒绝，你只负责表态。"
                        + "只能送库存里有的，神使库存不足时会凭空生成（会记审计）。调用时必须在 message 里说一句话。",
                Map.of(
                        "item", Map.of("type", "string", "description", "物品中文名，例如 面包/铁剑/绿宝石"),
                        "count", Map.of("type", "number", "description", "给几个（默认 1，最大 64）"),
                        "message", Map.of("type", "string", "description", "送东西时说的中文台词")
                ),
                List.of("item")));

        // accept_swap —— 同意玩家在背包界面提出的"手持物品换你 1 个物品"
        // 参数由代码层钉死（物品和数量都在点击时登记），模型只负责表态与台词，
        // 避免模型自行编造交换物/数量造成双方物品对不上。
        tools.add(func("accept_swap",
                "同意玩家在背包界面提出的交换：他用手里的物品换你的 1 个物品。"
                        + "只有系统提示存在待确认交换时才可调用；值不值由系统按双方价值与好感先算好"
                        + "（系统提示会告诉你结论），不划算时调用也会被拒绝。系统会一次性完成双方物品转移。",
                Map.of(
                        "message", Map.of("type", "string", "description", "同意交换时说的中文台词")
                ),
                List.of()));

        // break_block —— 职业白名单 + 领地保护（拆玩家的房子 = 拆家，必被拒）
        tools.add(func("break_block",
                "破坏一个方块（正常掉落）。限制很严：你只能破坏自己职业相关的方块（农夫收庄稼）；"
                        + "玩家的建筑/箱子/门绝不能碰 —— 拆家是重罪，系统会拒绝。神使除外。",
                Map.of(
                        "x", Map.of("type", "number", "description", "方块 X 坐标"),
                        "y", Map.of("type", "number", "description", "方块 Y 坐标"),
                        "z", Map.of("type", "number", "description", "方块 Z 坐标"),
                        "message", Map.of("type", "string", "description", "动手时说的中文台词")
                ),
                List.of("x", "y", "z")));

        // place_block —— 扣库存（神使免库存）
        tools.add(func("place_block",
                "在你面前的指定位置放一个方块（消耗你库存里的对应方块；神使不需要库存）。"
                        + "玩家的领地里不能乱放。调用时必须在 message 里说一句话。",
                Map.of(
                        "x", Map.of("type", "number", "description", "方块 X 坐标"),
                        "y", Map.of("type", "number", "description", "方块 Y 坐标"),
                        "z", Map.of("type", "number", "description", "方块 Z 坐标"),
                        "material", Map.of("type", "string", "description", "方块中文名，例如 圆石/橡木板/火把"),
                        "message", Map.of("type", "string", "description", "动手时说的中文台词")
                ),
                List.of("x", "y", "z", "material")));

        // teleport_to —— 传送到目标（他人 = 神使；玩家需聊天回"好"确认）
        tools.add(func("teleport_to",
                "传送。目标填玩家或村民的 id/名字：填自己相关坐标时等于瞬移过去；"
                        + "填别人时把对方拉到你身边 —— 这只有神使能做，且传玩家必须先征得同意（对方聊天回复'好'才生效）。"
                        + "调用时必须在 message 里说一句话。",
                Map.of(
                        "target_id", Map.of("type", "string", "description", "目标：player_开头id / 玩家名 / 村民名"),
                        "message", Map.of("type", "string", "description", "传送时说的中文台词（请对方确认时也用它）")
                ),
                List.of("target_id")));

        // teleport —— 传送自己到坐标（2.13 白名单"传送只传自己"，全等级允许）
        tools.add(func("teleport",
                "把你自己的身体瞬移到指定坐标（不走路）。调用时必须在 message 里说一句话。",
                Map.of(
                        "x", Map.of("type", "number", "description", "目标 X 坐标"),
                        "y", Map.of("type", "number", "description", "目标 Y 坐标"),
                        "z", Map.of("type", "number", "description", "目标 Z 坐标"),
                        "message", Map.of("type", "string", "description", "传送时说的中文台词")
                ),
                List.of("x", "y", "z")));

        // equip —— 穿装备/拿手上展示（消耗库存）
        tools.add(func("equip",
                "从你库存拿一件东西穿戴上或拿在手上展示（村民手里有货会更像真人）。"
                        + "slot 可填 mainhand(默认)/helmet/chestplate/leggings/boots。调用时必须在 message 里说一句话。",
                Map.of(
                        "item", Map.of("type", "string", "description", "物品中文名（必须在你库存里）"),
                        "slot", Map.of("type", "string", "description", "mainhand/helmet/chestplate/leggings/boots，默认 mainhand"),
                        "message", Map.of("type", "string", "description", "穿戴时说的中文台词")
                ),
                List.of("item")));

        return tools;
    }

    /**
     * 自主行动（需求驱动）用的收窄工具面 —— NeedsLayer 的"目标决策"调用专用。
     * 需求驱动 NPC 自己过日子：找吃/闲逛/找人聊/看看新鲜事，不该自主开店/拆家/学习，
     * 所以只给 表达 + 移动 类工具。
     */
    public static List<Map<String, Object>> autonomyToolSchemas() {
        List<Map<String, Object>> all = toolSchemas();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> tool : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            if (fn != null && AUTONOMY_TOOLS.contains(fn.get("name"))) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    /** 校验某个函数名是否在注册表里 —— DecisionExecutor 硬性校验时调用 */
    public static boolean isValidTool(String name) {
        return VALID_TOOL_NAMES.contains(name);
    }

    /**
     * 暴露合法工具名集合 —— 仅供 LLMClient 生成 fallback 正则用。
     * <p>
     * 为什么要有这个方法：之前 LLMClient 的 PY_FUNC_CALL / TOOL_NAME_IN_JSON
     * 各硬编码了一份 10 个工具名的清单，漏了 learn/nod，导致小模型 fallback 吐
     * 这两个工具时既识别不出 tool_call（丢调用）也剥不掉 JSON（原文进气泡）。
     * 白名单现在只在 VALID_TOOL_NAMES 一处维护，新增工具两个 Pattern 自动跟进。
     * Set.of 本身不可变，直接返回不破坏封装。
     */
    public static Set<String> validToolNames() {
        return VALID_TOOL_NAMES;
    }

    // ===== 内部辅助方法：构建 tool schema =====

    /** 构建一个 function tool 的完整 Map */
    private static Map<String, Object> func(String name, String desc,
                                             Map<String, Object> properties,
                                             List<String> required) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        Map<String, Object> function = new HashMap<>();
        function.put("name", name);
        function.put("description", desc);
        function.put("parameters", parameters);

        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return tool;
    }
}
