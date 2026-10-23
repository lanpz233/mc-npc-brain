package com.howard.mcai.bot;

import com.howard.mcai.McaiPlugin;
import com.howard.mcai.ai.NameLibrary;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 假人总管：招募/认领/遣散、档案存取、快慢两拍 tick 的注册点、喊话通道、/ai bot 命令。
 * <p>
 * 与 Leaves 的全部交互只走两条路：控制台命令（/bot create/remove/action）
 * 和 Bukkit API（CraftBot 就是个 Player）。不依赖 Leaves 的 Java API，换版本不怕。
 */
public final class BotService {

    private static final String[] NAME_POOL = {
            "Alex", "Ben", "Carl", "Dana", "Eli", "Finn", "Gus", "Hank",
            "Ivy", "Jack", "Kate", "Liam", "Mona", "Nina", "Omar", "Paul",
            "Rosa", "Sam", "Tina", "Victor", "Wade", "Xena", "Yuri", "Zoe"
    };

    private final McaiPlugin plugin;
    private final BotStore store;
    private final Map<String, BotAgent> byAccount = new LinkedHashMap<>();
    private final Map<UUID, BotAgent> byUuid = new LinkedHashMap<>();
    private final BotChatService chatService;
    private int slowTicks;

    public BotService(McaiPlugin plugin) {
        this.plugin = plugin;
        this.store = new BotStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.chatService = new BotChatService(this);
    }

    public McaiPlugin plugin() { return plugin; }
    public BotChatService chat() { return chatService; }
    public Collection<BotAgent> agents() { return byAccount.values(); }

    public BotAgent agentByUuid(UUID uuid) { return byUuid.get(uuid); }

    public BotAgent findAgent(String name) {
        for (BotAgent a : byAccount.values())
            if (a.state.accountName.equalsIgnoreCase(name) || a.state.chineseName.equals(name)) return a;
        return null;
    }

    // ============ 生命周期 ============

    public void load() {
        for (BotState s : store.load()) {
            BotAgent a = new BotAgent(s, new BotDriver(plugin.getNavService()));
            byAccount.put(s.accountName.toLowerCase(), a);
        }
        plugin.getLogger().info("[MC-AI] 假人档案载入 " + byAccount.size() + " 份");
    }

