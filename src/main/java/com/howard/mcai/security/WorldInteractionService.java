package com.howard.mcai.security;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.ToolCall;
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
 * 工具清单（注意：已无 take_item —— 从玩家身上拿东西只走"玩家亲口确认"的两步流程，
 * 不给模型直接扣货的口子，详见 {@link #takeFromPlayer}）：
 * <pre>
 * give_item(item,count)  库存给玩家东西（神使库存不足时凭空刷，审计）
 * break_block(x,y,z)     破坏方块（breakNaturally 正常掉落）
 * place_block(x,y,z,mat) 放置方块（扣 NPC 库存；神使免库存）
 * teleport_to(target)    传送到目标 / 把目标拉过来（他人=神使；玩家需聊天回"好"确认）
 * teleport(x,y,z)        传送自己到坐标（全等级允许 = 2.13 "传送只传自己"）
 * equip(item,slot)       穿装备/拿手上展示（消耗库存）
 * </pre>
 * <p>
 * 线程模型：execute 只在 Paper 主线程调用（CentralPlanner.executeDecision 内），
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

    public WorldInteractionService(McaiPlugin plugin, PermissionManager permissions, AuditLog auditLog) {
        this.plugin = plugin;
        this.permissions = permissions;
        this.auditLog = auditLog;
    }

    // ===== 主入口 =====

    /**
     * 执行一个世界交互工具（主线程）。
     *
     * @return 给玩家看的反馈行（§ 色彩码；null = 无需额外反馈，只说 LLM 台词）
     */
    public String execute(VillagerState vState, ToolCall tool, Player nearPlayer) {
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vState);
        // 亲信判定按"当前这位玩家"的好感 —— 无玩家上下文（nearPlayer=null）用 legacyFavor 回退
        NpcRank rank = permissions.rankOf(vState, nearPlayer != null ? nearPlayer.getUniqueId() : null);

        return switch (tool.name()) {
            case "give_item" -> giveItem(vState, npcName, rank, tool, nearPlayer);
            case "break_block" -> breakBlock(vState, npcName, rank, tool);
            case "place_block" -> placeBlock(vState, npcName, rank, tool);
            case "teleport_to" -> teleportTo(vState, npcName, rank, tool, nearPlayer);
            case "teleport" -> teleportSelf(vState, npcName, rank, tool);
            case "equip" -> equip(vState, npcName, rank, tool);
            default -> null;
        };
    }

    // ===== 代码层直接交付（玩家索要物品时由决策层调用，不依赖模型是否真的调 give_item） =====

    /**
     * 当场把 {@code count} 个 {@code mat} 从 NPC 库存交给玩家（主线程）。
     * <p>
     * 为什么要有这条路：索要物品的交付原先只靠"提示模型去调 give_item"，实测模型经常只在台词里
     * 写 {@code [动作:give_item 面包]} 当纯文本（被 sanitizeDialogue 剥掉，工具根本没被调用），
     * 于是它嘴上说"给你 1 个"、玩家背包里却什么都没有 —— 台词与实际结果对不上。
     * 现在"给不给"完全由决策层拍板并当场出货，再把"已经给过了"作为事实注入回话要求，
     * 与砍价同一套思路（代码层拍板 + 注入硬性成交事实）。
     * <p>
     * 出货后 8 秒内标记防重：模型同款 {@code give_item} 会被 {@code giveItem} 静默跳过。
     *
     * @return null = 已交付（或本就无可交付）；非 null = 给玩家看的失败原因（库存已空）
     */
    public String handOverFromStock(VillagerState vs, Player player, Material mat, int count) {
        if (vs == null || player == null || mat == null || mat.isAir() || count <= 0) return null;
        String npcName = com.howard.mcai.ai.Smalltalk.nameOf(vs);
        int stock = vs.stockOf(mat);
        if (stock <= 0) {
            return "§e" + npcName + "的背包里已经没有 "
                    + com.howard.mcai.ai.PromptBuilder.materialToZh(mat) + " 了";
        }
        int give = Math.min(count, stock);          // 实际能给的只有库存那么多，不凭空变出
        vs.removeFromInventory(mat, give);
        var leftover = player.getInventory().addItem(new ItemStack(mat, give));
        if (!leftover.isEmpty()) {                  // 玩家背包塞不下 → 溢出部分掉在脚下（与 giveItem 一致）
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        // 清掉这次"磨到松口"的一次性放行 —— 已经被用掉了，留着会让后面一次无关的 give_item 混过门槛
        vs.consumeMercy(player.getUniqueId(), mat);
        vs.markAutoGiven(mat);
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] 索要交付(代码层): " + npcName + " → " + player.getName()
                + " " + mat.name() + " x" + give + "（出库存）");
        return null;
    }

    // ===== give_item：库存给玩家东西（送礼看库存 —— 平民/亲信只出库存；神使可刷） =====

    private String giveItem(VillagerState vs, String npcName, NpcRank rank, ToolCall tool, Player player) {
        if (player == null) return "§e附近没有玩家可给";
        Material mat = resolveItem(safeStr(tool, "item"));
        if (mat == null || mat.isAir()) return "§e不知道这是什么东西";
        // 索要物品已由代码层（annotateBeg / GUI requestItem）当场交付过了 ——
        // 模型这一轮要是再调一次同款 give_item，直接静默跳过，否则同一件东西会被送出两份
        if (vs.wasAutoGivenRecently(mat)) return null;
        int count = Math.max(1, Math.min(64, (int) argNum(tool, "count", 1)));
        // GUI 索要时玩家在界面上选好的数量优先 —— 点"1 个"就只给 1 个，
        // 模型自由发挥的 count 参数不算数（否则要 1 个却塞来一整叠）
        int pinned = vs.consumeItemRequest(player.getUniqueId(), mat);
        if (pinned > 0) count = Math.min(64, pinned);
        boolean envoy = rank == NpcRank.ENVOY;
        int stock = vs.stockOf(mat);

        // 关系门槛（按玩家独立）—— 好感不够就婉拒，"索要物品"与 GUI 索要用同一门槛。
        // 神使（自己人/被授予权限的 NPC）不受此限；给出的是 NPC 自己的库存，不刷物品。
        // 例外：玩家把 NPC 磨到没耐心时 GUI 会 grantMercy —— 那一笔放行（一次性），台词与结果一致。
        if (!envoy) {
            double need = plugin.getPluginConfig().requestItemFavor();
            double fav = vs.emotion().favor(player.getUniqueId());
            if (fav < need && !vs.consumeMercy(player.getUniqueId(), mat)) {
                plugin.getLogger().info("§c[MC-AI] give_item 拒绝: " + npcName + " 对 " + player.getName()
                        + " 好感 " + String.format("%.0f", fav) + " < 门槛 " + String.format("%.0f", need));
                return "§e" + npcName + "跟你还没那么熟，不好白给你东西";
            }
        }

        if (stock < count) {
            if (!envoy) {
                plugin.getLogger().info("§c[MC-AI] give_item 拒绝: " + npcName + " 库存 " + mat.name()
                        + " 只剩 " + stock + "，给不了 " + count + "（送礼看库存）");
                return "§e" + npcName + "的库存里没有那么多 " + com.howard.mcai.ai.PromptBuilder.materialToZh(mat);
            }
            // 神使刷物品补差额 —— 必须审计（"刷物品"是神使专属且滥用高发）
            auditLog.log(npcName, vs.getUniqueId(), "give_item",
                    "item=" + mat.name() + ",spawned=" + (count - stock) + ",target=" + player.getName(), "OK");
        } else {
            vs.removeFromInventory(mat, count);
        }

        var leftover = player.getInventory().addItem(new ItemStack(mat, count));
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        plugin.getMemoryStore().markDirty(vs.getUniqueId());
        plugin.getLogger().info("§b[MC-AI] give_item: " + npcName + " → " + player.getName()
                + " " + mat.name() + " x" + count + (envoy && stock < count ? "（神使刷出差额）" : "（出库存）"));
        return null;
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
        for (ItemStack st : player.getInventory().getContents()) {
            if (taken >= count) break;
            if (st == null || st.getType() != mat) continue;
            int need = count - taken;
            int take = Math.min(need, st.getAmount());
            st.setAmount(st.getAmount() - take);
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

    private String breakBlock(VillagerState vs, String npcName, NpcRank rank, ToolCall tool) {
        Block block = argBlock(vs, tool);
        if (block == null) return "§e坐标无效";
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

    private String placeBlock(VillagerState vs, String npcName, NpcRank rank, ToolCall tool) {
        Block block = argBlock(vs, tool);
        if (block == null) return "§e坐标无效";
        Material mat = resolveItem(safeStr(tool, "material"));
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

    private String teleportTo(VillagerState vs, String npcName, NpcRank rank, ToolCall tool, Player nearPlayer) {
        String targetId = safeStr(tool, "target_id");
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

    private String teleportSelf(VillagerState vs, String npcName, NpcRank rank, ToolCall tool) {
        double x = argNum(tool, "x", Double.NaN);
        double y = argNum(tool, "y", Double.NaN);
        double z = argNum(tool, "z", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return "§e坐标无效";
        Location dest = new Location(vs.entity().getWorld(), x, y, z);
        boolean ok = vs.entity().teleport(dest);
        if (rank == NpcRank.ENVOY) {
            auditLog.log(npcName, vs.getUniqueId(), "teleport", "to=" + fmtLoc(dest), ok ? "OK" : "FAILED");
        }
        plugin.getLogger().info("§b[MC-AI] teleport: " + npcName + " → " + fmtLoc(dest)
                + (rank == NpcRank.ENVOY ? "（神使，已审计）" : ""));
        return null;
    }

    // ===== equip：穿装备/拿手上展示（消耗库存） =====

    private String equip(VillagerState vs, String npcName, NpcRank rank, ToolCall tool) {
        Material mat = resolveItem(safeStr(tool, "item"));
        if (mat == null || mat.isAir()) return "§e不知道这是什么东西";
        String slot = safeStr(tool, "slot");
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

    /** 中文名/英文名/ID → Material（复用 TradeRegistry 的中文映射 + 原生 matchMaterial 兜底） */
    private static Material resolveItem(String name) {
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

    /** 工具参数里的坐标 → Block（相对世界硬边界做保护，越界返回 null） */
    private static Block argBlock(VillagerState vs, ToolCall tool) {
        double x = argNum(tool, "x", Double.NaN);
        double y = argNum(tool, "y", Double.NaN);
        double z = argNum(tool, "z", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;
        if (y < -64 || y > 320) return null;
        return vs.entity().getWorld().getBlockAt((int) x, (int) y, (int) z);
    }

    private static String fmtLoc(Location loc) {
        return loc.getWorld().getName() + "@" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static String safeStr(ToolCall tool, String key) {
        var obj = tool.arguments();
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static double argNum(ToolCall tool, String key, double def) {
        var obj = tool.arguments();
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }
}
