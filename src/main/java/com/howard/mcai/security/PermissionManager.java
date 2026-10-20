package com.howard.mcai.security;

import com.howard.mcai.mob.VillagerProfession;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限判定器（SPEC 2.14 三档权限）—— 只做"能不能"的纯判定，不执行动作。
 * <p>
 * 判定顺序（WorldInteractionService 每个动作都先过这里）：
 * <ol>
 *   <li>神使：除"传送玩家需对方聊天确认"外全放行，调用方负责写审计</li>
 *   <li>亲信：favor ≥ 阈值时自动获得 —— 可进对方领地放置/使用，破坏仍走职业白名单</li>
 *   <li>平民：破坏 = 职业白名单 且 不在任何玩家领地内；放置 = 库存有货 且 领地外；
 *       传送 = 只传自己；给东西 = 只能出自己库存</li>
 * </ol>
 * <p>
 * 领地判定（无领地插件的简化实现）：以在线玩家的<b>床重生点</b>为圆心的领地半径范围。
 * 床是玩家在 MC 里"家"的最小锚点，比追踪方块放置记录便宜得多 ——
 * 验收用例"平民尝试破坏玩家房屋方块：拒绝执行"依赖它（房屋床周围 = 领地）。
 * <p>
 * 已知简化：EmotionState 的 favor 是单值（M1 未做按玩家分立），
 * 因此"亲信"判定为"对当前玩家 favor ≥ 阈值"，不区分是哪位玩家给的 favor。
 */
public final class PermissionManager {

    private final double confidantFavor;
    private final double territoryRadius;

    /** 亲信 favor 门槛默认值 —— PromptBuilder（无 config 引用的静态上下文）构建 prompt 用 */
    public static final double DEFAULT_CONFIDANT_FAVOR = 60.0;

    public PermissionManager(double confidantFavor, double territoryRadius) {
        this.confidantFavor = confidantFavor;
        this.territoryRadius = territoryRadius;
    }

    // ===== 等级判定 =====

    /**
     * NPC 相对某玩家的当前实际等级：神使（持久化，与玩家无关）> 亲信（该玩家 favor 派生）> 平民。
     * 传入 playerUuid 为 null（无玩家上下文，如控制台命令）→ 用 legacyFavor 判定。
     */
    public NpcRank rankOf(VillagerState vs, java.util.UUID playerUuid) {
        return rankOf(vs, playerUuid, confidantFavor);
    }

    /** 静态入口（PromptBuilder 拼 prompt 用，亲信门槛取默认值 60） */
    public static NpcRank rankOfStatic(VillagerState vs, java.util.UUID playerUuid) {
        return rankOf(vs, playerUuid, DEFAULT_CONFIDANT_FAVOR);
    }

    private static NpcRank rankOf(VillagerState vs, java.util.UUID playerUuid, double favorThreshold) {
        if (vs.rank() == NpcRank.ENVOY) return NpcRank.ENVOY;
        if (vs.emotion().favor(playerUuid) >= favorThreshold) return NpcRank.CONFIDANT;
        return NpcRank.CIVILIAN;
    }

    /** 是否神使 */
    public boolean isEnvoy(VillagerState vs) {
        return vs.rank() == NpcRank.ENVOY;
    }

    /** 是否某玩家的亲信（按该玩家自己的 favor 判定） */
    public boolean isConfidantOf(VillagerState vs, Player player) {
        return player != null && rankOf(vs, player.getUniqueId()) == NpcRank.CONFIDANT;
    }

    // ===== 领地判定 =====

    /**
     * 位置是否落在某在线玩家的领地内。
     *
     * @return 领地主人（玩家），不在任何领地返回 null
     */
    public Player territoryOwnerAt(Location loc) {
        for (Player p : loc.getWorld().getPlayers()) {
            if (!p.isOnline() || p.isDead()) continue;
            Location bed = p.getBedSpawnLocation();
            if (bed == null || !bed.getWorld().equals(loc.getWorld())) continue;
            if (bed.distanceSquared(loc) <= territoryRadius * territoryRadius) return p;
        }
        return null;
    }

    // ===== 破坏判定 =====