    public void start() {
        // 快拍（2 tick）：方向盘推速度
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (BotAgent a : byAccount.values())
                if (a.alive() && a.driver.driving()) a.driver.tickFast(a.player);
        }, 10L, 2L);
        // 慢拍（1 秒）：大脑 + 认领扫描 + 落盘
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            slowTicks++;
            if (slowTicks % 10 == 0) adoptScan();
            for (BotAgent a : byAccount.values()) {
                try { BotBrain.tick(this, a); }
                catch (Throwable t) { plugin.getLogger().warning("[MC-AI] 假人大脑异常 " + a.state.accountName + ": " + t); }
            }
            if (slowTicks % 30 == 0 && store.isDirty()) saveAll();
        }, 20L, 20L);
    }

    public void shutdown() { saveAll(); }

    public void storeDirty() { store.markDirty(); }
    public void storeDirtySoon() { store.markDirty(); }

    private void saveAll() {
        List<BotState> all = new ArrayList<>();
        for (BotAgent a : byAccount.values()) all.add(a.state);
        store.save(all);
    }

    // ============ 认领（Leaves 里的假人都是 CraftBot） ============

    static boolean isBotPlayer(Player p) {
        return p != null && p.getClass().getName().contains("CraftBot");
    }

    /**
     * 认领扫描（每 10 秒）：Leaves 假人不进 Bukkit 在线列表、也不触发 Join 事件
     * （探针实测），唯一可靠的发现通道是扫各世界活体实体——CraftBot 是 Player 的子类。
     * 顺带把已消失（死亡/被删）的活体摘掉。
     */
    private void adoptScan() {
        for (BotAgent a : byAccount.values()) {
            if (a.player != null && (!a.player.isValid()
                    || Bukkit.getPlayerExact(a.state.accountName) == null)) {
                Player gone = a.player;
                onBotGone(gone);
                // 死亡事件对假人不触发（实测 PlayerDeath/EntityDeath 都不来）：
                // 不是我们遣散的、却凭空消失 = 按死亡记账（/bot 手动删会误记，代价可接受）
                if (!a.expectGone) {
                    a.state.deaths++;
                    a.state.addFact("死过一次");
                    a.state.fear = Math.min(100, a.state.fear + 30);
                    plugin.getLogger().info("[MC-AI] 假人 " + a.state.chineseName + "（" + a.state.accountName
                            + "）消失，按死亡记账，累计 " + a.state.deaths + " 次");
                    storeDirty();
                }
                a.expectGone = false;
            }
        }
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.LivingEntity e : w.getLivingEntities()) {
                if (e instanceof Player p && isBotPlayer(p)) adoptPlayer(p);
            }
        }
    }

    /** 有玩家实体加入时由监听器调用：是假人就认领（档案没有就现场建档）。 */
    public void adoptPlayer(Player p) {
        if (!isBotPlayer(p) || byUuid.containsKey(p.getUniqueId())) return;
        BotAgent a = byAccount.get(p.getName().toLowerCase());
        if (a == null) {
            String gender = NameLibrary.randomGender();
            BotState s = new BotState(p.getName(),
                    plugin.uniqueRandomName(gender, -1), gender, p.getName());
            s.personalityTagIds = com.howard.mcai.ai.PersonalityTagLibrary.drawTagIds(false);
            a = new BotAgent(s, new BotDriver(plugin.getNavService()));
            byAccount.put(s.accountName.toLowerCase(), a);
            plugin.getLogger().info("[MC-AI] 认领了野假人 " + p.getName() + "（中文名 " + s.chineseName + "）");
        }
        attach(a, p);
        storeDirty();
    }

    private void attach(BotAgent a, Player p) {
        a.player = p;
        byUuid.put(p.getUniqueId(), a);
        if (a.state.bornDay < 0) a.state.bornDay = p.getWorld().getFullTime() / 24000L;
        a.state.lastSeenMillis = System.currentTimeMillis();
    }

    /** 假人死亡/消失后由监听器调用：摘掉活体引用，档案保留。 */
    public void onBotGone(Player p) {
        BotAgent a = byUuid.remove(p.getUniqueId());
        if (a != null && a.player == p) { a.player = null; a.mode = BotAgent.Mode.IDLE; }
    }

    // ============ 招募 / 遣散 ============

    /**
     * 招募。customName 是玩家在 /ai bot new 后面给的名字（Fong 要求可自定义）：
     * 英文/数字/下划线（3~16 位）当账号名用；其它一律当中文名用。皮肤借招募者自己的。
     */
    public void recruit(Player requester, String customName) {
        String account = null;
        String chinese = null;
        if (customName != null && !customName.isBlank()) {
            String name = customName.trim();
            if (name.matches("[A-Za-z0-9_]{3,16}")) {
                if (byAccount.containsKey(name.toLowerCase()) || Bukkit.getPlayerExact(name) != null) {
                    requester.sendMessage("§c[MC-AI] 账号名 " + name + " 已经被占用了，换一个名字再试。");
                    return;
                }
                account = name;
            } else {
                chinese = name.length() > 10 ? name.substring(0, 10) : name;
            }
        }
        if (account == null) account = freeAccountName();
        if (account == null) { requester.sendMessage("§c[MC-AI] 名字池用完了，招不了更多假人。"); return; }
        final String accountFinal = account;
        String gender = NameLibrary.randomGender();
        BotState s = new BotState(account,
                chinese != null ? chinese : plugin.uniqueRandomName(gender, -1), gender,
                requester.getName());
        s.personalityTagIds = com.howard.mcai.ai.PersonalityTagLibrary.drawTagIds(false);
        BotAgent a = new BotAgent(s, new BotDriver(plugin.getNavService()));
        byAccount.put(account.toLowerCase(), a);
        Location at = requester.getLocation();
        dispatchConsole("bot create " + account + " " + s.skinName + " "
                + at.getX() + " " + at.getY() + " " + at.getZ());
        requester.sendMessage("§a[MC-AI] 正在招募 " + s.chineseName + "（" + account + "）……");
        // Leaves 的 /bot create 是异步的：假人要走完登录流程才出现，实测能到 5 秒
        // （v0.7.1 真服事故：原先只等 2 秒就报"招募失败"，人其实在后面到了）。
        // 改为轮询等 15 秒，人一到就接上后续流程；认领扫描可能先 attach，这里兼容。
        new org.bukkit.scheduler.BukkitRunnable() {
            private int tries;
            @Override public void run() {
                Player p = a.player != null ? a.player : Bukkit.getPlayerExact(accountFinal);
                if (p == null) {
                    if (++tries >= 30) {
                        cancel();
                        plugin.getLogger().warning("[MC-AI] 招募失败：等了 15 秒假人 " + accountFinal + " 还没出现");
                        if (requester.isOnline())
                            requester.sendMessage("§c[MC-AI] 招募失败，等了 15 秒假人还没来——看下控制台 Leaves 有没有报错。");
                    }
                    return;
                }
                cancel();
                if (a.player == null) attach(a, p);
                // 创建坐标会被 Leaves 忽略（探针实测）→ 传送到请求者面前站好
                Location front;
                if (requester.isOnline()) {
                    front = requester.getLocation().clone()
                            .add(requester.getLocation().getDirection().setY(0).normalize().multiply(2.0));
                    front.setY(requester.getWorld().getHighestBlockYAt(front) + 1);
                    p.teleport(front);
                } else {
                    front = p.getLocation();
                }
                starterKit(p);
                s.setBase(front.getWorld().getName(), front.getX(), front.getY(), front.getZ());
                s.addFact("被 " + requester.getName() + " 招募来到这个世界");
                storeDirty(); saveAll();
                say(a, "大家好，我叫" + s.chineseName + "，刚来这儿，以后请多关照！");
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    private void starterKit(Player p) {
        boolean empty = true;
        for (ItemStack it : p.getInventory().getContents()) if (it != null) { empty = false; break; }
        if (!empty) return;
        p.getInventory().addItem(new ItemStack(Material.BREAD, 8));
        p.getInventory().addItem(new ItemStack(Material.WOODEN_AXE));
        p.getInventory().addItem(new ItemStack(Material.WOODEN_PICKAXE));
        p.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD));
        p.getInventory().addItem(new ItemStack(Material.FISHING_ROD));
        p.getInventory().addItem(new ItemStack(Material.TORCH, 8));
    }

    private String freeAccountName() {
        for (String base : NAME_POOL) {
            if (!byAccount.containsKey(base.toLowerCase()) && Bukkit.getPlayerExact(base) == null) return base;
            for (int i = 2; i <= 9; i++) {
                String cand = base + i;
                if (!byAccount.containsKey(cand.toLowerCase()) && Bukkit.getPlayerExact(cand) == null) return cand;
            }
        }
        return null;
    }

    public void dismiss(BotAgent a) {
        a.expectGone = true; // 遣散不算死亡
        say(a, "那我先走了，保重！"); // 先说再走：remove 之后活体引用就被摘了，话发不出去
        dispatchConsole("bot remove " + a.state.accountName);
        if (a.player != null) onBotGone(a.player);
    }

    // ============ 与 Leaves 说话的管道 ============

    public void dispatchConsole(String cmd) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    public void dispatchAction(String accountName, String action) {
        dispatchConsole("bot action " + accountName + " " + action);
    }

    // ============ 喊话（插件代发，与村民台词同一制式） ============

    public void say(BotAgent a, String text) {
        bubble(a, null, text);
        broadcastNear(a, "§6[" + a.state.chineseName + "] §f" + text);
    }

    public void sayTo(BotAgent a, Player to, String text) {
        bubble(a, to.getName(), text);
        broadcastNear(a, "§6[" + a.state.chineseName + ">" + to.getName() + "] §f" + text);
    }

    /** 头顶气泡（与村民同款 BubbleManager 会话框）；[chat] 日志由 BubbleManager 统一打，避免双份。 */
    private void bubble(BotAgent a, String listenerName, String text) {
        if (a.player == null || plugin.getBubbleManager() == null) {
            plugin.getLogger().info("[chat] " + a.state.chineseName
                    + (listenerName == null ? "" : ">" + listenerName) + ": " + text);
            return;
        }
        plugin.getBubbleManager().showDialog(a.player, a.state.chineseName, listenerName, text);
    }

    private void broadcastNear(BotAgent a, String line) {
        if (a.player == null) return;
        Location at = a.player.getLocation();
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) <= 40 * 40) p.sendMessage(line);
    }

    // ============ /ai bot 命令 ============

    public void handleCommand(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
        switch (sub) {
            case "new" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("只有玩家能招募假人（要站在他出现的位置）。"); return; }
                recruit(p, args.length >= 3 ? args[2] : null);
            }
            case "list" -> {
                if (byAccount.isEmpty()) { sender.sendMessage("§7[MC-AI] 还没有假人。用 /ai bot new 招募一个。"); return; }
                for (BotAgent a : byAccount.values()) {
                    BotState s = a.state;
                    String status = a.alive() ? "在线·" + modeZh(a) : "不在场";
                    String home = s.hasBase() ? "家在 " + (int) s.baseX + "," + (int) s.baseZ : "还没安家";
                    sender.sendMessage("§e" + s.chineseName + "§7（" + s.accountName + "）" + status + "，" + home
                            + (s.deaths > 0 ? "，死过 " + s.deaths + " 次" : ""));
                }
            }
            case "remove" -> {
                BotAgent a = args.length >= 3 ? findAgent(args[2]) : null;
                if (a == null) { sender.sendMessage("§c[MC-AI] 没找到这个假人，先 /ai bot list 看看。"); return; }
                dismiss(a);
                sender.sendMessage("§a[MC-AI] 已让 " + a.state.chineseName + " 离开（档案保留，同名再招募还是他）。");
            }
            case "stop" -> {
                BotAgent a = args.length >= 3 ? findAgent(args[2]) : null;
                if (a == null || !a.alive()) { sender.sendMessage("§c[MC-AI] 这个假人不在场。"); return; }
                a.haltAll(this);
                sender.sendMessage("§a[MC-AI] 已让 " + a.state.chineseName + " 停下。");
            }
            default -> sender.sendMessage("§7用法：/ai bot new [名字] | list | remove <名> | stop <名>（new 后面填中文就是他的中文名，填英文就是账号名）");
        }
    }

    private String modeZh(BotAgent a) {
        return switch (a.mode) {
            case IDLE -> "闲着";
            case FOLLOW -> "跟随中";
            case GOTO -> "赶路中";
            case WORK -> "干活中";
            case RETURN_BASE -> "回家中";
            case DEPOSIT -> "存东西";
            case BUILD -> "盖房中";
            case REST -> "休息中";
            case FLEE -> "逃命中";
        };
    }
}
