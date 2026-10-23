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
 * NPC 背包 GUI —— 玩家对着村民按 F 打开，展示该村民的真实库存；他没看着你时可以偷（走 TheftService）。
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
 *   <li>物品**只能通过 TheftService 出货**（偷窃的 2 秒托管结算），GUI 本身对所有
 *       点击/拖拽事件 cancel，玩家不能直接把东西拖走
 *       （{@link com.howard.mcai.listener.VillagerInventoryListener} 负责拦截）。</li>
 *   <li>索要与右键交换已于 v0.4.0 删除（服主定）：想要他的东西只剩买、等他送、偷三条路。</li>
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
    /** 界面模式（v0.4.1）：false=查看（普通 F，可选货谈换物） true=偷窃（蹲下+F） */
    private final boolean stealMode;
    /** 查看模式下选中的"想要的货"（换物提议用），null = 还没选 */
    private Material selectedMat;

    private VillagerInventoryGui(McaiPlugin plugin, VillagerState state, Player viewer) {
        this(plugin, state, viewer, false);
    }

    private VillagerInventoryGui(McaiPlugin plugin, VillagerState state, Player viewer, boolean stealMode) {
        this.plugin = plugin;
        this.state = state;
        this.villagerUuid = state.getUniqueId();
        this.viewerUuid = viewer.getUniqueId();
        this.stealMode = stealMode;
        this.inventory = Bukkit.createInventory(this, SIZE, title());
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
        // 蹲下+F = 偷窃界面；普通 F = 查看界面（v0.4.1 服主定）
        boolean steal = viewer.isSneaking();
        VillagerInventoryGui gui = new VillagerInventoryGui(plugin, state, viewer, steal);
        gui.rebuild();
        viewer.openInventory(gui.inventory);
        // 偷窃守望只在偷窃模式登记（且只在他真有未结算的赃时才收网）
        if (steal && plugin.getTheftService() != null) plugin.getTheftService().openSession(viewer, state);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 窗口标题：开了资源包皮肤 → 背包专属背景字形（皮背包，素材阶段 4，
     * 与商店柜台同字体 mcai_shop 的 \ue110）；没开 → 普通文字标题。
     */
    private net.kyori.adventure.text.Component title() {
        if (plugin.getPluginConfig().shopSkinEnabled()) {
            return net.kyori.adventure.text.Component.text("\uF808\uE110")
                    .font(net.kyori.adventure.key.Key.key("minecraft", "mcai_shop"));
        }
        return net.kyori.adventure.text.Component.text("§6" + displayName() + "的背包");
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
        favorLore.add("§8亲信门槛：好感≥" + fmt(plugin.getPluginConfig().confidantFavor()) + " 算亲信");
        favorLore.add("§7—— 对各位玩家的好感 ——");
        favorLore.addAll(topList(favorEntries(), "§8（还没有任何好感记录）"));
        putStatusIcon(47, ShopIcons.HEART, "§6§l情绪与好感", favorLore);

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
        putStatusIcon(49, ShopIcons.BOND, "§6§l社会关系", relLore);

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
        String recentSay = state.recentSocialContext();
        if (recentSay != null && !recentSay.isBlank()) {
            memLore.add("§7—— 最近在群里说过 ——");
            memLore.add("§f" + clip(recentSay, 160));
        }
        putStatus(51, Material.WRITTEN_BOOK, "§6§l记忆概况", memLore);

        // 52 背包与交易
        Map<String, Integer> inv = state.inventorySnapshot();
        int totalItems = inv.values().stream().mapToInt(Integer::intValue).sum();
        List<String> invLore = new ArrayList<>();
        invLore.add("§7物品种类: §f" + inv.size() + " §7总件数: §f" + totalItems);
        int tradeLvl = state.tradeLevel();
        int toNext = com.howard.mcai.ai.TradeLevels.tradesToNextLevel(state.tradeCount(), tradeLvl);
        invLore.add("§7买卖等级: §f" + tradeLvl + " §7级（共成交 " + state.tradeCount() + " 笔"
                + (toNext < 0 ? "，已满级" : "，再 " + toNext + " 笔升级") + "）");
        invLore.add("§7动态改价商品: §f" + state.dynamicPrices().size() + " §7种");
        invLore.add("§7当前上架: §f" + state.offeredItems().size() + " §7种");
        var vLoc = state.entity() != null ? state.entity().getLocation() : null;
        if (vLoc != null && vLoc.getWorld() != null) {
            int vid = plugin.getVillageTrust().findVillageId(vLoc.getWorld().getName(), vLoc.getX(), vLoc.getZ());
            if (vid >= 0) {
                int tScore = plugin.getVillageTrust().score(viewerUuid, vid);
                invLore.add("§7村里对你的信任: §f" + tScore + " §7（"
                        + com.howard.mcai.ai.VillageTrust.tierZh(tScore) + "）");
            }
        }
        invLore.add(stealMode
                ? "§8（偷窃中：点东西后稍等片刻才到手，他看见就完蛋）"
                : "§8（选他的货 + 点你的货 = 谈换物；蹲下按 F 进偷窃界面）");
        putStatus(52, Material.CHEST, "§6§l背包与交易", invLore);

        // 53 跟随开关（v0.5.0，原“最近言行”卡已并入 51 记忆概况）
        buildFollowCard();
    }

    /**
     * 跟随开关卡（53 格）：查看模式可点 —— 这是插件里少数“能点的状态卡”，
     * 点击走 {@link #handleFollowClick}（不在 slotMap 里，单独分流）。
     * 文案按他此刻的跟随状态变：跟着你=取消键、正跟别人=灰、正忙等=把“磨他”的玩法写明。
     */
    private void buildFollowCard() {
        var follow = plugin.getFollowService();
        List<String> lore = new ArrayList<>();
        String title;
        if (stealMode) {
            title = "§8§l跟随";
            lore.add("§7偷窃界面里不谈这个。");
            lore.add("§8（站直了再开背包，这里才是查看界面）");
        } else if (follow != null && follow.isFollowing(state, viewerUuid)) {
            title = "§c§l别跟了（点击取消跟随）";
            lore.add("§7他正在跟着你。");
            lore.add("§e点击 → 他停下，不再跟着你。");
        } else if (state.followingPlayer() != null && follow != null
                && follow.isFollowing(state, state.followingPlayer())) {
            title = "§8§l他正跟着别人";
            Player p = Bukkit.getPlayer(state.followingPlayer());
            lore.add("§7他正跟着 " + (p != null ? p.getName() : "另一位玩家") + "，叫不走。");
        } else {
            title = "§a§l让他跟着你（点击）";
            lore.add("§7他没什么事的话，一般都会答应。");
            lore.add("§7他不肯？多点几次——把他磨烦了，没准就跟你走了。");
        }
        putStatus(53, Material.LEAD, title, lore);
    }

    /** 跟随开关点击：跟着我=取消；没跟=请求（裁定与台词归 FollowService，这里只给玩家系统提示） */
    private void handleFollowClick(Player player) {
        var follow = plugin.getFollowService();
        if (follow == null) return;
        if (follow.isFollowing(state, viewerUuid)) {
            follow.stopFollow(state, player);
            player.sendMessage("§7[MC-AI] 他停下来了，不再跟着你。");
            rebuild();
            return;
        }
        var d = follow.requestFollow(state, player);
        if (d == null) return;
        switch (d) {
            case AGREE -> player.sendMessage("§a[MC-AI] 他答应了，开始跟着你走。");
            case AGREE_GRUDGING -> player.sendMessage("§e[MC-AI] 他被你磨得不耐烦，还是一脸不情愿地跟上了。");
            case REFUSE_BUSY -> player.sendMessage("§e[MC-AI] 他手头正忙，走不开。（不死心就多点几次）");
            case REFUSE_SLEEPING -> player.sendMessage("§e[MC-AI] 他正睡着呢。（多点几次能把他磨醒跟你走）");
            case REFUSE_SCARED -> player.sendMessage("§e[MC-AI] 他吓坏了，不敢乱走。（多点几次试试）");
            case REFUSE_NEED -> player.sendMessage("§e[MC-AI] 他有迫切的事没解决，走不开。（多点几次试试）");
            case REFUSE_FOLLOWING_OTHER -> player.sendMessage("§e[MC-AI] 他正跟着别人呢，叫不走。");
        }
        rebuild();
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

    /** 信息卡（图标版）：用资源包专属图标替代容易误解的原版物品（金苹果=情绪、盔甲架=关系都太绕） */
    private void putStatusIcon(int slot, int cmd, String name, List<String> lore) {
        inventory.setItem(slot, ShopIcons.button(cmd, name, lore));
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

    /** 库存格物品：材质 = 库存物，数量 = 库存数，lore 按当前能不能下手给提示 */
    private ItemStack buildItem(Material mat, int amount) {
        ItemStack stack = new ItemStack(mat, Math.max(1, Math.min(amount, 64)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + PromptBuilder.materialToZh(mat));
            List<String> lore = new ArrayList<>();
            lore.add("§7库存: §e" + amount);
            if (stealMode) {
                Player viewer = Bukkit.getPlayer(viewerUuid);
                var theft = plugin.getTheftService();
                if (theft != null && theft.isPending(viewerUuid, villagerUuid, mat)) {
                    lore.add("§8正在下手……（稍等片刻见分晓）");
                } else if (viewer != null && theft != null
                        && com.howard.mcai.security.TheftService.canSteal(viewer, state)) {
                    lore.add("§a点击 → 偷走 1 个（轻点声）");
                } else {
                    lore.add("§7他正看着你，下不了手");
                }
            } else if (mat == selectedMat) {
                lore.add("§a已选中：点你背包里的东西，跟他谈换");
                lore.add("§7再点一次取消选中");
            } else {
                lore.add("§e点击选中：想要这件？再点你背包里的东西出价");
            }
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
     * 偷窃模式：点"真货格" = 试图偷走 1 个（走 TheftService 托管结算）。
     * 查看模式：点"真货格" = 选中想换的货（再点自己背包里的东西出价谈换物）。
     * 底板/状态卡一律忽略；玩家自己背包的点击走 {@link #handleOwnItemClick}。
     */
    public void handleClick(Player player, int slot, boolean rightClick) {
        if (player == null || !player.getUniqueId().equals(viewerUuid)) return;
        // 跟随开关（53 格，查看模式专属）：不在 slotMap 里，先于真货格分流
        if (slot == 53 && !stealMode) {
            handleFollowClick(player);
            return;
        }
        Material mat = slotMap.get(slot);
        if (mat == null) return;
        if (stealMode) {
            var theft = plugin.getTheftService();
            if (theft == null) return;
            String err = theft.beginSteal(player, state, mat, this::refreshIfOpen);
            if (err != null) {
                long now = System.currentTimeMillis();
                if (now - lastWarnMs > 1500L) {
                    lastWarnMs = now;
                    player.sendMessage(err);
                }
                return;
            }
            rebuild();   // 立刻把这一格刷成"正在下手"
            return;
        }
        // 查看模式：选中/取消选中想换的货
        selectedMat = (mat == selectedMat) ? null : mat;
        rebuild();
    }

    /**
     * 玩家点了自己背包里的格子（查看模式的换物出价口）：
     * 已选中 NPC 的货 → 登记提议、关界面、把插件算好的简报递给 NPC 模型裁决；
     * 没选中 → 提醒先选货。偷窃模式下自己背包的点击一律忽略。
     */
    public void handleOwnItemClick(Player player, int ownSlot) {
        if (stealMode || player == null || !player.getUniqueId().equals(viewerUuid)) return;
        if (selectedMat == null) {
            long now = System.currentTimeMillis();
            if (now - lastWarnMs > 1500L) {
                lastWarnMs = now;
                player.sendMessage("§7先点他背包里你想要的东西，再点你背包里的东西跟他谈换。");
            }
            return;
        }
        ItemStack stack = player.getInventory().getItem(ownSlot);
        if (stack == null || stack.getType().isAir()) return;
        Material npcMat = selectedMat;
        Material offerMat = stack.getType();
        int offerCount = stack.getAmount();
        state.setPendingBarter(player.getUniqueId(), npcMat, offerMat, offerCount);
        player.closeInventory();
        boolean hungry = state.needs().get(com.howard.mcai.mob.NeedsState.HUNGER) >= 50.0;
        String note = com.howard.mcai.ai.BarterBrief.brief(player.getName(), state.profession(),
                npcMat, offerMat, offerCount, hungry);
        plugin.getPlanner().switchConversation(player, state);
        plugin.getPlanner().onPlayerMessage(state, player, note, true);
    }

    /** 偷窃结算后回调：窗口还开着就原地刷新（东西到手/被没收都要让格子对上真实库存） */
    private void refreshIfOpen() {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        if (viewer == null || !viewer.isOnline()) return;
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
            rebuild();
        }
    }

    /** 窗口关掉（玩家主动关 / 被抓收网关）：注销偷窃守望会话 */
    public void onClosed(Player player) {
        if (player != null && player.getUniqueId().equals(viewerUuid)
                && plugin.getTheftService() != null) {
            plugin.getTheftService().closeSession(player);
        }
    }

    /** 偷窃警告节流（连点时别刷屏） */
    private long lastWarnMs = 0L;
}