    /**
     * 职业破坏白名单（2.13 平民白名单 —— 职业限定破坏）。
     * 村民是生产者不是拆迁队：默认职业一个方块都不许拆，
     * 农夫例外（收庄稼是本职）。玩家房屋的木板/门/玻璃不在任何白名单 → 必然拒绝。
     */
    private static final Map<VillagerProfession, Set<Material>> BREAK_WHITELIST = Map.of(
            VillagerProfession.FARMER, Set.of(
                    Material.WHEAT, Material.CARROTS, Material.POTATOES,
                    Material.BEETROOTS, Material.PUMPKIN, Material.MELON,
                    Material.HAY_BLOCK, Material.PUMPKIN_STEM, Material.MELON_STEM));

    /**
     * NPC 能否破坏该方块。
     * 神使：任意方块（调用方写审计）。
     * 亲信/平民：领地外 + 职业白名单内。
     */
    public boolean canBreak(VillagerState vs, Block block) {
        if (isEnvoy(vs)) return true;
        if (territoryOwnerAt(block.getLocation()) != null) return false;  // 领地内一律不拆
        Set<Material> allow = BREAK_WHITELIST.get(vs.profession());
        return allow != null && allow.contains(block.getType());
    }

    /** 破坏拒绝原因（拒绝时给玩家/日志看的人话说明） */
    public String breakRefuseReason(VillagerState vs, Block block) {
        if (territoryOwnerAt(block.getLocation()) != null) return "这是玩家的房子附近，不能拆";
        return vs.profession().zhName + "不该拆这种方块";
    }

    // ===== 放置判定 =====

    /**
     * NPC 能否在该位置放方块（库存是否够由调用方按等级判断 —— 神使可凭空刷）。
     * 领地内：神使 / 领主亲信 放行；领地外：所有等级都放行（公共地盘）。
     */
    public boolean canPlace(VillagerState vs, Block block, Player nearbyPlayer) {
        if (isEnvoy(vs)) return true;
        Player owner = territoryOwnerAt(block.getLocation());
        if (owner == null) return true;
        return isConfidantOf(vs, owner);
    }

    // ===== 传送判定 =====

    /** 平民"传送只传自己"：非神使只能传自己，其他实体/玩家一律拒绝 */
    public boolean canTeleportOthers(VillagerState vs) {
        return isEnvoy(vs);
    }

    // ===== 物品稀有度（亲信"不许拿走 rare 以上物品"的门槛） =====

    /**
     * 是否 rare 以上物品 —— 复用 PromptBuilder.materialValue 的价值权重：
     * ≥100（钻石/铁级工具、下界合金、绿宝石、鞘翅、图腾等）视为 rare+。
     * 亲信用箱子/工作台时禁止取走这类物品（平民根本不许碰箱子）。
     */
    public boolean isRarePlus(Material m) {
        return itemValue(m) >= 100;
    }

    private static int itemValue(Material m) {
        return switch (m) {
            case DIAMOND, DIAMOND_BLOCK, DIAMOND_SWORD, DIAMOND_PICKAXE, DIAMOND_AXE,
                 DIAMOND_SHOVEL, DIAMOND_HOE, DIAMOND_HELMET, DIAMOND_CHESTPLATE,
                 DIAMOND_LEGGINGS, DIAMOND_BOOTS, TOTEM_OF_UNDYING, ELYTRA,
                 NETHERITE_INGOT, NETHERITE_SWORD, NETHERITE_PICKAXE -> 400;
            case EMERALD, EMERALD_BLOCK -> 300;
            case GOLD_INGOT, GOLD_BLOCK, GOLDEN_APPLE -> 200;
            case IRON_INGOT, IRON_BLOCK, IRON_SWORD, IRON_PICKAXE, IRON_AXE, IRON_SHOVEL,
                 IRON_HOE, IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS -> 100;
            default -> 10;
        };
    }

    // ===== 提示文案 =====

    /** 等级中文名（日志/反馈用） */
    public static String zhRank(NpcRank rank) {
        return rank.zhName();
    }

    /** 玩家可读的等级说明（/ai envoy list 输出用） */
    public List<String> rankRuleLines() {
        return List.of(
                "平民: 职业白名单破坏 / 送礼看库存 / 传送只传自己",
                "亲信: favor≥" + (int) confidantFavor + " → 进领地、用工作台箱子（不拿 rare+）",
                "神使: 完整权限，操作写 audit.log，可撤销");
    }
}
