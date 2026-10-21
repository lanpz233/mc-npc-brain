package com.howard.mcai.security;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.TradeRegistry;
import com.howard.mcai.mob.VillagerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界交互执行器（SPEC 2.14）—— 新增 7 个"真动手"工具的落地实现。
 * <p>
 * 原则：能做，但有限制 —— NPC 不能拆家、平民不能刷物品。
 * 所有动作先过 {@link PermissionManager} 判定，神使动作再过 {@link AuditLog} 留痕。
 * <p>
 * 动作清单（注意：已无 take_item —— 从玩家身上拿东西只走"玩家亲口确认"的两步流程，
 * 不给模型直接扣货的口子，详见 {@link #takeFromPlayer}）：
 * <pre>
 * 2026-10-21 全部迁入统一工具柜（tool/tools/*Tool），本类只留执行体：
 * give_item    → NpcGiveItemTool（裁决 GiftPolicy）
 * break_block  → NpcBreakBlockTool（执行体 breakBlockAt）
 * place_block  → NpcPlaceBlockTool（执行体 placeBlockAt）
 * teleport     → NpcTeleportTool（执行体 teleportSelfTo）
 * teleport_to  → NpcTeleportToTool（执行体 teleportToTarget；确认走 consumeTeleportConfirm）
 * equip        → NpcEquipTool（执行体 equipItem）
 * 外加代码层直达通道：deliverFreeGift（免费送出库存）、takeFromPlayer（玩家确认后收礼）。
 * </pre>
 * <p>
 * 线程模型：所有执行体只在 Paper 主线程调用（统一分发器保证），
 * Paper API（breakNaturally/teleport/inventory）天然安全。
 */
public final class WorldInteractionService {

    private final McaiPlugin plugin;
    private final PermissionManager permissions;
    private final AuditLog auditLog;

    /**
     * 待确认的"传送玩家"请求 —— 神使要传玩家时，玩家必须在聊天里回"好"才执行。
     * key = 玩家 UUID（同一玩家同时只有一个待确认请求，新请求覆盖旧的）。
     */
    private record PendingPlayerTeleport(UUID npcUuid, String npcName, long expiryMs) {}

    private final Map<UUID, PendingPlayerTeleport> pendingTeleports = new ConcurrentHashMap<>();

    /** 确认词 —— 玩家回复这些短词之一才放行（配合 SPEC"传送玩家需其聊天确认'好'"） */
    private static final String[] CONFIRM_WORDS = {"好", "好呀", "好的", "好吧", "行", "可以", "嗯", "没问题", "来吧"};

    /** 世界交互动作半径（格）—— NPC 够不着太远的地方，防 LLM 传个千格外的坐标乱拆乱放 */
    private static final double ACTION_RANGE = 32.0;

    /**
     * 目标点是否在 NPC 动作半径内（同世界才比距离）。
     * break/place/teleport 的坐标都先过这一关：LLM 偶尔会编一个很远的坐标，
     * 不拦的话村民能隔着整个地图拆方块。
     */
    private boolean inRange(VillagerState vs, Location loc) {
        Location from = vs.entity().getLocation();
        if (from.getWorld() == null || !from.getWorld().equals(loc.getWorld())) return false;
        return from.distanceSquared(loc) <= ACTION_RANGE * ACTION_RANGE;
    }

    /**
     * 落点安全检查（SPEC 2.13"传送只传自己且检查落点安全"）。
     *
     * @return null = 安全；非 null = 不安全的原因（直接拼进给玩家的反馈）
     */
    private static String unsafeLandingReason(Location dest) {
        Block feet = dest.getBlock();
        Block head = dest.clone().add(0, 1, 0).getBlock();
        Block ground = dest.clone().add(0, -1, 0).getBlock();
        Material feetType = feet.getType();
        Material headType = head.getType();
        if (feetType == Material.LAVA || headType == Material.LAVA) return "岩浆";
        if (feetType == Material.FIRE || headType == Material.FIRE) return "火";
        if (feetType == Material.CACTUS || headType == Material.CACTUS) return "仙人掌";
        if (!feetType.isAir() || !headType.isAir()) return "卡在方块里";
        Material groundType = ground.getType();
        if (groundType.isAir()) return "悬空";
        if (groundType == Material.LAVA) return "脚下是岩浆";
        if (!groundType.isSolid()) return "脚下站不住";
        return null;
    }

    public WorldInteractionService(McaiPlugin plugin, PermissionManager permissions, AuditLog auditLog) {
        this.plugin = plugin;
        this.permissions = permissions;
        this.auditLog = auditLog;
    }

    // ===== 主入口（已退役） =====
    // 旧的 execute(ToolCall) 分发入口于 2026-10-21 随 equip 迁柜一并删除：
    // 全部世界交互动作（give_item / break_block / place_block /
    // teleport / teleport_to / equip）都已迁入统一工具柜，本类只保留各动作的
    // 执行体（公开方法）与代码层直达通道（索要交付、收礼、传送确认等）。

    // ===== 免费赠送（offer_trade 的 price=0 路径）—— 必须出库存，禁止凭空造物 =====

    /** 免费赠送单次上限（与旧实现一致，只是不再凭空生成） */
    public static final int FREE_GIFT_MAX = 16;

    /**
     * 免费送实际能给出的数量 —— 纯函数，供单测。
     * 非神使：库存有多少最多给多少（库存 0 = 一个都不给）；神使：可凭空补足（调用方记审计）。
     */
    static int freeGiftAmount(int requested, int stock, boolean envoy) {
        int want = Math.max(0, Math.min(FREE_GIFT_MAX, requested));
        if (envoy) return want;
        return Math.min(want, Math.max(0, stock));
    }

    /**
     * 执行一次免费赠送（主线程）：东西必须从 NPC 真实库存出（神使除外，凭空部分记审计）。
     * <p>
     * 背景：旧实现 price=0 时直接把物品塞进玩家背包、完全不扣库存，等于印钞口——
     * 模型一句话就能凭空变出最多 16 个任意物品（设计决策文档 2026-10-21 第 6 节已定取消）。
     * 现在与 give_item 同规：平民/亲信只出库存，没有就是没有；交付后标记防重，
     * 避免模型同轮再走 give_item 送出第二份。
     *
     * @return 实际交付的数量（0 = 没给成，调用方不要再宣称送过了）
     */
    public int deliverFreeGift(VillagerState vs, Player player, Material mat, int requestedCount) {
        if (vs == null || player == null || mat == null || mat.isAir() || requestedCount <= 0) return 0;
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        boolean envoy = permissions.rankOf(vs, player.getUniqueId()) == NpcRank.ENVOY;
        int stock = vs.stockOf(mat);
        int give = freeGiftAmount(requestedCount, stock, envoy);
        if (give <= 0) {
            plugin.getLogger().info("§c[MC-AI] 免费赠送拦截: " + npcName + " 库存没有 "
                    + mat.name() + "，不能凭空送（已取消凭空造物）");
            return 0;
        }
        int fromStock = Math.min(give, stock);
        if (fromStock > 0) vs.removeFromInventory(mat, fromStock);
        if (give > fromStock) {
            // 神使凭空补足的部分必须审计（与 give_item 神使刷物品同规）
            auditLog.log(npcName, vs.getUniqueId(), "offer_trade_free_gift",
                    "item=" + mat.name() + ",spawned=" + (give - fromStock)
                            + ",target=" + player.getName(), "OK");
        }
        var leftover = player.getInventory().addItem(new ItemStack(mat, give));
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        vs.markAutoGiven(mat);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] 免费赠送(出库存): " + npcName + " → " + player.getName()
                + " " + mat.name() + " x" + give + (give > fromStock ? "（神使凭空补 " + (give - fromStock) + "，已审计）" : ""));
        return give;
    }

    // ===== 从玩家身上收东西（只有"玩家亲口确认"一条路，模型不许直接扣货） =====

    /**
     * 从玩家背包真实扣掉 count 个某物品，放进 NPC 库存（主线程）。
     * <p>
     * 唯一入口是代码层"玩家递东西"拦截：{@code CentralPlanner.annotateGift} 先只登记意向、
     * 让 NPC 问一句"真要给俺？"，等玩家亲口确认（{@code resolvePendingGift}）才调到这里真正扣货。
     * <b>LLM 没有 {@code take_item} 工具</b> —— 模型只嘴上答应不动手还能接受，
     * 但它擅自扣玩家东西就是单方面抢夺，这个口子从工具面彻底封死。
     * <p>
     * 从玩家背包扣货，不够就按实际数量收（玩家说给 5 个但只有 3 个 → 收 3 个，不坑人）。
     *
     * @return 实际收到的数量（0 = 什么都没收到）
     */
    public int takeFromPlayer(VillagerState vs, Player player, Material mat, int count) {
        if (vs == null || player == null || mat == null || mat.isAir() || count <= 0) return 0;
        int taken = 0;
        var inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (taken >= count) break;
            ItemStack st = contents[i];
            if (st == null || st.getType() != mat) continue;
            int need = count - taken;
            int take = Math.min(need, st.getAmount());
            int rest = st.getAmount() - take;
            if (rest <= 0) {
                // 扣光直接清格：setAmount(0) 会在背包里留一个 0 数量的"幽灵物品"，
                // 看着是空格但 getContents() 还能扫到，后续计数/收礼逻辑会把它当真格子
                inv.setItem(i, null);
            } else {
                st.setAmount(rest);
            }
            taken += take;
        }
        if (taken == 0) return 0;
        vs.addToInventory(mat, taken);
        // 送礼涨好感：主动把东西塞给 NPC 是实打实的善意，+2（与聊天/教学/成交一起构成好感来源）
        vs.emotion().addFavor(player.getUniqueId(), 2.0);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] 玩家确认送礼(扣货): " + player.getName() + " → "
                + com.howard.mcai.ai.Smalltalk.nameOf(vs) + " " + mat.name() + " x" + taken + "（进库存）");
        return taken;
    }

    // ===== break_block：破坏方块（职业白名单 + 领地保护；神使全放行 + 审计） =====

    /**
     * 按坐标破坏方块（统一柜 NpcBreakBlockTool 的执行体；规则与旧后门时期完全一致）。
     *
     * @param playerUuid 当前互动玩家（定神使/亲信等级）；无玩家上下文传 null
     * @return 给玩家看的反馈行（null = 成功，无需额外反馈）
     */
    public String breakBlockAt(VillagerState vs, java.util.UUID playerUuid, double x, double y, double z) {
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        NpcRank rank = permissions.rankOf(vs, playerUuid);
        Block block = blockAt(vs, x, y, z);
        if (block == null) return "§e坐标无效";
        if (!inRange(vs, block.getLocation())) return "§e太远了，够不着（" + (int) ACTION_RANGE + " 格内才行）";
        String params = "x=" + block.getX() + ",y=" + block.getY() + ",z=" + block.getZ()
                + ",type=" + block.getType().name();

        if (!permissions.canBreak(vs, block)) {
            String reason = permissions.breakRefuseReason(vs, block);
            plugin.getLogger().info("§c[MC-AI] break_block 拒绝: " + npcName + " @ "
                    + block.getLocation() + " — " + reason);
            // 平民越权尝试也留档到控制台（audit.log 只记神使，不污染）
            if (rank == NpcRank.ENVOY) auditLog.log(npcName, vs.getUniqueId(), "break_block", params, "REFUSED");
            return "§c[MC-AI] 权限不足: " + reason;
        }

        boolean dropped = block.breakNaturally();  // 正常掉落
        if (rank == NpcRank.ENVOY) {
            auditLog.log(npcName, vs.getUniqueId(), "break_block", params, "OK");
        }
        plugin.getLogger().info("§b[MC-AI] break_block: " + npcName + " 拆掉 "
                + block.getType().name() + " @ " + block.getX() + "," + block.getY() + "," + block.getZ()
                + (rank == NpcRank.ENVOY ? "（神使，已审计）" : "（职业白名单内）"));
        return null;
    }

    // ===== place_block：放置方块（扣库存；神使免库存 + 审计） =====

    /**
     * 按坐标放置方块（统一柜 NpcPlaceBlockTool 的执行体；规则与旧后门时期完全一致）。
     *
     * @param playerUuid 当前互动玩家（定神使/亲信等级）；无玩家上下文传 null
     * @return 给玩家看的反馈行（null = 成功，无需额外反馈）
     */
    public String placeBlockAt(VillagerState vs, java.util.UUID playerUuid, double x, double y, double z,
                               Material mat) {
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        NpcRank rank = permissions.rankOf(vs, playerUuid);
        Block block = blockAt(vs, x, y, z);
        if (block == null) return "§e坐标无效";
        if (!inRange(vs, block.getLocation())) return "§e太远了，够不着（" + (int) ACTION_RANGE + " 格内才行）";
        if (mat == null || !mat.isBlock()) return "§e那不是能放置的方块";
        boolean envoy = rank == NpcRank.ENVOY;
        // 库存快照要在扣货前取：神使免库存 = 凭空刷，审计里要标 spawned=true
        boolean hadStock = vs.stockOf(mat) >= 1;

        if (!permissions.canPlace(vs, block, null)) {
            plugin.getLogger().info("§c[MC-AI] place_block 拒绝: " + npcName + " 在玩家领地内放置");
            return "§c[MC-AI] 权限不足: 那是玩家的领地，不能乱放东西";
        }
        // 库存守卫：平民/亲信必须真有这个方块（防止凭空造物）；神使免检（刷方块，审计）
        if (!envoy) {
            if (!hadStock) {
                return "§e" + npcName + "身上没有 " + com.howard.mcai.ai.PromptBuilder.materialToZh(mat);
            }
            vs.removeFromInventory(mat, 1);
        }

        block.setType(mat);
        if (envoy) {
            auditLog.log(npcName, vs.getUniqueId(), "place_block",
                    "x=" + block.getX() + ",y=" + block.getY() + ",z=" + block.getZ()
                            + ",material=" + mat.name() + (hadStock ? "" : ",spawned=true"),
                    "OK");
        }
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] place_block: " + npcName + " 放置 " + mat.name()
                + " @ " + block.getX() + "," + block.getY() + "," + block.getZ()
                + (envoy ? "（神使，已审计）" : "（扣库存）"));
        return null;
    }

    // ===== teleport_to：传送到目标 / 把目标拉到自己身边 =====

    /**
     * 把目标拉到 NPC 身边（统一柜 NpcTeleportToTool 的执行体；规则与旧后门时期一致）。
     * 传送玩家 = 神使专属且要等玩家聊天回「好」确认（pending 记在本服务，
     * 由 {@link #consumeTeleportConfirm} 消费）；传其它生物 = 神使直接执行 + 审计。
     *
     * @return 给玩家看的反馈/提示行（null = 已直接完成，无需额外提示）
     */
    public String teleportToTarget(VillagerState vs, String targetId) {
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        if (targetId == null || targetId.isBlank()) return "§e缺少传送目标";
        Entity target = resolveTarget(targetId);
        if (target == null) return "§e找不到传送目标 " + targetId;

        // 目标是自己 → 等价传送到目标位置（全等级允许）
        if (target.getUniqueId().equals(vs.getUniqueId())) {
            return "§e不能传送自己到自己";
        }

        if (target instanceof Player targetPlayer) {
            // 传送玩家 = 神使专属 + 聊天确认"好"（SPEC 2.14 硬性要求）
            if (!permissions.canTeleportOthers(vs)) {
                plugin.getLogger().info("§c[MC-AI] teleport_to 拒绝: " + npcName + " 想传玩家 "
                        + targetPlayer.getName() + " — 非神使");
                return "§c[MC-AI] 权限不足: 只有神使能传送别人";
            }
            pendingTeleports.put(targetPlayer.getUniqueId(),
                    new PendingPlayerTeleport(vs.getUniqueId(), npcName,
                            System.currentTimeMillis() + confirmTimeoutMs()));
            plugin.getLogger().info("§b[MC-AI] 神使传送请求: " + npcName + " → 玩家 "
                    + targetPlayer.getName() + "，等待其聊天回复\"好\"确认");
            auditLog.log(npcName, vs.getUniqueId(), "teleport_to",
                    "target=player:" + targetPlayer.getName(), "CONFIRM_PENDING");
            return "§b[MC-AI] " + npcName + " 想传送你，请在聊天框回复 §e好 §b确认（30秒内有效）";
        }

        // 非玩家实体（动物/村民/怪物）→ 神使直接拉到自己身边
        if (!permissions.canTeleportOthers(vs)) {
            return "§c[MC-AI] 权限不足: 只有神使能传送别的生物";
        }
        Location dest = vs.entity().getLocation();
        boolean ok = target.teleport(dest);
        auditLog.log(npcName, vs.getUniqueId(), "teleport_to",
                "target=" + target.getType().name() + ",to=" + fmtLoc(dest), ok ? "OK" : "FAILED");
        plugin.getLogger().info("§b[MC-AI] 神使传送实体: " + npcName + " 把 "
                + target.getType().name() + " 拉到身边（已审计）");
        return null;
    }

    // ===== teleport：传送自己到坐标（2.13 平民白名单"传送只传自己"） =====

    /**
     * 把 NPC 自己传送到坐标（统一柜 NpcTeleportTool 的执行体；规则与旧后门时期一致：
     * 32 格内 + 落点安全检查 + 神使审计）。
     *
     * @param playerUuid 当前互动玩家（定等级用）；无玩家上下文传 null
     * @return 给玩家看的反馈行（null = 成功）
     */
    public String teleportSelfTo(VillagerState vs, java.util.UUID playerUuid, double x, double y, double z) {
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        NpcRank rank = permissions.rankOf(vs, playerUuid);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return "§e坐标无效";
        Location dest = new Location(vs.entity().getWorld(), x, y, z);
        if (!inRange(vs, dest)) return "§e太远了，传不过去（" + (int) ACTION_RANGE + " 格内才行）";
        String unsafe = unsafeLandingReason(dest);
        if (unsafe != null) {
            plugin.getLogger().info("§c[MC-AI] teleport 落点不安全: " + npcName + " → "
                    + fmtLoc(dest) + " — " + unsafe);
            return "§e那个地方落不了脚（" + unsafe + "），换个地方吧";
        }
        boolean ok = vs.entity().teleport(dest);
        if (rank == NpcRank.ENVOY) {
            auditLog.log(npcName, vs.getUniqueId(), "teleport", "to=" + fmtLoc(dest), ok ? "OK" : "FAILED");
        }
        plugin.getLogger().info("§b[MC-AI] teleport: " + npcName + " → " + fmtLoc(dest)
                + (rank == NpcRank.ENVOY ? "（神使，已审计）" : ""));
        return null;
    }

    // ===== equip：穿装备/拿手上展示（消耗库存） =====

    /**
     * 穿戴一件库存物品（统一柜 NpcEquipTool 的执行体；规则与旧后门时期一致：
     * 必须有库存、旧装备先收回库存再换新、换下的不凭空消失）。
     *
     * @param slot mainhand/helmet/chestplate/leggings/boots；空或未知值按 mainhand
     * @return 给玩家看的反馈行（null = 成功）
     */
    public String equipItem(VillagerState vs, Material mat, String slot) {
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        if (mat == null || mat.isAir()) return "§e不知道这是什么东西";
        if (slot == null || slot.isBlank()) slot = "mainhand";

        if (vs.stockOf(mat) < 1) {
            return "§e" + npcName + "身上没有 " + com.howard.mcai.ai.PromptBuilder.materialToZh(mat);
        }
        var equip = vs.entity().getEquipment();
        if (equip == null) return "§e穿不上";

        // 先把旧装备收回库存再换新（避免替换掉的装备凭空消失）
        ItemStack old = switch (slot.toLowerCase()) {
            case "helmet" -> equip.getHelmet();
            case "chestplate" -> equip.getChestplate();
            case "leggings" -> equip.getLeggings();
            case "boots" -> equip.getBoots();
            default -> equip.getItemInMainHand();
        };
        if (old != null && !old.getType().isAir()) {
            vs.addToInventory(old.getType(), old.getAmount());
        }
        switch (slot.toLowerCase()) {
            case "helmet" -> equip.setHelmet(new ItemStack(mat));
            case "chestplate" -> equip.setChestplate(new ItemStack(mat));
            case "leggings" -> equip.setLeggings(new ItemStack(mat));
            case "boots" -> equip.setBoots(new ItemStack(mat));
            default -> equip.setItemInMainHand(new ItemStack(mat));
        }
        vs.removeFromInventory(mat, 1);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] equip: " + npcName + " 装备 " + mat.name() + " @ " + slot);
        return null;
    }

    // ===== 传送确认（PlayerChatListener 在聊天入口调用） =====

    /**
     * 玩家聊天回复"好"时消费待确认的传送请求。
     *
     * @return true = 已执行传送（该消息不应再进对话流程）；
     *         false = 没有待确认请求或回复的不是确认词（消息照常路由）
     */
    public boolean consumeTeleportConfirm(Player player, String message) {
        PendingPlayerTeleport pending = pendingTeleports.get(player.getUniqueId());
        if (pending == null) return false;
        // 过期清理
        if (System.currentTimeMillis() > pending.expiryMs()) {
            pendingTeleports.remove(player.getUniqueId());
            return false;
        }
        String m = message == null ? "" : message.trim();
        boolean confirmed = false;
        for (String w : CONFIRM_WORDS) {
            if (m.equals(w)) { confirmed = true; break; }  // 全词匹配，防"好的吧不想去"误触
        }
        if (!confirmed) return false;  // 保留 pending，玩家可以再想想

        pendingTeleports.remove(player.getUniqueId());
        VillagerState npcState = plugin.getVillagers().get(pending.npcUuid());
        if (npcState == null || !npcState.entity().isValid()) return true;  // NPC 没了，确认作废但不进对话
        Location dest = npcState.entity().getLocation();
        boolean ok = player.teleport(dest);
        auditLog.log(pending.npcName(), pending.npcUuid(), "teleport_to",
                "target=player:" + player.getName() + ",to=" + fmtLoc(dest), ok ? "OK(confirmed)" : "FAILED");
        plugin.getLogger().info("§b[MC-AI] 玩家确认传送: " + player.getName() + " → " + pending.npcName()
                + " 身边（已审计）");
        return true;
    }

    /** 是否存在待确认请求（调试/命令展示用） */
    public boolean hasPendingConfirm(Player player) {
        return pendingTeleports.containsKey(player.getUniqueId());
    }

    private long confirmTimeoutMs() {
        return plugin.getPluginConfig().teleportConfirmTimeoutSeconds() * 1000L;
    }

    // ===== 解析辅助 =====

    /** 中文名/英文名/ID → Material（复用 TradeRegistry 的中文映射 + 原生 matchMaterial 兜底）。统一柜工具（如 NpcGiveItemTool）共用同一份解析，避免各写一份对不上 */
    public static Material resolveItem(String name) {
        if (name == null || name.isBlank()) return null;
        String clean = name.trim().replace("附魔", "");
        Material m = TradeRegistry.zhToMaterial(clean);
        if (m != null) return m;
        try {
            return Material.matchMaterial(clean.toUpperCase().replace(' ', '_'));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * target_id → 实体。支持：
     * "player_ab12cd34"（scene JSON 的 8 位前缀）、玩家名、"villager_<uuid8>"、村民名。
     */
    private Entity resolveTarget(String targetId) {
        String t = targetId.trim();
        if (t.startsWith("player_")) t = t.substring("player_".length());
        if (t.startsWith("villager_")) t = t.substring("villager_".length());
        // 1. 玩家：UUID 前缀 / 精确名
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().toString().startsWith(t) || p.getName().equalsIgnoreCase(t)) return p;
        }
        // 2. AI 村民：UUID 前缀 / 显示名
        for (VillagerState vs : plugin.getVillagers().values()) {
            if (vs.getUniqueId().toString().startsWith(t)) return vs.entity();
            String n = vs.entity().getCustomName();
            if (n != null && n.replaceAll("§.", "").equalsIgnoreCase(t)) return vs.entity();
        }
        return null;
    }

    /** 坐标 → 方块（相对世界硬边界做保护：y 越界/实体缺失 → null） */
    private static Block blockAt(VillagerState vs, double x, double y, double z) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;
        if (y < -64 || y > 320) return null;
        var entity = vs.entity();
        if (entity == null || entity.getWorld() == null) return null;
        return entity.getWorld().getBlockAt((int) x, (int) y, (int) z);
    }

    private static String fmtLoc(Location loc) {
        return loc.getWorld().getName() + "@" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
