package com.howard.mcai.ai;

import com.howard.mcai.mob.VillagerState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天气账本注册表 —— 按世界名持有一本 {@link WeatherLedger}。
 * <p>
 * 采样与落盘由插件调度层（McaiPlugin）负责：周期性把各世界天气喂进账本、
 * 退出时存盘、启动时读回。本类只做持有与“给某个村民渲染摘要”。
 * 与 ProfessionBadge 的静态镜像是同一路数：让 PromptBuilder 在拼上下文时
 * 能直接拿到账本，不必层层传参。
 */
public final class WeatherLedgers {

    private static final Map<String, WeatherLedger> BY_WORLD = new ConcurrentHashMap<>();

    private WeatherLedgers() {
    }

    public static WeatherLedger getOrCreate(String worldName) {
        return BY_WORLD.computeIfAbsent(worldName, k -> new WeatherLedger());
    }

    public static WeatherLedger get(String worldName) {
        return BY_WORLD.get(worldName);
    }

    public static Map<String, WeatherLedger> all() {
        return BY_WORLD;
    }

    public static void replace(String worldName, WeatherLedger ledger) {
        BY_WORLD.put(worldName, ledger);
    }

    /** 给某个村民渲染他那本账（按他脚下群系换算雨/雪/没下）；无账本返回 null */
    public static String summaryFor(VillagerState vs) {
        org.bukkit.entity.Villager e = vs.entity();
        if (e == null || !e.isValid()) return null;
        WeatherLedger ledger = BY_WORLD.get(e.getWorld().getName());
        if (ledger == null) return null;
        String biome = e.getWorld().getBiome(e.getLocation()).name();
        return ledger.summaryZh(e.getWorld().getFullTime(),
                LocalWeather.isSnowyBiome(biome), LocalWeather.isDryBiome(biome));
    }
}
