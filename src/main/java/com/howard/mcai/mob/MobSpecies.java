package com.howard.mcai.mob;

import org.bukkit.Material;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Zombie;

import java.util.List;
import java.util.Map;

/**
 * 物种工具 —— 中央计划器多物种支持（SPEC M2）的公共常量与短语。
 * <p>
 * 气泡文案设计（SPEC 2.4 观感）：
 * 动物/怪物气泡**只显示 4-8 字的碎片化短句**（拟声词/小动作），不做长篇叙述 ——
 * TextDisplay 浮在生物头顶空间有限，长句换行堆叠反而遮挡视线。
 * 每个物种每个情绪 3 条短语随机抽，避免反复念同一句。
 * <p>
 * 三类情绪池：
 * - scared：受惊/被打/逃跑时（如"哞——！"）
 * - pleased：喂食满足时（如"满意地嚼着"）
 * - idle：空闲氛围小动作（如"低头吃草"）—— 让世界有"活着"的感觉
 * <p>
 * 全部纯规则（无 LLM），保证动物/怪物的表现低成本且稳定。
 */
public final class MobSpecies {

    public static final String COW = "COW";
    public static final String SHEEP = "SHEEP";
    public static final String PIG = "PIG";
    public static final String CHICKEN = "CHICKEN";
    public static final String ZOMBIE = "ZOMBIE";

    /** 受惊/逃跑/被打 —— 短句池 */
    private static final Map<String, List<String>> SCARED = Map.of(
            COW, List.of("哞——！", "慌忙后退", "惊叫着躲开"),
            SHEEP, List.of("咩——！", "连连后退", "瑟瑟发抖"),
            PIG, List.of("哼唧！", "撒腿就跑", "慌慌张张"),
            CHICKEN, List.of("咯咯咯！", "扑棱着翅膀", "四散奔逃"),
            ZOMBIE, List.of("嘶——", "死盯着你", "低吼着逼近")
    );

    /** 喂食满足 —— 短句池 */
    private static final Map<String, List<String>> PLEASED = Map.of(
            COW, List.of("满意地嚼着", "哞~", "蹭了蹭你的手"),
            SHEEP, List.of("咩~", "大口吃草", "蹭了蹭你"),
            PIG, List.of("哼~", "呼噜呼噜", "拱着鼻子"),
            CHICKEN, List.of("咕咕~", "哒哒啄食", "吃得正欢")
    );

    /** 空闲氛围 —— 短句池（玩家在旁边但没互动时偶尔冒一句） */
    private static final Map<String, List<String>> IDLE = Map.of(
            COW, List.of("低头吃草", "甩了甩尾巴", "悠闲地嚼着草"),
            SHEEP, List.of("低头啃草", "咩咩叫了两声", "抬头看了看你"),
            PIG, List.of("拱了拱泥土", "哼哼着溜达", "在泥里打了个滚"),
            CHICKEN, List.of("咯咯啄地", "扑腾扑腾翅膀", "低头啄食"),
            ZOMBIE, List.of("低吼着游荡", "漫无目的地走", "朝这边看了一眼")
    );

    private MobSpecies() {
    }

    /** 实体类型 → 物种 ID；不支持的返回 null */
    public static String of(LivingEntity e) {
        if (e instanceof Cow) return COW;
        if (e instanceof Sheep) return SHEEP;
        if (e instanceof Pig) return PIG;
        if (e instanceof Chicken) return CHICKEN;
        if (e instanceof Zombie) return ZOMBIE;
        return null;
    }

    /** 动物（非怪物）？动物走逃跑/喂食逻辑，僵尸走记仇逻辑 */
    public static boolean isAnimal(String species) {
        return !ZOMBIE.equals(species);
    }

    /** 随机一条受惊短句（气泡不带头名 —— 气泡就浮在生物头顶，玩家看得见是什么动物） */
    public static String scaredPhrase(String species) {
        return pick(SCARED.get(species), "受惊地退开");
    }

    /** 随机一条喂食满足短句 */
    public static String pleasedPhrase(String species) {
        return pick(PLEASED.get(species), "吃得正欢");
    }

    /** 随机一条空闲氛围短句 */
    public static String idlePhrase(String species) {
        return pick(IDLE.get(species), "东张西望");
    }

    /** 玩家主手物品是不是该物种的食物（喂食判定） */
    public static boolean isFood(String species, Material m) {
        return switch (species) {
            case COW, SHEEP -> m == Material.WHEAT;
            case PIG -> m == Material.CARROT || m == Material.POTATO || m == Material.BEETROOT;
            case CHICKEN -> m == Material.WHEAT_SEEDS || m == Material.MELON_SEEDS
                    || m == Material.PUMPKIN_SEEDS || m == Material.BEETROOT_SEEDS;
            default -> false;
        };
    }

    /** 从短语池随机抽一条，池为空时用兜底文案 */
    private static String pick(List<String> pool, String fallback) {
        if (pool == null || pool.isEmpty()) return fallback;
        return pool.get((int) (Math.random() * pool.size()));
    }
}
