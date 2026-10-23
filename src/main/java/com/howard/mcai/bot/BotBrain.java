package com.howard.mcai.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人的大脑（每秒推进一步）：生存 > 命令/工作 > 作息 > 社交。
 * <p>
 * 与村民同规矩：插件定一切行为，模型只负责说话。这里没有一个决定是模型做的。
 */
public final class BotBrain {

    private BotBrain() {}

    private static final Map<Material, Integer> FOOD = Map.ofEntries(
            Map.entry(Material.BREAD, 5), Map.entry(Material.COOKED_BEEF, 8),
            Map.entry(Material.COOKED_PORKCHOP, 8), Map.entry(Material.COOKED_CHICKEN, 6),
            Map.entry(Material.COOKED_MUTTON, 6), Map.entry(Material.COOKED_COD, 5),
            Map.entry(Material.COOKED_SALMON, 6), Map.entry(Material.BAKED_POTATO, 5),
            Map.entry(Material.CARROT, 3), Map.entry(Material.APPLE, 4),
            Map.entry(Material.POTATO, 1), Map.entry(Material.MELON_SLICE, 2),
            Map.entry(Material.SWEET_BERRIES, 2), Map.entry(Material.DRIED_KELP, 1),
            Map.entry(Material.PUMPKIN_PIE, 8), Map.entry(Material.COOKIE, 2),
            Map.entry(Material.GOLDEN_CARROT, 6), Map.entry(Material.BEETROOT, 1),
            Map.entry(Material.COOKED_RABBIT, 5), Map.entry(Material.MUSHROOM_STEW, 6),
            Map.entry(Material.BEETROOT_SOUP, 6), Map.entry(Material.RABBIT_STEW, 10));

    public static void tick(BotService svc, BotAgent a) {
        if (!a.alive()) return;
        Player bot = a.player;
        BotState s = a.state;
        long now = System.currentTimeMillis();
        s.fear = Math.max(0, s.fear - 1.5);
        s.lastSeenMillis = now;

        // ===== 生存：危险就跑 =====
        Entity threat = nearestHostile(bot, 9);
        if (bot.getHealth() <= 8 && threat != null && a.mode != BotAgent.Mode.FLEE) {
            a.haltAll(svc);
            a.mode = BotAgent.Mode.FLEE;
            Location away = fleeTarget(svc, a, threat.getLocation());
            a.handle = a.driver.goTo(bot, away, 2.0);
            if (now - a.lastShoutAt > 30_000) { a.lastShoutAt = now; svc.say(a, pickShout(s)); }
            return;
        }
        if (a.mode == BotAgent.Mode.FLEE) {
            if (threat == null || (a.handle != null && a.handle.done())) {
                a.mode = BotAgent.Mode.IDLE; a.handle = null;
                eatIfHungry(svc, a, true);
            }
            return;
        }

        // ===== 生存：饿了就吃（自己的真背包） =====
        eatIfHungry(svc, a, false);
        if (bot.getFoodLevel() <= 4 && now - a.lastShoutAt > 300_000) {
            a.lastShoutAt = now;
            svc.say(a, "我饿得不行了，身上又没吃的……");
            s.addFact("饿过肚子，没东西吃");
        }

        // ===== 夜里作息（守夜岗除外） =====
        long time = bot.getWorld().getTime();
        boolean night = time >= 13000 && time <= 22500;
        if (night && a.mode == BotAgent.Mode.IDLE) {
            if (s.hasBase() && bot.getWorld().getName().equals(s.baseWorld)
                    && dist2d(bot.getLocation(), s.baseX, s.baseZ) > 20) {
                a.mode = BotAgent.Mode.RETURN_BASE; a.phase = 0;
                a.handle = a.driver.goTo(bot, baseLoc(svc, s), 1.5);
                return;
            }
            a.mode = BotAgent.Mode.REST;
            return;
        }
        if (!night && a.mode == BotAgent.Mode.REST) { a.mode = BotAgent.Mode.IDLE; return; }
        if (night && a.mode == BotAgent.Mode.WORK && a.job != BotAgent.Job.GUARD) {
            svc.say(a, "天黑了，我先回去歇着。");
            a.haltAll(svc);
            return; // 下一 tick 走 IDLE 的夜里分支
        }

        // ===== 背包满：自动回据点存货 =====
        if ((a.mode == BotAgent.Mode.WORK || a.mode == BotAgent.Mode.IDLE)
                && BotJobs.inventoryFull(bot)) {
            if (s.hasChest) {
                svc.say(a, "背包满了，我回据点存一下。");
                a.haltAll(svc);
                a.mode = BotAgent.Mode.RETURN_BASE; a.phase = 1; // 到了存货
                a.handle = a.driver.goTo(bot, chestStand(svc, s), 1.6);
                return;
            } else if (!a.fullWarned) {
                a.fullWarned = true;
                svc.say(a, "我背包满了，又没箱子可放……");
            }
        }
        if (!BotJobs.inventoryFull(bot)) a.fullWarned = false;

        // ===== 模式分发 =====
        switch (a.mode) {
            case IDLE -> {
                // 身边有怪打过来就还手（站桩自卫）
                if (threat != null && bot.getHealth() > 10)
                    BotJobs.combatTick(svc, a, bot.getLocation(), 8);
                else idleSocial(svc, a);
            }
            case REST -> { /* 站着歇，偶尔看看四周 */
                if (threat != null && bot.getHealth() > 10)
                    BotJobs.combatTick(svc, a, bot.getLocation(), 8);
            }
            case FOLLOW -> followTick(svc, a);
            case GOTO -> {
                if (a.handle == null || a.handle.done()) {
                    a.mode = BotAgent.Mode.IDLE; a.handle = null;
                }
            }
            case RETURN_BASE -> {
                if (a.handle == null || a.handle.done()) {
                    boolean wantDeposit = a.phase == 1;
                    a.mode = BotAgent.Mode.IDLE; a.handle = null;
                    if (wantDeposit && s.hasChest) {
                        int moved = BotJobs.doDeposit(svc, a);
                        svc.say(a, moved > 0 ? "存好了，一共放进去 " + moved + " 件。" : "没什么可存的。");
                        svc.storeDirty();
                    }
                }
            }
            case DEPOSIT -> {
                int moved = BotJobs.doDeposit(svc, a);
                svc.say(a, moved > 0 ? "都存好了，一共 " + moved + " 件。" : "没什么可存的。");
                a.mode = BotAgent.Mode.IDLE;
                svc.storeDirty();
            }
            case WORK -> workTick(svc, a);
            case BUILD -> {
                if (BotJobs.buildTick(svc, a)) finishBuild(svc, a);
            }
            case FLEE -> { /* 上面已处理 */ }
        }
    }

