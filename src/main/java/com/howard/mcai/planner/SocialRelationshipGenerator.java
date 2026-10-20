package com.howard.mcai.planner;

import com.howard.mcai.mob.VillagerProfession;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.mob.VillagerState.Relationship;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * 社会关系配对器 —— 在原版 MC 基础上给 AI 村民生成"夫妻 + 兄弟姐妹"关系。
 * <p>
 * 配对策略（用户确认：职业/地缘配对）：
 * <ul>
 *   <li><b>夫妻（spouse）</b>：异性（M+F）+ 同职业/同大类权重最高 + 距离越近加分越多，双向写入</li>
 *   <li><b>兄弟姐妹（sibling）</b>：不限性别 + 同职业权重最高 + 距离加成，每人最多 2 个</li>
 * </ul>
 * 铁律：
 * <ul>
 *   <li><b>幂等</b>：已有配偶/满 2 个兄弟的村民不重复配对 → 重启后 MemoryStore 恢复的关系不被覆盖</li>
 *   <li><b>宁缺毋滥</b>：无异性单身村民不强制配对；跨村庄（&gt;200 格）不认亲</li>
 *   <li>所有方法必须在 Paper 主线程调用（内部用实体 getLocation/distance）</li>
 * </ul>
 */
public final class SocialRelationshipGenerator {

    /** 跨村庄阈值：超过 200 格不配对（村庄之间不强行认亲） */
    private static final double MAX_PAIR_DISTANCE = 200.0;

    /** 每个村民最多兄弟姐妹数 */
    private static final int MAX_SIBLINGS = 2;

    /** 距离惩罚：每格 -0.3 分 */
    private static final double DISTANCE_PENALTY = 0.3;

    private SocialRelationshipGenerator() {
    }

    /** 主入口 —— 给所有"缺关系"的村民补关系（幂等，已配对的不动）。启动扫描后调用。
     *  @param nameProvider 姓氏 + 性别 → 全局唯一中文名 的函数（兄弟随姓改名用，须保留原性别，由调用方注入） */
    public static void assignRelationships(Map<UUID, VillagerState> villagers,
                                           BiFunction<String, String, String> nameProvider) {
        assignSpouses(villagers);
        assignSiblings(villagers, nameProvider);
        unifyAllSiblingSurnames(villagers, nameProvider);
        refreshRelationshipNames(villagers);
    }

    /** 运行中途新增村民时补配 —— 复用全量逻辑，幂等保证不覆盖已配对村民。 */
    public static void assignRelationshipsFor(VillagerState newcomer, Map<UUID, VillagerState> villagers,
                                              BiFunction<String, String, String> nameProvider) {
        assignRelationships(villagers, nameProvider);
    }

    // ===== 夫妻配对 =====

