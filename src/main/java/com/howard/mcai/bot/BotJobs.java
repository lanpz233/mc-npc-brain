package com.howard.mcai.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;

/**
 * 假人的各种活计（状态机，每秒被 BotBrain 推进一步；快动作靠 Leaves 的持续 action）。
 * <p>
 * 统一约定：动作命令（look/break/fish/attack/stop）走控制台分发；走动走 BotDriver；
 * 每种活都有"干完/干不下去"出口 —— 出口时自己收尾（停动作、喊一嗓子、模式回 IDLE）。
 */
public final class BotJobs {

    private BotJobs() {}

    // ============ 通用小工具 ============

    static boolean isLog(Material m) { return m.name().endsWith("_LOG") || m.name().endsWith("_STEM"); }
    static boolean isStoneish(Material m) {
        return m == Material.STONE || m == Material.COBBLESTONE || m == Material.DEEPSLATE
                || m == Material.ANDESITE || m == Material.DIORITE || m == Material.GRANITE
                || m.name().endsWith("_ORE");
    }

    static void lookAt(BotService svc, BotAgent a, Location t) {
        svc.dispatchAction(a.state.accountName,
                "look " + t.getX() + " " + t.getY() + " " + t.getZ());
    }

    /** 背包里某种材料的总量。 */
    public static int countItem(Player p, Material mat) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == mat) n += it.getAmount();
        return n;
    }

    /** 从背包扣指定材料 n 个（不够扣则全扣并返回实际扣数）。 */
    static int takeItem(Player p, Material mat, int n) {
        int left = n;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(i, null);
            left -= take;
        }
        return n - left;
    }

    static boolean inventoryFull(Player p) {
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it == null || it.getType() == Material.AIR) return false;
        return true;
    }

    // ============ 战斗（守夜 + 自卫共用） ============

    /**
     * 朝最近的敌对怪走过去打。返回 true = 正在交战（含赶路）。
     * post 不为 null 时，无怪可打就回 post 站着。
     */
    static boolean combatTick(BotService svc, BotAgent a, Location post, double radius) {
        Player bot = a.player;
        Entity target = null;
        if (a.combatTarget != null) {
            Entity e = bot.getWorld().getEntity(a.combatTarget);
            if (e != null && !e.isDead() && e.getLocation().distanceSquared(bot.getLocation()) <= radius * radius * 2)
                target = e;
            else a.combatTarget = null;
        }
        if (target == null) {
            double best = radius * radius;
            for (Entity e : bot.getWorld().getNearbyEntities(bot.getLocation(), radius, radius, radius)) {
                if (e instanceof Monster && !e.isDead()) {
                    double d = e.getLocation().distanceSquared(bot.getLocation());
                    if (d < best) { best = d; target = e; }
                }
            }
            if (target != null) a.combatTarget = target.getUniqueId();
        }
        if (target == null) {
            svc.dispatchAction(a.state.accountName, "stop");
            if (post != null && bot.getLocation().distanceSquared(post) > 4) {
                if (!a.driver.driving()) a.driver.goTo(bot, post, 1.2);
            }
            return false;
        }
        Location tl = target.getLocation();
        double d = Math.hypot(tl.getX() - bot.getLocation().getX(), tl.getZ() - bot.getLocation().getZ());
        if (d > 2.2) {
            if (!a.driver.driving()) a.driver.goTo(bot, tl, 1.8);
        } else {
            a.driver.stop(bot);
            lookAt(svc, a, tl.clone().add(0, 1, 0));
            svc.dispatchAction(a.state.accountName, "attack 8 -1");
        }
        return true;
    }

    // ============ 钓鱼 ============

    static void fishTick(BotService svc, BotAgent a) {
        Player bot = a.player;
        if (a.phase == 0) {
            // 找水边站位：12 格内找「水方块旁的实心地」
            Location shore = findShore(bot.getLocation(), 12);
            if (shore == null) { svc.say(a, "这附近没水，钓不了鱼。"); a.haltAll(svc); return; }
            a.target = shore;
            a.handle = a.driver.goTo(bot, shore, 1.0);
            a.phase = 1;
            return;
        }
        if (a.phase == 1) {
            if (a.handle != null && !a.handle.done()) return;
            if (a.handle != null && a.handle.failed()) { svc.say(a, "我走不到水边去。"); a.haltAll(svc); return; }
            // 面向最近的水
            Block water = nearestBlock(bot.getLocation(), 6, Material.WATER);
            if (water == null) { svc.say(a, "水呢？刚还在的……"); a.haltAll(svc); return; }
            lookAt(svc, a, water.getLocation().add(0.5, 0.3, 0.5));
            if (countItem(bot, Material.FISHING_ROD) == 0) {
                svc.say(a, "我没鱼竿，钓不了。"); a.haltAll(svc); return;
            }
            equipMainHand(bot, Material.FISHING_ROD);
            svc.dispatchAction(a.state.accountName, "fish");
            a.phase = 2;
            a.phaseUntil = System.currentTimeMillis() + 10 * 60_000L;
            svc.say(a, "好，我在这儿钓鱼。");
            return;
        }
        // phase 2：钓着呢 —— Leaves 的 fish 动作自带收竿循环；这里只看总时长与突发状况
        if (System.currentTimeMillis() > a.phaseUntil) {
            svc.say(a, "钓得差不多了，我先收竿。");
            a.haltAll(svc);
        }
    }

    private static Location findShore(Location from, int radius) {
        World w = from.getWorld();
        int bx = from.getBlockX(), bz = from.getBlockZ(), by = from.getBlockY();
        for (int r = 1; r <= radius; r++)
            for (int x = bx - r; x <= bx + r; x++)
                for (int z = bz - r; z <= bz + r; z++)
                    for (int y = by - 2; y <= by + 2; y++) {
                        if (w.getBlockAt(x, y, z).getType() != Material.WATER) continue;
                        // 水的四邻找可站的实心地（上面两格空）
                        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (int[] d : dirs) {
                            Block g = w.getBlockAt(x + d[0], y - 1, z + d[1]);
                            if (g.getType().isSolid()
                                    && !w.getBlockAt(x + d[0], y, z + d[1]).getType().isSolid()
                                    && !w.getBlockAt(x + d[0], y + 1, z + d[1]).getType().isSolid())
                                return new Location(w, x + d[0] + 0.5, y, z + d[1] + 0.5);
                        }
                    }
        return null;
    }

    static Block nearestBlock(Location from, int radius, Material mat) {
        World w = from.getWorld();
        Block best = null; double bd = Double.MAX_VALUE;
        for (int x = from.getBlockX() - radius; x <= from.getBlockX() + radius; x++)
            for (int y = from.getBlockY() - 2; y <= from.getBlockY() + 2; y++)
                for (int z = from.getBlockZ() - radius; z <= from.getBlockZ() + radius; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != mat) continue;
                    double d = b.getLocation().distanceSquared(from);
                    if (d < bd) { bd = d; best = b; }
                }
        return best;
    }

    static void equipMainHand(Player p, Material mat) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == mat) { inv.setHeldItemSlot(i); return; }
        }
        for (int i = 9; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == mat) {
                inv.setItem(inv.getHeldItemSlot(), it.clone());
                inv.setItem(i, null);
                return;
            }
        }
    }

    // ============ 砍树 / 敲石（找最近目标块，走过去敲掉，循环） ============

    static void gatherTick(BotService svc, BotAgent a, boolean logs) {
        Player bot = a.player;
        long now = System.currentTimeMillis();
        if (a.phaseUntil == 0) a.phaseUntil = now + 5 * 60_000L;
        if (now > a.phaseUntil) {
            svc.say(a, logs ? "砍得差不多了，先歇歇。" : "敲得差不多了，先歇歇。");
            a.haltAll(svc); return;
        }
        // 身边 4 格内有目标 → 对准开敲
        Block near = nearestMatching(bot.getLocation(), 4, logs);
        if (near != null) {
            a.driver.stop(bot);
            lookAt(svc, a, near.getLocation().add(0.5, 0.5, 0.5));
            equipForBlock(bot, near.getType());
            svc.dispatchAction(a.state.accountName, "break 8 -1");
            return;
        }
        svc.dispatchAction(a.state.accountName, "stop");
        // 远一点找下一棵/下一块
        Block far = nearestMatching(bot.getLocation(), 18, logs);
        if (far == null) {
            svc.say(a, logs ? "附近的树砍完了。" : "附近没石头可敲了。");
            a.haltAll(svc); return;
        }
        if (!a.driver.driving())
            a.handle = a.driver.goTo(bot, far.getLocation().add(0.5, 0, 0.5), 2.2);
    }

    static Block nearestMatching(Location from, int radius, boolean logs) {
        World w = from.getWorld();
        Block best = null; double bd = Double.MAX_VALUE;
        for (int x = from.getBlockX() - radius; x <= from.getBlockX() + radius; x++)
            for (int y = from.getBlockY() - 3; y <= from.getBlockY() + 6; y++)
                for (int z = from.getBlockZ() - radius; z <= from.getBlockZ() + radius; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    boolean hit = logs ? isLog(b.getType()) : isStoneish(b.getType());
                    if (!hit) continue;
                    double d = b.getLocation().distanceSquared(from);
                    if (d < bd) { bd = d; best = b; }
                }
        return best;
    }

    static void equipForBlock(Player bot, Material block) {
        String want = isLog(block) ? "_AXE" : "_PICKAXE";
        PlayerInventory inv = bot.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType().name().endsWith(want)) {
                if (i < 9) inv.setHeldItemSlot(i);
                else { inv.setItem(inv.getHeldItemSlot(), it.clone()); inv.setItem(i, null); }
                return;
            }
        }
    }

    // ============ 直线矿道 ============

    static void mineSetup(BotAgent a) {
        Player bot = a.player;
        float yaw = bot.getLocation().getYaw();
        // 朝向取整到卡迪尔方向
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));
        if (dx != 0) dz = 0; else dz = dz == 0 ? 1 : dz;
        a.mineDx = dx; a.mineDz = dz;
        a.mineSteps = 0; a.minePlan = 24;
        a.mineEntry = bot.getLocation().clone();
        a.phase = 0;
    }

    static void mineTick(BotService svc, BotAgent a) {
        Player bot = a.player;
        Location cur = bot.getLocation();
        if (a.phase == 2) { // 回程
            if (a.handle != null && a.handle.done()) {
                if (a.handle.failed()) {
                    // 走不回来且四下无人看 → 直接送回入口（保命兜底，不装样子）
                    bot.teleport(a.mineEntry);
                }
                svc.say(a, "挖完了，回来了。");
                a.haltAll(svc);
            }
            return;
        }
        if (a.phase == 1) { // 正在向前走那一步，等走完再看下一格
            if (a.handle == null || a.handle.done()) a.phase = 0;
            return;
        }
        // 当前步的目标格（脚/头两格）
        int tx = cur.getBlockX() + a.mineDx, tz = cur.getBlockZ() + a.mineDz;
        int ty = cur.getBlockY();
        World w = cur.getWorld();
        Block foot = w.getBlockAt(tx, ty, tz);
        Block head = w.getBlockAt(tx, ty + 1, tz);
        // 安全：前方脚下是空/岩浆/水 → 收工
        Block below = w.getBlockAt(tx, ty - 1, tz);
        if (below.getType() == Material.LAVA || foot.getType() == Material.LAVA || head.getType() == Material.LAVA) {
            svc.say(a, "前面有岩浆，不挖了，太危险。"); startMineReturn(svc, a); return;
        }
        if (!below.getType().isSolid() && below.getType() != Material.WATER) {
            int depth = 0; int yy = ty - 1;
            while (yy > ty - 5 && !w.getBlockAt(tx, yy, tz).getType().isSolid()) { depth++; yy--; }
            if (depth >= 4 || w.getBlockAt(tx, yy, tz).getType() == Material.LAVA) {
                svc.say(a, "前面是空的/深坑，不挖了。"); startMineReturn(svc, a); return;
            }
        }
        boolean blocked = foot.getType().isSolid() || head.getType().isSolid()
                || foot.getType() == Material.GRAVEL || head.getType() == Material.GRAVEL;
        if (blocked) {
            lookAt(svc, a, foot.getLocation().add(0.5, 0.8, 0.5));
            equipForBlock(bot, foot.getType().isSolid() ? foot.getType() : head.getType());
            svc.dispatchAction(a.state.accountName, "break 8 -1");
            return;
        }
        svc.dispatchAction(a.state.accountName, "stop");
        // 通了 → 前进一步
        a.mineSteps++;
        if (a.mineSteps >= a.minePlan) { svc.say(a, "这一趟挖够了。"); startMineReturn(svc, a); return; }
        Location step = new Location(w, tx + 0.5, ty, tz + 0.5);
        a.handle = a.driver.goTo(bot, step, 0.45);
        a.phase = 1;
    }

    private static void startMineReturn(BotService svc, BotAgent a) {
        svc.dispatchAction(a.state.accountName, "stop");
        a.phase = 2;
        a.handle = a.driver.goTo(a.player, a.mineEntry, 1.2);
    }

    // ============ 存箱子 ============

    /** 到了箱子边调用：把 DepositPlan 选中的货搬进箱子。返回存入总件数。 */
    static int doDeposit(BotService svc, BotAgent a) {
        BotState s = a.state;
        if (!s.hasChest) return 0;
        World w = svc.plugin().getServer().getWorld(s.chestWorld);
        if (w == null) return 0;
        Block block = w.getBlockAt(s.chestX, s.chestY, s.chestZ);
        if (!(block.getState() instanceof Chest chest)) return 0;
        Inventory chestInv = chest.getInventory();
        Player bot = a.player;
        // 快照（按材料名合并）→ 算要存的
        Map<String, Integer> totals = new HashMap<>();
        for (ItemStack it : bot.getInventory().getContents())
            if (it != null) totals.merge(it.getType().name(), it.getAmount(), Integer::sum);
        Map<String, Integer> plan = DepositPlan.toDeposit(totals);
        int moved = 0;
        PlayerInventory inv = bot.getInventory();
        for (Map.Entry<String, Integer> e : plan.entrySet()) {
            Material mat = Material.matchMaterial(e.getKey());
            if (mat == null) continue;
            int want = e.getValue();
            for (int i = 0; i < inv.getSize() && want > 0; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType() != mat) continue;
                int take = Math.min(want, it.getAmount());
                ItemStack moving = it.clone(); moving.setAmount(take);
                Map<Integer, ItemStack> leftover = chestInv.addItem(moving);
                int accepted = take - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (accepted > 0) {
                    it.setAmount(it.getAmount() - accepted);
                    if (it.getAmount() <= 0) inv.setItem(i, null);
                    want -= accepted; moved += accepted;
                }
                if (accepted < take) break; // 箱子满了
            }
        }
        return moved;
    }

    // ============ 盖房（图纸施工） ============

    /** 建房材料检查：返回缺料描述（null = 料齐）。planks 按任意木板合并算。 */
    static String buildShortage(Player bot) {
        int planks = 0, doors = 0, beds = 0, chests = 0;
        for (ItemStack it : bot.getInventory().getContents()) {
            if (it == null) continue;
            String n = it.getType().name();
            if (n.endsWith("_PLANKS")) planks += it.getAmount();
            else if (n.endsWith("_DOOR")) doors += it.getAmount();
            else if (n.endsWith("_BED")) beds += it.getAmount();
            else if (it.getType() == Material.CHEST) chests += it.getAmount();
        }
        int need = HutBlueprint.planksNeeded();
        StringBuilder sb = new StringBuilder();
        if (planks < need) sb.append("木板还差").append(need - planks).append("块；");
        if (doors < 1) sb.append("缺一扇门；");
        if (beds < 1) sb.append("缺一张床；");
        if (chests < 1) sb.append("缺一个箱子；");
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 找一块 5×5 平地（原点=西南角地面 Y）：同高、四周不悬空、上面两层空。 */
    static Location findBuildSite(Location around) {
        World w = around.getWorld();
        for (int r = 3; r <= 14; r += 1) {
            for (int ox = around.getBlockX() - r; ox <= around.getBlockX() + r; ox += 4)
                for (int oz = around.getBlockZ() - r; oz <= around.getBlockZ() + r; oz += 4) {
                    int gy = w.getHighestBlockYAt(ox, oz);
                    boolean ok = true;
                    for (int x = ox; x < ox + 5 && ok; x++)
                        for (int z = oz; z < oz + 5 && ok; z++) {
                            if (w.getHighestBlockYAt(x, z) != gy) ok = false;
                            else {
                                Material top = w.getBlockAt(x, gy, z).getType();
                                if (top == Material.WATER || top == Material.LAVA
                                        || isLog(top) || top == Material.BEDROCK) ok = false;
                            }
                        }
                    if (ok) return new Location(w, ox, gy, oz);
                }
        }
        return null;
    }

    /** 推进一步施工。返回 true = 全部完成。 */
    static boolean buildTick(BotService svc, BotAgent a) {
        Player bot = a.player;
        java.util.List<HutBlueprint.Cell> cells = HutBlueprint.cells();
        long now = System.currentTimeMillis();
        if (now < a.nextActAt) return false;
        a.nextActAt = now + 650;
        World w = a.buildOrigin.getWorld();
        while (a.buildIndex < cells.size()) {
            HutBlueprint.Cell c = cells.get(a.buildIndex);
            Block b = w.getBlockAt(a.buildOrigin.getBlockX() + c.dx(),
                    a.buildOrigin.getBlockY() + c.dy(), a.buildOrigin.getBlockZ() + c.dz());
            if (alreadyBuilt(b, c.part())) { a.buildIndex++; continue; }
            Material use = consumeFor(b, c.part(), bot);
            if (use == null) { svc.say(a, "料不够了，房子先盖到这儿。"); return true; } // 停工当完成处理（大脑会收尾）
            placeCell(b, c.part(), use);
            a.buildIndex++;
            return false;
        }
        return true;
    }

    private static boolean alreadyBuilt(Block b, HutBlueprint.Part part) {
        return switch (part) {
            case PLANK -> b.getType().name().endsWith("_PLANKS");
            case DOOR -> b.getType().name().endsWith("_DOOR");
            case BED_FOOT, BED_HEAD -> b.getType().name().endsWith("_BED");
            case CHEST -> b.getType() == Material.CHEST;
        };
    }

    /** 按图纸部件从背包扣一份料，返回要放的方块材料。 */
    private static Material consumeFor(Block b, HutBlueprint.Part part, Player bot) {
        PlayerInventory inv = bot.getInventory();
        switch (part) {
            case PLANK -> {
                // 用他存量最多的那种木板
                Material best = null; int bestN = 0;
                Map<Material, Integer> counts = new HashMap<>();
                for (ItemStack it : inv.getContents())
                    if (it != null && it.getType().name().endsWith("_PLANKS"))
                        counts.merge(it.getType(), it.getAmount(), Integer::sum);
                for (Map.Entry<Material, Integer> e : counts.entrySet())
                    if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
                if (best == null) return null;
                takeItem(bot, best, 1);
                return best;
            }
            case DOOR -> {
                for (ItemStack it : inv.getContents())
                    if (it != null && it.getType().name().endsWith("_DOOR")) {
                        Material m = it.getType(); takeItem(bot, m, 1); return m;
                    }
                return null;
            }
            case BED_FOOT, BED_HEAD -> {
                // 床两格共用一份床物品：只在 FOOT 时扣
                if (part == HutBlueprint.Part.BED_HEAD) {
                    for (ItemStack it : inv.getContents())
                        if (it != null && it.getType().name().endsWith("_BED")) return it.getType();
                    return Material.RED_BED;
                }
                for (ItemStack it : inv.getContents())
                    if (it != null && it.getType().name().endsWith("_BED")) {
                        Material m = it.getType(); takeItem(bot, m, 1); return m;
                    }
                return null;
            }
            case CHEST -> {
                if (countItem(bot, Material.CHEST) <= 0) return null;
                takeItem(bot, Material.CHEST, 1);
                return Material.CHEST;
            }
        }
        return null;
    }

    private static void placeCell(Block b, HutBlueprint.Part part, Material use) {
        switch (part) {
            case PLANK, CHEST -> b.setType(use);
            case DOOR -> {
                BlockData lower = Bukkit.createBlockData(use, "[half=lower,facing=south]");
                b.setBlockData(lower);
                Block above = b.getRelative(0, 1, 0);
                BlockData upper = Bukkit.createBlockData(use, "[half=upper,facing=south]");
                above.setBlockData(upper);
            }
            case BED_FOOT -> {
                BlockData foot = Bukkit.createBlockData(use, "[part=foot,facing=east]");
                b.setBlockData(foot);
            }
            case BED_HEAD -> {
                BlockData head = Bukkit.createBlockData(use, "[part=head,facing=east]");
                b.setBlockData(head);
            }
        }
    }
}
