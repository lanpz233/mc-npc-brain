package com.howard.mcai.ai;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 物品 ID ↔ Material 的互转（数据文件里用 "minecraft:iron_ingot" 这种字符串存）。
 * 用枚举名直转，不走注册表查询：单元测试环境里也能用，且拼错的 ID 返回 null 由调用方跳过。
 */
public final class ItemIds {

    private ItemIds() {
    }

    /** "minecraft:iron_ingot" / "iron_ingot" → Material；认不出来返回 null */
    public static Material materialOf(String id) {
        if (id == null) return null;
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        try {
            return Material.valueOf(path.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Material → "minecraft:iron_ingot" */
    public static String idOf(Material m) {
        return m == null ? null : "minecraft:" + m.name().toLowerCase(Locale.ROOT);
    }
}
