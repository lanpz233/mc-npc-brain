package com.howard.mcai.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存箱子取舍（纯计算，不碰 Bukkit —— 可离线单测）。
 * <p>
 * 原则（Fong 定）：假人回据点把收获存进自己的箱子，不管背包满没满都行；
 * 但身上要留住命的家当：口粮留 16 个、每类工具留 1 件最好的、火把留 8 根。
 * 输入输出都用「物品键（Material 名）→ 数量」，执行器负责和真背包对账转移。
 */
public final class DepositPlan {

    /** 吃的东西（按 Material 名后缀/全名粗判，与执行器的真判保持同口径）。 */
    static boolean isFood(String key) {
        return key.equals("BREAD") || key.equals("APPLE") || key.equals("CARROT")
                || key.equals("POTATO") || key.equals("BAKED_POTATO") || key.equals("BEETROOT")
                || key.equals("MELON_SLICE") || key.equals("SWEET_BERRIES")
                || key.endsWith("_STEW") || key.equals("COOKED_BEEF") || key.equals("COOKED_PORKCHOP")
                || key.equals("COOKED_CHICKEN") || key.equals("COOKED_MUTTON") || key.equals("COOKED_RABBIT")
                || key.equals("COOKED_COD") || key.equals("COOKED_SALMON") || key.equals("DRIED_KELP")
                || key.equals("GOLDEN_CARROT") || key.equals("PUMPKIN_PIE") || key.equals("COOKIE");
    }

    /** 工具类别（同类只留一件）：返回类别名，非工具返回 null。 */
    static String toolKind(String key) {
        if (key.endsWith("_PICKAXE")) return "PICKAXE";
        if (key.endsWith("_AXE") && !key.endsWith("_PICKAXE")) return "AXE";
        if (key.endsWith("_SWORD")) return "SWORD";
        if (key.endsWith("_SHOVEL")) return "SHOVEL";
        if (key.endsWith("_HOE")) return "HOE";
        if (key.equals("FISHING_ROD")) return "ROD";
        if (key.equals("SHEARS")) return "SHEARS";
        return null;
    }

    /** 材质档次（留"最好"的那件工具用）：木<石<铁<金<钻<下界合金。 */
    static int tier(String key) {
        if (key.startsWith("NETHERITE_")) return 6;
        if (key.startsWith("DIAMOND_")) return 5;
        if (key.startsWith("GOLDEN_")) return 4;
        if (key.startsWith("IRON_")) return 3;
        if (key.startsWith("STONE_")) return 2;
        if (key.startsWith("WOODEN_")) return 1;
        return 0;
    }

    /**
     * 算要存进箱子的量：在手的每种物品 → 存多少（0 不出现）。
     * inv = 物品键 → 在手总数（同键多格已合并）。
     */
    public static Map<String, Integer> toDeposit(Map<String, Integer> inv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        // 每类工具选出要"留"的那件（档次最高；并列先到先留）
        Map<String, String> keepTool = new LinkedHashMap<>();
        for (String key : inv.keySet()) {
            String kind = toolKind(key);
            if (kind == null) continue;
            String cur = keepTool.get(kind);
            if (cur == null || tier(key) > tier(cur)) keepTool.put(kind, key);
        }
        int foodKept = 0;
        boolean torchKept = false;
        // 固定按物品名排序遍历：口粮"总共留 16"要跨种类分配，先留谁取决于遍历顺序，
        // 入参是 HashMap/Map.of 时顺序不稳定（v0.7.1 测试实测暴露：同一份背包两次算出不同留法）
        java.util.List<Map.Entry<String, Integer>> entries = new java.util.ArrayList<>(inv.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Integer> e : entries) {
            String key = e.getKey();
            int n = e.getValue();
            if (n <= 0) continue;
            String kind = toolKind(key);
            if (kind != null && key.equals(keepTool.get(kind))) {
                // 留 1 件，多余的同件存掉
                if (n > 1) out.put(key, n - 1);
                continue;
            }
            if (isFood(key)) {
                int keep = Math.max(0, 16 - foodKept);
                int keptHere = Math.min(n, keep);
                foodKept += keptHere;
                if (n > keptHere) out.put(key, n - keptHere);
                continue;
            }
            if (key.equals("TORCH") && !torchKept) {
                torchKept = true;
                if (n > 8) out.put(key, n - 8);
                continue;
            }
            out.put(key, n);
        }
        return out;
    }

    private DepositPlan() {}
}