    private static void workTick(BotService svc, BotAgent a) {
        switch (a.job) {
            case FISH -> BotJobs.fishTick(svc, a);
            case CHOP -> BotJobs.gatherTick(svc, a, true);
            case BREAK -> BotJobs.gatherTick(svc, a, false);
            case MINE -> BotJobs.mineTick(svc, a);
            case GUARD -> BotJobs.combatTick(svc, a, a.target, 14);
            case NONE -> a.mode = BotAgent.Mode.IDLE;
        }
    }

    private static void followTick(BotService svc, BotAgent a) {
        Player bot = a.player;
        Player leader = a.followUuid == null ? null
                : svc.plugin().getServer().getPlayer(a.followUuid);
        if (leader == null || !leader.isOnline() || leader.isDead()
                || !leader.getWorld().equals(bot.getWorld())) {
            svc.say(a, "人呢？那我先在这儿等着。");
            a.haltAll(svc);
            return;
        }
        Location ll = leader.getLocation();
        double d = ll.distance(bot.getLocation());
        if (d > 40) { // 跟丢太远：直接现身（与村民跟随同一规矩的放宽版）
            Location behind = ll.clone().add(ll.getDirection().setY(0).normalize().multiply(-2.5));
            behind.setY(bot.getWorld().getHighestBlockYAt(behind) + 1);
            bot.teleport(behind);
            return;
        }
        if (d > 3.2 && !a.driver.driving())
            a.handle = a.driver.goTo(bot, ll, 2.6);
    }

    private static void finishBuild(BotService svc, BotAgent a) {
        BotState s = a.state;
        Location o = a.buildOrigin;
        // 据点搬进新家：站位在屋中央，箱子就是图纸里那个
        s.setBase(o.getWorld().getName(), o.getBlockX() + 2.5, o.getBlockY() + 1, o.getBlockZ() + 2.5);
        s.setChest(o.getWorld().getName(), o.getBlockX() + 1, o.getBlockY() + 1, o.getBlockZ() + 2);
        s.addFact("自己盖了一间小屋，安了家");
        a.mode = BotAgent.Mode.IDLE;
        a.buildOrigin = null; a.buildIndex = 0;
        svc.say(a, "房子盖好啦！以后这儿就是我家了。");
        svc.storeDirty();
    }

