package com.howard.mcai.ai;

import java.util.Set;

/**
 * “脚下天气”统一判定 —— 全插件唯一的天气口径（2026-10-22，服主要求监听群系）。
 * <p>
 * 原版规则：天气是全世界统一的开关（在下/没下、打雷/不打雷），但**落到每个
 * 群系头上不一样**：沙漠、恶地、热带草原永远不降水；雪系群系降水是雪；
 * 下界和末地根本没有天气。旧实现只看世界开关，沙漠里的村民也会被告知
 * “下雨了” —— 本类把判定收成一条链：维度 → 群系（干旱/雪系）→ 世界开关。
 * <p>
 * 纯计算：只吃群系名/维度名字符串，不碰 Bukkit，方便单测与面板等外部复用。
 */
public final class LocalWeather {

    /** 村民脚下此刻真实的天气 */
    public enum Kind {
        /** 下界/末地：没有天气这个概念 */
        NONE,
        /** 没降水（含干旱群系在世界暴雨时：它那边就是没下） */
        CLEAR,
        RAIN,
        SNOW,
        THUNDER_RAIN,
        THUNDER_SNOW
    }

    /** 雪系群系（降水为雪）：Bukkit Biome 枚举名 */
    private static final Set<String> SNOWY_BIOMES = Set.of(
            "SNOWY_PLAINS", "SNOWY_TAIGA", "SNOWY_BEACH", "SNOWY_SLOPES",
            "ICE_SPIKES", "FROZEN_OCEAN", "FROZEN_RIVER", "DEEP_FROZEN_OCEAN",
            "FROZEN_PEAKS", "JAGGED_PEAKS", "GROVE");

    /** 干旱群系（原版永不降水）：沙漠、恶地全家、热带草原全家 */
    private static final Set<String> DRY_BIOMES = Set.of(
            "DESERT", "BADLANDS", "ERODED_BADLANDS", "WOODED_BADLANDS",
            "SAVANNA", "SAVANNA_PLATEAU", "WINDSWEPT_SAVANNA");

    private LocalWeather() {
    }

    public static boolean isSnowyBiome(String biomeName) {
        return biomeName != null && SNOWY_BIOMES.contains(biomeName);
    }

    public static boolean isDryBiome(String biomeName) {
        return biomeName != null && DRY_BIOMES.contains(biomeName);
    }

    /**
     * 判定脚下天气。
     *
     * @param environmentName 世界维度名（NORMAL / NETHER / THE_END）
     * @param biomeName       村民脚下群系名（Bukkit Biome 枚举名）
     * @param worldStorm      世界天气开关：在下
     * @param worldThundering 世界天气开关：在打雷
     */
    public static Kind resolve(String environmentName, String biomeName,
                               boolean worldStorm, boolean worldThundering) {
        if (environmentName != null && !"NORMAL".equals(environmentName)) {
            return Kind.NONE;
        }
        if (!worldStorm) return Kind.CLEAR;
        if (isDryBiome(biomeName)) return Kind.CLEAR;
        if (isSnowyBiome(biomeName)) {
            return worldThundering ? Kind.THUNDER_SNOW : Kind.SNOW;
        }
        return worldThundering ? Kind.THUNDER_RAIN : Kind.RAIN;
    }

    /** 此刻天气的一句话（速览/直答共用，干旱群系的晴由调用方按群系补注） */
    public static String zhNow(Kind kind) {
        return switch (kind) {
            case NONE -> "这地方没有天气变化";
            case CLEAR -> "晴朗，没下雨";
            case RAIN -> "下着雨";
            case SNOW -> "下着雪";
            case THUNDER_RAIN -> "下着暴雨，还打雷";
            case THUNDER_SNOW -> "下着大雪，还打雷";
        };
    }

    /** 是否正在降水（雨或雪，含雷暴） */
    public static boolean isPrecipitating(Kind kind) {
        return kind == Kind.RAIN || kind == Kind.SNOW
                || kind == Kind.THUNDER_RAIN || kind == Kind.THUNDER_SNOW;
    }

    /** 是否在打雷 */
    public static boolean isThundering(Kind kind) {
        return kind == Kind.THUNDER_RAIN || kind == Kind.THUNDER_SNOW;
    }
}