    private static void assignSpouses(Map<UUID, VillagerState> villagers) {
        List<VillagerState> singles = villagers.values().stream()
                .filter(SocialRelationshipGenerator::isUsable)
                .filter(vs -> !vs.hasSpouse())
                .toList();
        for (VillagerState a : singles) {
            // 前面配对可能已给 a 写入 spouse（b 侧先被配对时）
            if (a.hasSpouse()) continue;
            VillagerState best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (VillagerState b : singles) {
                if (b == a || b.hasSpouse()) continue;
                if (a.isMale() == b.isMale()) continue;   // 夫妻必须异性
                if (!sameWorld(a, b)) continue;
                double dist = dist(a, b);
                if (dist > MAX_PAIR_DISTANCE) continue;
                double score = 60.0 - dist * DISTANCE_PENALTY;  // 地缘基础分：越近越易成家
                if (a.profession() == b.profession()) score += 100;   // 同职业最合拍
                else if (sameTrade(a, b)) score += 60;               // 同行当（铁匠三件套）次之
                if (score > bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best != null) {
                pair(a, best, "spouse");
            }
        }
    }

    // ===== 兄弟姐妹配对 =====

    private static void assignSiblings(Map<UUID, VillagerState> villagers,
                                       BiFunction<String, String, String> nameProvider) {
        List<VillagerState> candidates = villagers.values().stream()
                .filter(SocialRelationshipGenerator::isUsable)
                .filter(vs -> vs.siblings().size() < MAX_SIBLINGS)
                .toList();
        for (VillagerState a : candidates) {
            // 补齐到上限：已满 2 个 / 前面配对已写满的跳过
            while (a.siblings().size() < MAX_SIBLINGS) {
                VillagerState best = null;
                double bestScore = Double.NEGATIVE_INFINITY;
                for (VillagerState b : candidates) {
                    if (b == a) continue;
                    if (b.siblings().size() >= MAX_SIBLINGS) continue;
                    // 同一人已配过兄弟（去重由 addRelationship 保证，这里只是避免空转）
                    if (b.hasRelationshipWith(a.getUniqueId().toString(), "sibling")) continue;
                    if (!sameWorld(a, b)) continue;
                    double dist = dist(a, b);
                    if (dist > MAX_PAIR_DISTANCE) continue;
                    double score = 40.0 - dist * DISTANCE_PENALTY;  // 地缘基础分（兄弟姐妹比夫妻更宽松）
                    if (a.profession() == b.profession()) score += 100;   // 同职业更像一家人
                    else if (sameTrade(a, b)) score += 40;
                    if (score > bestScore) {
                        bestScore = score;
                        best = b;
                    }
                }
                if (best == null) break;   // 没有合适的兄弟候选 → 收工
                pair(a, best, "sibling");
                // 兄弟随姓：兄弟姐妹是同一对爸妈的孩子，必须共用同一姓氏（用户约束：随爸或妈姓）
                unifySiblingSurname(a, best, villagers, nameProvider);
            }
        }
    }

    /**
     * 全量随姓统一 pass —— 遍历所有现存 sibling 关系，强制每组家人同姓。
     * <p>
     * 为什么需要独立于 {@link #assignSiblings}：
     * 重启后兄弟关系会从记忆文件恢复，这些村民已满 2 个兄弟，被 assignSiblings 的
     * "siblings() < 2" 过滤直接跳过 → 随姓逻辑对存量关系永不生效。
     * 这个 pass 不看数量、只按关系配对逐对统一；链式家族（A-B-C-D）需要多轮收敛，
     * 所以循环直到某轮没有任何改名（幂等，最多 5 轮防死循环）。
     */
    private static void unifyAllSiblingSurnames(Map<UUID, VillagerState> villagers,
                                                BiFunction<String, String, String> nameProvider) {
        for (int round = 0; round < 5; round++) {
            boolean anyChanged = false;
            Set<String> visited = new HashSet<>();
            for (VillagerState vs : villagers.values()) {
                if (!isUsable(vs)) continue;
                String aKey = vs.getUniqueId().toString();
                for (Relationship rel : vs.siblings()) {
                    String bKey = rel.targetUuid();
                    String pairKey = aKey.compareTo(bKey) < 0 ? aKey + "|" + bKey : bKey + "|" + aKey;
                    if (!visited.add(pairKey)) continue;
                    VillagerState other = villagers.get(UUID.fromString(bKey));
                    if (other == null || !isUsable(other)) continue;
                    if (unifySiblingSurname(vs, other, villagers, nameProvider)) anyChanged = true;
                }
            }
            if (!anyChanged) break;
        }
    }

    /**
     * 关系显示名重刷 —— 把所有关系的 targetName 按目标实体当前名字重新写一遍。
     * 任何来源的改名（ensureUniqueName 撞名改名、随姓改名）都会让别处关系里的旧名失效，
     * 这里统一兜底，保证 prompt 注入的名字永远与实体一致。
     */
    private static void refreshRelationshipNames(Map<UUID, VillagerState> villagers) {
        for (VillagerState vs : villagers.values()) {
            for (VillagerState.Relationship rel : vs.relationships()) {
                VillagerState target = villagers.get(UUID.fromString(rel.targetUuid()));
                if (target != null && isUsable(target)) {
                    vs.renameRelationshipTarget(rel.targetUuid(), nameOf(target));
                }
            }
        }
    }

    // ===== 工具 =====

    /** 双向写入关系（a 记 b、b 记 a），关系名用去色后的显示名 */
    private static void pair(VillagerState a, VillagerState b, String type) {
        a.addRelationship(type, b.getUniqueId().toString(), nameOf(b));
        b.addRelationship(type, a.getUniqueId().toString(), nameOf(a));
    }

    /** 实体有效 + 名字兜底可读（村民已移除的跳过） */
    private static boolean isUsable(VillagerState vs) {
        return vs.entity() != null && vs.entity().isValid();
    }

    private static boolean sameWorld(VillagerState a, VillagerState b) {
        return a.entity().getWorld().equals(b.entity().getWorld());
    }

    private static double dist(VillagerState a, VillagerState b) {
        return Math.sqrt(a.entity().getLocation().distanceSquared(b.entity().getLocation()));
    }

    /** 村民显示名（去 § 颜色码，缺名字退职业中文名） */
    private static String nameOf(VillagerState vs) {
        String n = vs.entity().getCustomName();
        return n != null && !n.isBlank() ? n.replaceAll("§.", "") : vs.profession().zhName;
    }

    /** 同大类职业：铁匠三件套算一个行当（Toolsmith/Weaponsmith/Armorer） */
    private static boolean sameTrade(VillagerState a, VillagerState b) {
        VillagerProfession p1 = a.profession();
        VillagerProfession p2 = b.profession();
        return isSmith(p1) && isSmith(p2);
    }

    private static boolean isSmith(VillagerProfession p) {
        return p == VillagerProfession.TOOLSMITH
                || p == VillagerProfession.WEAPONSMITH
                || p == VillagerProfession.ARMORER;
    }

    // ===== 兄弟随姓（用户约束：家人姓氏一致，孩子随爸或妈姓） =====

    /**
     * 提取中文名的姓氏：默认取第一个字；"老王/小李"这类"老/小+姓"的 2 字名剥前缀取真姓。
     * 名字池生成的是 3 字名（姓 + 2 字名），所以首个字符就是姓氏。
     */
    private static String surnameOf(VillagerState vs) {
        String name = nameOf(vs);
        if (name == null || name.isBlank()) return "";
        if (name.length() == 2 && (name.startsWith("老") || name.startsWith("小"))) {
            return name.substring(1);
        }
        return name.substring(0, 1);
    }

    /**
     * 让一对兄弟姐妹共用同一姓氏（随爸或妈姓，符合中国社会习惯）：
     * anchor 的姓氏作为家的姓，若 sibling 姓氏不同 → 用 nameProvider 生成"anchor 姓氏 + 随机名"改名，
     * 改名后必须全量同步所有关系记录里指向该兄弟的显示名（否则 prompt 显示旧名）。
     *
     * @return 是否真的改了名（供全量统一 pass 判断收敛）
     */
    private static boolean unifySiblingSurname(VillagerState anchor, VillagerState sibling,
                                               Map<UUID, VillagerState> villagers,
                                               BiFunction<String, String, String> nameProvider) {
        String anchorSurname = surnameOf(anchor);
        String siblingSurname = surnameOf(sibling);
        if (anchorSurname.isEmpty() || anchorSurname.equals(siblingSurname)) return false;
        // 改名保留当事人原有性别 —— 名字用字按性别抽，用错了会"变性"（兄弟变姐妹）
        String newName = nameProvider.apply(anchorSurname, sibling.gender());
        if (newName == null || newName.isBlank()) return false;
        sibling.entity().setCustomName("§6" + newName);
        sibling.entity().setCustomNameVisible(true);
        // 全量同步：所有村民关系里指向该兄弟的 targetName 换成新名
        for (VillagerState other : villagers.values()) {
            other.renameRelationshipTarget(sibling.getUniqueId().toString(), newName);
        }
        return true;
    }
}
