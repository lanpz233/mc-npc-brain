package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;

/**
 * 职业徽章（素材阶段 5）—— 聊天台词与对话提示里村民名字前的小牌匾：
 * 图案与他商店柜台的招牌完全一致（同色 + 同徽记，make_badges.py 从柜台图裁的），
 * 玩家看聊天就知道在跟什么职业的人说话。字形在资源包字体 mcai_shop 里
 * （\uE120 通用、\uE121 起按职业序），只有开了资源包皮肤才显示，
 * 没装包时调用方不拼徽章，避免名字前出现方框字。
 */
public final class ProfessionBadge {

    /** 资源包皮肤是否启用（McaiPlugin 启动时按 config 写入；关 = 一切调用方不拼徽章） */
    private static volatile boolean enabled;

    private ProfessionBadge() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 某职业的徽章字（单字符字符串）；null/无业用通用牌匾 */
    public static String badgeOf(VillagerProfession p) {
        if (p == null) return "\uE120";
        return switch (p) {
            case FARMER -> "\uE121";
            case TOOLSMITH -> "\uE122";
            case WEAPONSMITH -> "\uE123";
            case ARMORER -> "\uE124";
            case CLERIC -> "\uE125";
            case LIBRARIAN -> "\uE126";
            case CARTOGRAPHER -> "\uE127";
            case FLETCHER -> "\uE128";
            case FISHERMAN -> "\uE129";
            case BUTCHER -> "\uE12A";
            case LEATHERWORKER -> "\uE12B";
            case MASON -> "\uE12C";
            case SHEPHERD -> "\uE12D";
            case NITWIT -> "\uE12E";
            default -> "\uE120";
        };
    }
}