    // ============ 生存零件 ============

    static Entity nearestHostile(Player bot, double radius) {
        Entity best = null; double bd = radius * radius;
        for (Entity e : bot.getWorld().getNearbyEntities(bot.getLocation(), radius, radius, radius)) {
            if (e instanceof Monster && !e.isDead()) {
                double d = e.getLocation().distanceSquared(bot.getLocation());
                if (d < bd) { bd = d; best = e; }
            }
        }
        return best;
    }

    private static Location fleeTarget(BotService svc, BotAgent a, Location from) {
        Player bot = a.player;
        BotState s = a.state;
        if (s.hasBase() && bot.getWorld().getName().equals(s.baseWorld))
            return baseLoc(svc, s);
        Location cur = bot.getLocation();
        double dx = cur.getX() - from.getX(), dz = cur.getZ() - from.getZ();
        double len = Math.max(0.001, Math.hypot(dx, dz));
        Location t = cur.clone().add(dx / len * 14, 0, dz / len * 14);
        t.setY(bot.getWorld().getHighestBlockYAt(t) + 1);
        return t;
    }

    /** 饿了吃自己背包里最好的食物（真扣物品、真回饥饿）。force=逃命后也要吃。 */
    static void eatIfHungry(BotService svc, BotAgent a, boolean force) {
        Player bot = a.player;
        if (bot.getFoodLevel() > 13 && !force) return;
        if (force && bot.getFoodLevel() >= 18) return;
        Material best = null; int bestVal = 0;
        for (ItemStack it : bot.getInventory().getContents()) {
            if (it == null) continue;
            Integer v = FOOD.get(it.getType());
            if (v != null && v > bestVal) { bestVal = v; best = it.getType(); }
        }
        if (best == null) return;
        BotJobs.takeItem(bot, best, 1);
        bot.setFoodLevel(Math.min(20, bot.getFoodLevel() + bestVal));
        bot.setSaturation(Math.min(bot.getFoodLevel(), bot.getSaturation() + bestVal * 0.6f));
    }

    public static Location baseLoc(BotService svc, BotState s) {
        org.bukkit.World w = svc.plugin().getServer().getWorld(s.baseWorld);
        return w == null ? null : new Location(w, s.baseX, s.baseY, s.baseZ);
    }

    static Location chestStand(BotService svc, BotState s) {
        org.bukkit.World w = svc.plugin().getServer().getWorld(s.chestWorld);
        if (w == null) return baseLoc(svc, s);
        // 站到箱子南边一格（够得着开箱）
        return new Location(w, s.chestX + 0.5, s.chestY, s.chestZ + 1.5);
    }

    private static double dist2d(Location l, double x, double z) {
        return Math.hypot(l.getX() - x, l.getZ() - z);
    }

    private static String pickShout(BotState s) {
        String[] pool = {"救命！", "别打了！我要不行了！", "快跑啊！", "疼疼疼！救命！"};
        return pool[ThreadLocalRandom.current().nextInt(pool.length)];
    }

    private static final String[] GREETS = {
            "哟，你来啦。", "今天天气不错，是吧？", "我正歇着呢，有事说话。",
            "来啦？坐会儿不。", "你忙你的，我就在这儿。", "嗨，又见面了。"
    };

    private static void idleSocial(BotService svc, BotAgent a) {
        Player bot = a.player;
        long now = System.currentTimeMillis();
        if (now - a.lastGreetAt < 300_000) return;
        for (Player p : bot.getWorld().getPlayers()) {
            if (p == bot || p.getClass().getName().contains("CraftBot")) continue;
            if (p.getLocation().distanceSquared(bot.getLocation()) <= 20) {
                if (ThreadLocalRandom.current().nextDouble() < 0.35) {
                    BotJobs.lookAt(svc, a, p.getEyeLocation());
                    svc.sayTo(a, p, GREETS[ThreadLocalRandom.current().nextInt(GREETS.length)]);
                    a.lastGreetAt = now;
                }
                return;
            }
        }
    }
}
