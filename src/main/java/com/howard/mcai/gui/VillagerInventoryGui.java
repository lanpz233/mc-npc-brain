package com.howard.mcai.gui;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.PersonalityTagLibrary;
import com.howard.mcai.ai.PromptBuilder;
import com.howard.mcai.mob.NeedsState;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.security.NpcRank;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPC 背包 GUI（索要入口）—— 玩家对着村民按 F 打开，展示该村民的真实库存。
 * <p>
 * 54 格布局分两区：
 * <ul>
 *   <li><b>0~44 格</b>：库存物品（材质=库存物，数量=库存数，点击索要），空位铺灰色底板；
 *       库存为空时居中放屏障提示。</li>
 *   <li><b>45~53 格</b>：<b>NPC 详细状态面板</b> —— 基本信息 / 人格性格 / 情绪好感 / 需求 /
 *       社会关系 / 环境位置 / 记忆概况 / 背包交易 / 最近言行，共 9 张只读信息卡。</li>
 * </ul>
 * 关键约束：
 * <ul>
 *   <li>物品**只能通过 {@link #requestItem} 出货**，GUI 本身对所有点击/拖拽事件 cancel，
 *       玩家不能直接把东西拿走（{@link com.howard.mcai.listener.VillagerInventoryListener} 负责拦截）。</li>
 *   <li>出货受"关系门槛"约束：对当前玩家的好感 < {@code permissions.request-item-favor} → 婉拒且不动库存。</li>
 *   <li>出货后同步 markDirty，库存变化立即进入防抖落盘，与对话侧 {@code give_item} 共用同一份库存。</li>
 *   <li>所有方法必须在 Paper 主线程调用（openInventory / 库存扣减 / 掉落物 / 读实体状态）。</li>
 * </ul>
 */
public final class VillagerInventoryGui implements InventoryHolder {

    private static final int SIZE = 54;
    /** 库存物品占用 0~44，45~53 留给状态面板 */
    private static final int ITEM_AREA = 45;
    /** 空位底板 —— 避免大片空槽，也防止玩家手滑点到空气 */
    private static final Material FILLER = Material.GRAY_STAINED_GLASS_PANE;
    /** 状态面板里列表类字段最多展示多少条，超出折叠为"还有 N 个" */
    private static final int MAX_LIST_LINES = 6;

    private final McaiPlugin plugin;
    private final VillagerState state;
    private final UUID villagerUuid;
    /** 打开这个窗口的玩家 —— 防止别的玩家（理论上不会）点到别人的窗口；也是"按玩家好感"的取值对象 */
    private final UUID viewerUuid;
    private final Inventory inventory;
    /** 槽位 → 该格代表的 Material；只有"真货"格才进表，底板/状态卡不在表里 */
    private final Map<Integer, Material> slotMap = new HashMap<>();

    private VillagerInventoryGui(McaiPlugin plugin, VillagerState state, Player viewer) {
        this.plugin = plugin;
        this.state = state;
        this.villagerUuid = state.getUniqueId();
        this.viewerUuid = viewer.getUniqueId();
        this.inventory = Bukkit.createInventory(this, SIZE, "§6" + displayName() + "的背包");
    }

    /**
     * 打开背包 GUI（主线程）。
     *
     * @param plugin 插件实例（取配置门槛 + 记忆标脏）
     * @param viewer 打开的玩家
     * @param state  目标村民状态
     */
    public static void open(McaiPlugin plugin, Player viewer, VillagerState state) {
        if (state == null || viewer == null) return;
        // 开面板前先纠正职业：状态卡的「职业」必须与实体真实职业一致 ——
        // 村民可能刚认领工作站（从"无业"变成真实职业），不刷新会显示成"无业"
        plugin.refreshVillagerProfession(state);
        VillagerInventoryGui gui = new VillagerInventoryGui(plugin, state, viewer);
        gui.rebuild();
        viewer.openInventory(gui.inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID villagerUuid() {
        return villagerUuid;
    }

    /** NPC 显示名：优先自定义名（去色彩码），否则退回职业中文名 */
    private String displayName() {
        var e = state.entity();
        String cn = (e != null) ? e.getCustomName() : null;
        if (cn != null && !cn.isBlank()) return cn.replaceAll("§.", "");
        return state.profession().zhName;
    }

    /**
     * 按当前库存 + 实时状态重建 54 格内容。
     * <p>
     * 复用**同一个 Inventory 实例**（玩家窗口已绑定它），只 clear + 重填，
     * 玩家窗口不会闪烁关闭 —— 库存变化/状态变化时原地刷新即可。
     */
    private void rebuild() {
        inventory.clear();
        slotMap.clear();

        int slot = 0;
        for (Map.Entry<String, Integer> e : state.inventorySnapshot().entrySet()) {
            if (slot >= ITEM_AREA) break;                  // 库存种类超过 45 —— 多余的这次不展示
            Material mat = Material.matchMaterial(e.getKey());
            int amount = e.getValue() == null ? 0 : e.getValue();
            if (mat == null || mat.isAir() || amount <= 0) continue;
            inventory.setItem(slot, buildItem(mat, amount));
            slotMap.put(slot, mat);
            slot++;
        }

        // 空位铺底板
        ItemStack filler = buildFiller();
        for (int i = 0; i < ITEM_AREA; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler.clone());
        }
        // 完全空背包：居中放一个屏障提示
        if (slotMap.isEmpty()) {
            inventory.setItem(22, buildEmptyHint());
        }

        // 底部一行：NPC 详细状态面板
        buildStatusRow();
    }

    // ===== 状态面板（45~53） =====

    /**
     * 构建 9 张只读信息卡 —— 把 VillagerState 里所有"活着"的字段都摊开给玩家看：
     * 基本身份、人格性格、情绪好感、需求、社会关系、环境位置、记忆概况、背包交易、最近言行。
     * <p>
     * 全部是**展示**用途，点击同样被 listener cancel（它们不在 {@link #slotMap} 里，
     * {@link #handleClick} 对非真货格直接 return，不会触发索要）。
     */
    private void buildStatusRow() {
        var emotion = state.emotion();
        double myFavor = emotion.favor(viewerUuid);
        NpcRank rank = rankOf();

        // 45 基本信息
        putStatus(45, Material.NAME_TAG, "§6§l基本信息", List.of(
                "§7姓名: §f" + displayName(),
                "§7职业: §f" + state.profession().zhName,
                "§7性别: §f" + state.genderZh(),
                "§7对话对象: §f" + interactingName(),
                "§7权限档: §f" + rank.zhName() + "§7（对你）",
                "§7UUID: §8" + villagerUuid.toString().substring(0, 13) + "…"));

        // 46 人格与性格
        List<String> traitLore = new ArrayList<>();
        List<String> tagIds = state.personalityTagIds();
        if (tagIds.isEmpty()) {
            traitLore.add("§8（未抽取人格标签）");
        } else {
            for (String id : tagIds) {
                var t = PersonalityTagLibrary.tagById(id);
                traitLore.add("§e" + (t != null ? t.nameZh() : id)
                        + " §7[类别:" + (t != null ? t.category() : "?") + "]");
            }
        }
        traitLore.add("§7开放度(接受新事物): §f" + state.openness() + "§7/100");
        traitLore.add("§7接受新事物倾向: §f" + pct(state.openMindedness()));
        traitLore.add("§7八卦倾向: §f" + pct(state.gossipProne()));
        putStatus(46, Material.BOOK, "§6§l人格与性格", traitLore);

        // 47 情绪与好感
        List<String> favorLore = new ArrayList<>();
        favorLore.add("§7恐惧值: §f" + emotion.fear() + "§7/100 " + bar(emotion.fear()));
        favorLore.add("§7对你的好感: §f" + fmt(myFavor) + " §7(" + rank.zhName() + ")");
        favorLore.add("§8索要门槛 " + fmt(plugin.getPluginConfig().requestItemFavor())
                + " / 亲信门槛 " + fmt(plugin.getPluginConfig().confidantFavor()));
        favorLore.add("§7—— 对各位玩家的好感 ——");
        favorLore.addAll(topList(favorEntries(), "§8（还没有任何好感记录）"));
        putStatus(47, Material.GOLDEN_APPLE, "§6§l情绪与好感", favorLore);

        // 48 需求状态
        List<String> needLore = new ArrayList<>();
        NeedsState needs = state.needs();
        Map<String, Double> needSnap = needs.snapshot();
        for (String k : NeedsState.ALL) {
            double v = needSnap.getOrDefault(k, 0.0);
            needLore.add("§7" + NeedsState.zhName(k) + ": §f" + fmt(v) + " " + bar(v));
        }
        String urgent = needs.dominantUrgent(70);
        needLore.add(urgent != null
                ? "§c当前最迫切: " + NeedsState.zhName(urgent)
                : "§a暂时没什么迫切需求");
        needLore.add("§8（需求越满越会主动行动：找吃的/睡觉/找朋友唠嗑）");
        putStatus(48, Material.COOKED_BEEF, "§6§l需求状态", needLore);

        // 49 社会关系
        List<String> relLore = new ArrayList<>();
        var rels = state.relationships();
        if (rels.isEmpty()) {
            relLore.add("§8（无家庭关系）");
        } else {
            for (var r : rels) {
                relLore.add("§7" + relZh(r.type()) + ": §f" + r.targetName());
            }
        }
        relLore.add("§7受击(30秒内): §f" + (state.wasHitRecently() ? "§c是" : "§a否"));
        putStatus(49, Material.ARMOR_STAND, "§6§l社会关系", relLore);

        // 50 环境与位置
        putStatus(50, Material.COMPASS, "§6§l环境与位置", envLore());

        // 51 记忆概况
        List<String> memLore = new ArrayList<>();
        int factTotal = state.factEntries().size();
        long factPrivate = state.factEntries().stream().filter(f -> f.ownerUuid() != null).count();
        memLore.add("§7近段对话缓存: §f" + state.chatHistory().size() + " §7条");
        memLore.add("§7关键事实: §f" + factTotal + " §7条 §8(其中私有 " + factPrivate + " 条)");
        memLore.add("§7—— 见过的玩家 ——");
        memLore.addAll(topList(metEntries(), "§8（还没见过任何玩家）"));
        String summary = state.summary();
        if (summary != null && !summary.isBlank()) {
            memLore.add("§7—— 记忆摘要 ——");
            memLore.add("§8" + clip(summary, 180));
        }
        putStatus(51, Material.WRITTEN_BOOK, "§6§l记忆概况", memLore);

        // 52 背包与交易
        Map<String, Integer> inv = state.inventorySnapshot();
        int totalItems = inv.values().stream().mapToInt(Integer::intValue).sum();
        List<String> invLore = new ArrayList<>();
        invLore.add("§7物品种类: §f" + inv.size() + " §7总件数: §f" + totalItems);
        invLore.add("§7动态改价商品: §f" + state.dynamicPrices().size() + " §7种");
        invLore.add("§7当前上架: §f" + state.offeredItems().size() + " §7种");
        invLore.add("§8（点上方物品可直接索要，需关系达标）");
        putStatus(52, Material.CHEST, "§6§l背包与交易", invLore);

        // 53 最近言行
        List<String> sayLore = new ArrayList<>();
        String recent = state.recentSocialContext();
        if (recent == null || recent.isBlank()) {
            sayLore.add("§8（最近没有群聊/闲聊发言）");
        } else {
            sayLore.add("§7最近在群里说过:");
            sayLore.add("§f" + clip(recent, 160));
        }
        putStatus(53, Material.CLOCK, "§6§l最近言行", sayLore);
    }

    /** 环境与位置卡片（实体坐标/群系/时间/天气/生命/冻结状态） */
    private List<String> envLore() {
        List<String> lore = new ArrayList<>();
        Villager v = state.entity();
        if (v == null || !v.isValid()) {
            lore.add("§c实体不存在或已卸载");
            return lore;
        }
        Location loc = v.getLocation();
        World w = loc.getWorld();
        lore.add("§7世界: §f" + (w != null ? w.getName() : "?"));
        lore.add("§7坐标: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        if (w != null) {
            lore.add("§7群系: §f" + w.getBiome(loc).name().toLowerCase());
            long t = w.getTime();
            // 统一走 PromptBuilder 的 MC 时间规则（tick 13000 = 19:00 入夜），
            // 避免面板显示的时间和发给模型的场景/速览口径不一致
            lore.add("§7游戏时间: §f" + PromptBuilder.mcClock(t)
                    + " §7(" + (PromptBuilder.mcIsDay(t) ? "白天" : "夜晚") + ")");
            String weather = w.isThundering() ? "§c雷暴" : (w.hasStorm() ? "§b下雨" : "§a晴朗");
            lore.add("§7天气: §f" + weather);
        }
        lore.add("§7生命: §f" + fmt(v.getHealth()) + "§7/" + fmt(v.getMaxHealth()));
        boolean frozenAI = !v.hasAI();
        String act;
        if (state.interactingPlayer() != null) {
            act = "§e对话中（AI已冻结，站着不动）";
        } else if (frozenAI) {
            act = "§e已冻结（无对话对象）";
        } else {
            act = "§a空闲（自由活动）";
        }
        lore.add("§7当前状态: " + act);
        return lore;
    }

    /** 信息卡写入：只读，不进 slotMap（点它不会触发索要） */
    private void putStatus(int slot, Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        inventory.setItem(slot, it);
    }

    // ===== 状态面板用的小工具 =====

    /** 当前玩家视角下的权限档（亲信/神使/平民） */
    private NpcRank rankOf() {
        var perm = plugin.getPermissionManager();
        return perm != null ? perm.rankOfStatic(state, viewerUuid) : state.rank();
    }

    /** 正在对话的玩家名，没在对话返回"无" */
    private String interactingName() {
        UUID id = state.interactingPlayer();
        if (id == null) return "无";
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }

    /** 好感记录：uuid → 名字/好感值，按好感降序 */
    private List<String> favorEntries() {
        Map<String, String> met = state.metPlayersSnapshot();
        List<Map.Entry<String, Double>> entries = new ArrayList<>(state.emotion().favorSnapshot().entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (var e : entries) {
            out.add("§7" + nameOf(e.getKey(), met) + ": §f" + fmt(e.getValue()));
        }
        return out;
    }

    /** 见过的玩家：uuid → 名字 */
    private List<String> metEntries() {
        List<String> out = new ArrayList<>();
        for (var e : state.metPlayersSnapshot().entrySet()) {
            out.add("§7" + e.getValue() + " §8(" + e.getKey().substring(0, 8) + ")");
        }
        return out;
    }

    /** uuid 字符串 → 可读名：优先 metPlayers，其次在线玩家，最后短 uuid */
    private static String nameOf(String uuidStr, Map<String, String> met) {
        String n = met.get(uuidStr);
        if (n != null && !n.isBlank()) return n;
        try {
            Player p = Bukkit.getPlayer(UUID.fromString(uuidStr));
            if (p != null) return p.getName();
        } catch (IllegalArgumentException ignored) {
            // 脏数据 uuid 解析失败 —— 退回短串展示
        }
        return uuidStr.length() > 8 ? uuidStr.substring(0, 8) : uuidStr;
    }

    /** 列表截断：超过 {@link #MAX_LIST_LINES} 条折叠成"还有 N 个" */
    private static List<String> topList(List<String> all, String emptyText) {
        if (all.isEmpty()) return List.of(emptyText);
        if (all.size() <= MAX_LIST_LINES) return all;
        List<String> out = new ArrayList<>(all.subList(0, MAX_LIST_LINES));
        out.add("§8…还有 " + (all.size() - MAX_LIST_LINES) + " 个");
        return out;
    }

    private static String relZh(String type) {
        if ("spouse".equals(type)) return "配偶";
        if ("sibling".equals(type)) return "兄弟姐妹";
        return type;
    }

    private static String pct(double v) {
        return String.format("%.0f%%", Math.max(0, Math.min(1, v)) * 100);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** 0~100 的进度条（10 格），用于需求/恐惧直观展示 */
    private static String bar(double v) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, v)) / 10.0);
        filled = Math.max(0, Math.min(10, filled));
        return "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ===== 库存格 =====

    /** 库存格物品：材质 = 库存物，数量 = 库存数，lore 提示中文名 + 数量 + 点击索要 */
    private ItemStack buildItem(Material mat, int amount) {
        ItemStack stack = new ItemStack(mat, Math.max(1, Math.min(amount, 64)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + PromptBuilder.materialToZh(mat));
            List<String> lore = new ArrayList<>();
            lore.add("§7库存: §e" + amount);
            lore.add("§a左键 → 索要 1 个");
            lore.add("§b右键 → 索要这一组（" + amount + " 个）");
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildFiller() {
        ItemStack it = new ItemStack(FILLER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§8·");
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack buildEmptyHint() {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7背包空空如也");
            it.setItemMeta(meta);
        }
        return it;
    }

    // ===== 点击处理 =====

    /**
     * GUI 点击入口（由 VillagerInventoryListener 转发，主线程）。
     * 只有点到"真货格"才触发索要；底板/状态卡/空位/玩家自己的背包格一律忽略。
     *
     * @param rightClick true = 右键（要一整组），false = 左键（只要 1 个）
     */
    public void handleClick(Player player, int slot, boolean rightClick) {
        if (player == null || !player.getUniqueId().equals(viewerUuid)) return;
        Material mat = slotMap.get(slot);
        if (mat == null) return;
        // 该格显示的库存数就是"一组"的量（重建时放进去的 amount）
        ItemStack shown = inventory.getItem(slot);
        int stack = (shown != null) ? Math.max(1, shown.getAmount()) : 1;
        requestItem(player, mat, rightClick ? stack : 1);
    }

    /**
     * 索要物品（主线程）—— <b>转成一次真交流</b>，而不是静默把东西塞给你。
     * <p>
     * 流程：关掉背包 GUI → 进入对话 → 把"玩家点了索要 X 共 N 个"当作一句话发给 NPC，
     * 由模型决定说什么 + 是否调用 {@code give_item}。
     * <p>
     * 给不给的最终把关仍在代码层（{@code give_item} 内的好感门槛），但这里决定"这次该给还是该拒"，
     * 并把结论作为"关系信息"告诉模型，让台词和最终结果一致。
     * <p>
     * <b>磨耐心</b>：好感不够时不是一口回绝就完了 —— 每次再来索要会消耗 NPC 的耐心，
     * 耐心耗尽他就松口答应一次（见 {@link VillagerState#grindPatience}）。耐心值随机、不对外公开。
     *
     * @param wantCount 玩家这次想要的个数（左键 = 1，右键 = 一整组）
     */
    public void requestItem(Player player, Material mat, int wantCount) {
        String zh = PromptBuilder.materialToZh(mat);
        int want = Math.max(1, Math.min(64, wantCount));

        // 库存兜底：GUI 可能过期（东西刚被别处拿走）
        if (state.stockOf(mat) <= 0) {
            player.sendMessage("§e" + displayName() + "的背包里已经没有 " + zh + " 了");
            rebuild();
            return;
        }

        Villager villager = state.entity();
        if (villager == null || !villager.isValid()) return;

        double need = plugin.getPluginConfig().requestItemFavor();
        double fav = state.emotion().favor(player.getUniqueId());
        boolean closeEnough = fav >= need;
        // 回收窗：这件东西本来就是这位玩家刚送出去的（3 分钟内），要回去不该看好感，直接还给他
        boolean reclaim = state.consumeReclaim(player.getUniqueId(), mat);
        // 好感不够 → 看能不能把 NPC 磨到没耐心。磨成功这次就放行（并记下 mercy，让代码层门槛同步放行）
        boolean mercy = false;
        if (!closeEnough && !reclaim) {
            mercy = state.grindPatience(player.getUniqueId());
            if (mercy) state.grantMercy(player.getUniqueId(), mat);
        }
        boolean allow = closeEnough || mercy || reclaim;

        // 代码层钉死数量：玩家点"1 个"就只给 1 个，模型自由发挥的 count 参数不算数
        state.setItemRequest(player.getUniqueId(), mat, want);

        // === 代码层当场交付 ===
        // 不给模型"要不要真的调 give_item"的裁量权：实测它常常只在台词里写 [动作:give_item 面包] 当纯文本
        // （被 sanitizeDialogue 剥掉、工具根本没被调用），于是嘴上说"给你 1 个"、玩家背包里却什么都没有。
        // 所以给不给由这里拍板并当场出货，再把"已经给过了"作为事实注入回话要求，台词和结果才一致。
        String failReason = null;
        if (allow) {
            failReason = plugin.getWorldInteraction().handOverFromStock(state, player, mat, want);
            if (failReason != null) allow = false;      // 库存刚被别处清空 → 退回按婉拒处理
        }

        // 1. 关掉背包，把 NPC 拉进对话状态（冻结原版 AI + 会持续转向玩家）
        player.closeInventory();
        var planner = plugin.getPlanner();
        planner.switchConversation(player, state);
        plugin.getBubbleManager().showDialog(villager, displayName(), "（思考中...）");

        // 2. 用 user 角色把"索要意图 + 关系现状"告诉模型（system 只能有一条且在首位 —— Jinja 铁律）。
        //    内容用中文，system prompt 仍是英文，不影响模型的中文台词输出。
        String relation = reclaim ? "这件东西本来就是他的，你刚收下不久"
                : (closeEnough ? "已经够熟" : (mercy ? "本来不够熟，但被他磨得没了耐心" : "还很生疏"));
        String note = "（玩家 " + player.getName() + " 在你的背包界面点了「" + zh
                + "」（材质 " + mat.name() + "），向你讨要 " + want + " 个。你俩的关系：" + relation + "。"
                + (allow
                        ? "你刚刚已经当场从自己背包里数出 " + want + " 个给他了（现在就在他手上）。"
                          + "只用一句中文台词把这件事说出来即可，不要再调用 give_item —— 没有东西可给了。"
                        : "请用一句中文台词礼貌地婉拒他，什么都别给。台词里绝不能承诺「给你」「拿着」这类话，"
                          + "因为你确实什么都没给。")
                + "台词要直接说出来，不能只调用工具。）";
        plugin.getLogger().info("§b[MC-AI] 索要: " + player.getName() + " → " + displayName()
                + " " + mat.name() + " x" + want + " 好感=" + String.format("%.0f", fav)
                + " 门槛=" + String.format("%.0f", need)
                + " → " + (allow
                        ? (reclaim ? "物归原主，还（代码层已交付）"
                                   : (mercy ? "磨到松口，给（代码层已交付）" : "够熟，给（代码层已交付）"))
                        : "婉拒"));
        if (failReason != null) player.sendMessage(failReason);
        planner.onPlayerMessage(state, player, note);
    }
}
