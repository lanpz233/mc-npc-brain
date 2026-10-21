package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerProfession;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

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

    /**
     * 把徽章拼到一段内容前面 —— 必须平级拼装：徽章与内容各自作为子组件挂到一个
     * 无样式的空根下。<b>绝不能</b>把内容 append 到徽章组件上：游戏的组件样式会
     * 从父传给子，徽章的 mcai_shop 字体会把整段内容一起染掉——那套字体只有牌匾
     * 字形、没有汉字，整句台词会变成满屏方块（2026-10-21 实测事故）。
     *
     * @param content 原内容组件（字体保持原样，通常是默认字体）
     * @param p       说话村民的职业
     * @return [徽章（mcai_shop 字体）, 空格, 原内容] 三个平级子组件组成的根组件
     */
    public static Component prependBadge(Component content, VillagerProfession p) {
        return Component.empty()
                .append(Component.text(badgeOf(p)).font(Key.key("minecraft", "mcai_shop")))
                .append(Component.space())
                .append(content);
    }
}
