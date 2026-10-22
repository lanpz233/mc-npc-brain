package com.howard.mcai.planner;

import java.util.regex.Pattern;

/**
 * 承诺守卫的纯判定 —— 台词里的话有没有在“许诺一件没真做成的事”。
 * <p>
 * 为什么单独成类：判定只用正则、不碰任何 Bukkit 状态，抽出来才能离线单测锁死
 * （CentralPlanner 的静态初始化会碰 Paper 注册表，离线环境类都加载不了——
 * 这是配方构造类逻辑测不到的老教训，纯计算必须放在不碰注册表的地方）。
 * CentralPlanner.stripUnbackedPromises 负责切句与执行结果的收集，逐句判定走这里。
 */
public final class PromiseGuard {

    private PromiseGuard() {
    }

    /** 承诺交付物件："我这就给你X" / "你拿去" / "送你了" */
    public static final Pattern PROMISE_GIVE = Pattern.compile(
            // 跨度放宽到 14 字且允许中间一个逗号：实测台词"俺这儿有现成的面包，送你俩"
            // 中间隔着 8 字 + 逗号，旧版 {0,8} 且禁逗号的窗口正好卡死 → 空头承诺漏过守卫
            "(?:我|俺|咱)[^。！？；]{0,14}(?:给你|送你|拿给你|递给你)"
                    // 无主语的全量交付与"已经到你手里"式说法（v0.4.5 补）：实测打劫场景
                    // "反正这绿宝石也没几个了，全给你！拿稳了，别摔了！"——没有"我"字、
                    // 不在旧名单里，整句漏过守卫，嘴上交了全部绿宝石、实际纹丝没动。
                    // "给你了"靠婉拒判定兜底（"不给你"先被 refused 救下，不误伤）。
                    + "|(?:你拿去|你拿着|拿去吧|拿去用|送你了|归你了"
                    + "|全给你|都给你|全给您|都给您|给你了|都是你的|全是你的|拿稳了|拿好了)");

    /**
     * 承诺马上动身："我这就过去" / "我马上过来" / "我这就去找你"。
     * <p>
     * 尾部的动词必须真的是"朝玩家移动"——旧版把裸 "去/走" 也算承诺，导致
     * "我这就去把炉子灭了""我马上走去拿料"这类**和玩家无关**的句子被整句删掉，
     * 若整条回复只有这一句，就会静默变成 "..."（实测玩家收到空回复的根因）。
     */
    public static final Pattern PROMISE_MOVE = Pattern.compile(
            "(?:我|俺|咱)[^，。！？；]{0,6}(?:这就|马上|立刻|待会儿|等下|回头|一会儿)"
                    + "[^，。！？；]{0,6}(?:过去|过来|去找你|找你|陪你|跟着你|跟你走|去你那|到你那儿|到你那)");

    /** 承诺记住："我记住了" / "我记下了" */
    public static final Pattern PROMISE_REMEMBER = Pattern.compile(
            "(?:我|俺|咱)[^，。！？；]{0,4}(?:记住|记住了|记下了|记着了|记牢了)");

    /** 价格让步用语 —— "给你便宜点""给你打个折"谈的是价格不是交付实物，不归承诺守卫管 */
    public static final Pattern PRICE_TALK = Pattern.compile("便宜|打折|优惠|少收|降价|算你");

    /** 婉拒/否定说法 —— 命中就不是承诺（老实拒绝不能当空头支票删掉） */
    public static final Pattern GIVE_REFUSAL = Pattern.compile(
            "不给你|不给|没给你|没给|没拿|别想|想得美|下回|下次|不能给|不送|自己留着");

    /**
     * 单句判定：这句话是否在许诺一件本轮没真做成的事。
     *
     * @param reallyGave    本轮 give_item 是否真的把东西交出去了
     * @param reallyMoved   本轮是否真的移动了（对话中腿被锁住，恒为未兑现）
     * @param reallyLearned 本轮 learn 是否真的写进了永久记忆
     */
    public static boolean isUnbackedPromise(String sentence, boolean reallyGave,
                                            boolean reallyMoved, boolean reallyLearned) {
        if (!reallyGave && !PRICE_TALK.matcher(sentence).find()) {
            // 婉拒句不是承诺："俺不能给你"这类话是老实拒绝，删掉反而只剩莫名其妙的沉默
            boolean refused = GIVE_REFUSAL.matcher(sentence).find();
            if (!refused && PROMISE_GIVE.matcher(sentence).find()) {
                return true;
            }
        }
        if (!reallyMoved && PROMISE_MOVE.matcher(sentence).find()) {
            return true;
        }
        if (!reallyLearned && PROMISE_REMEMBER.matcher(sentence).find()) {
            return true;
        }
        return false;
    }
}
