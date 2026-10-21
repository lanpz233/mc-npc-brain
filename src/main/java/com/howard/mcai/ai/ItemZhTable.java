package com.howard.mcai.ai;

import org.bukkit.Material;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 物品官方中文名全表 —— 治「有些物品没汉化」（2026-10-21 实测：商店里 stone slab /
 * stone bricks / bricks 直接漏英文）。
 * <p>
 * 旧版有两份手写小词典（TradeRegistry.ZH_TO_MATERIAL、PromptBuilder.ITEM_ZH），
 * 只收了常见物品，漏网的全部回退成英文小写。本表由 Mojang 官方 1.21.1 简体中文
 * 语言文件（zh_cn.json 的 item.minecraft.* / block.minecraft.*）离线生成，
 * 存放在 {@code src/main/resources/item-names-zh.tsv}（1480 条，另补 16 面墙旗帜
 * —— 官方无单独词条，与同色旗帜同名）。显示与理解都以本表为准，手写小词典只作
 * 口语别名（"绿宝""铁"）与兜底。
 */
public final class ItemZhTable {

    private static final Map<String, String> BY_MATERIAL_NAME = new LinkedHashMap<>();
    private static final Map<String, Material> TO_MATERIAL = new HashMap<>();

    static {
        load();
    }

    private ItemZhTable() {
    }

    private static void load() {
        try (InputStream in = ItemZhTable.class.getResourceAsStream("/item-names-zh.tsv")) {
            if (in == null) {
                System.err.println("[MC-AI] item-names-zh.tsv 缺失，物品名退回手写小词典");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String matName = line.substring(0, tab).trim();
                String zh = line.substring(tab + 1).trim();
                if (zh.isEmpty()) continue;
                BY_MATERIAL_NAME.put(matName, zh);
            }
        } catch (Exception e) {
            System.err.println("[MC-AI] item-names-zh.tsv 读取失败: " + e.getMessage());
        }
        for (Map.Entry<String, String> e : BY_MATERIAL_NAME.entrySet()) {
            try {
                TO_MATERIAL.putIfAbsent(e.getValue(), Material.valueOf(e.getKey()));
            } catch (IllegalArgumentException ignored) {
                // 表里比当前服务端版本新的物品名：跳过，不影响其余条目
            }
        }
    }

    /** Material → 官方中文名；表里没有返回 null（调用方自行回退） */
    public static String zhOf(Material m) {
        return m == null ? null : BY_MATERIAL_NAME.get(m.name());
    }

    /** 官方中文名 → Material；重名取表中先出现者，没有返回 null */
    public static Material materialOf(String zh) {
        return zh == null ? null : TO_MATERIAL.get(zh.trim());
    }

    /** 全部官方中文名（玩家打字找物品时用） */
    public static Set<String> zhKeys() {
        return Collections.unmodifiableSet(TO_MATERIAL.keySet());
    }

    /** 已加载条数（测试与自检用） */
    public static int size() {
        return BY_MATERIAL_NAME.size();
    }
}
