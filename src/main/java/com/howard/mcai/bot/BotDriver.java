package com.howard.mcai.bot;

import com.howard.mcai.nav.NavService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 假人的"腿"：速度方向盘（探针实测可行：每 2 tick 给方向速度，7 格不到 1 秒）。
 * <p>
 * 玩家身体没有原版导航，所以路线分两层：40 格内借 NavService 的格子路（绕墙过门），
 * 更远就直奔目标、每 10 秒朝目标重算一段。卡住先跳、再原地等、最后才用"没人看见时
 * 小步瞬移补位"兜底（与村民跟随的跟丢规矩同一条体面线：真玩家 24 格内看着就不许瞬移）。
 * 大脑（BotBrain）只下 goTo 命令、轮询 Handle 结果，不碰速度细节。
 */
public final class BotDriver {

    public static final class Handle {
        private boolean done, failed;
        private String failReason = "";
        public boolean done() { return done; }
        public boolean failed() { return failed; }
        public String failReason() { return failReason; }
        void finish(boolean ok, String reason) { done = true; failed = !ok; failReason = reason; }
    }

    private static final double SPEED = 0.24;          // 每 tick 水平速度（约 4.8 格/秒，接近步行）
    private static final double PLAN_RANGE = 40.0;      // 这个距离内用 NavService 算路
    private static final long MAX_DRIVE_MS = 10 * 60_000L; // 单次行军最长 10 分钟（无限范围靠分段续）

    private final NavService nav;
    private Handle handle;
    private Location target;
    private double arriveDist = 1.6;
    private final Deque<Location> waypoints = new ArrayDeque<>();
    private long startedAt;
    private long lastReplanAt;
    private Location lastPos;
    private long lastProgressAt;
    private int nudges;
    private boolean jumpCooldown;

    public BotDriver(NavService nav) { this.nav = nav; }

    public boolean driving() { return handle != null && !handle.done(); }

    public Handle goTo(Player bot, Location to, double arrive) {
        handle = new Handle();
        target = to.clone();
        arriveDist = arrive;
        waypoints.clear();
        startedAt = System.currentTimeMillis();
        lastReplanAt = 0;
        lastPos = bot.getLocation();
        lastProgressAt = startedAt;
        nudges = 0;
        return handle;
    }

    public void stop(Player bot) {
        if (handle != null && !handle.done()) handle.finish(false, "被叫停");
        waypoints.clear();
        if (bot != null && bot.isValid()) {
            Vector v = bot.getVelocity();
            bot.setVelocity(new Vector(0, v.getY(), 0));
        }
    }

    /** 快 tick（每 2 tick 由 BotService 调）：推一把速度。 */
    public void tickFast(Player bot) {
        if (!driving() || bot == null || !bot.isValid()) return;
        Location cur = bot.getLocation();
        long now = System.currentTimeMillis();
        if (now - startedAt > MAX_DRIVE_MS) { handle.finish(false, "走了太久还没到"); halt(bot); return; }

        // 到达判定（看水平距离 + 高度差不太离谱）
        double dxT = target.getX() - cur.getX(), dzT = target.getZ() - cur.getZ();
        double distT = Math.hypot(dxT, dzT);
        if (distT <= arriveDist && Math.abs(target.getY() - cur.getY()) <= 2.5) {
            handle.finish(true, "");
            halt(bot);
            return;
        }

        // 路点维护：近处用 NavService 的路，远处直奔（每 10 秒或路点走完重算）
        Location aim = waypoints.peek();
        if (aim == null || now - lastReplanAt > 10_000) {
            replan(bot, cur);
            aim = waypoints.peek();
            lastReplanAt = now;
        }
        if (aim == null) aim = target;
        if (cur.distanceSquared(aim) < 1.2 * 1.2 && waypoints.size() > 1) {
            waypoints.poll();
            aim = waypoints.peek() == null ? target : waypoints.peek();
        }

        // 进度监测（卡住判定）
        if (lastPos == null || cur.distanceSquared(lastPos) > 0.6 * 0.6) {
            lastPos = cur.clone();
            lastProgressAt = now;
        }
        long stalledMs = now - lastProgressAt;
        if (stalledMs > 4_000) {
            // 兜底补位：真玩家 24 格内没人看着才许
            if (!realPlayerNear(bot, 24)) {
                Location nudge = cur.clone().add(norm(dxT, dzT, 2.0));
                nudge.setY(groundY(bot, nudge));
                bot.teleport(nudge);
                lastPos = nudge; lastProgressAt = now;
                if (++nudges >= 4) { handle.finish(false, "卡住且补位多次无效"); halt(bot); }
                return;
            }
            if (stalledMs > 12_000) { handle.finish(false, "卡住了"); halt(bot); return; }
        }

        // 转向 + 给速度
        double dx = aim.getX() - cur.getX(), dz = aim.getZ() - cur.getZ();
        double len = Math.hypot(dx, dz);
        if (len > 0.01) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bot.setRotation(yaw, bot.getLocation().getPitch());
        }
        Vector v = new Vector(dx / Math.max(len, 0.001) * SPEED, bot.getVelocity().getY(), dz / Math.max(len, 0.001) * SPEED);
        // 跳：前方脚下有方块、上方空、目标不比脚低 → 起跳越障（1 格台阶）
        if (bot.isOnGround() && !jumpCooldown && blockedAhead(bot)) {
            v.setY(0.42);
            jumpCooldown = true;
        } else if (bot.isOnGround()) {
            jumpCooldown = false;
        }
        // 水里：往上浮
        if (bot.getLocation().getBlock().getType() == Material.WATER) v.setY(0.18);
        bot.setVelocity(v);
    }

    private void replan(Player bot, Location cur) {
        waypoints.clear();
        double d = cur.distance(target);
        if (d <= PLAN_RANGE && nav != null) {
            try {
                NavService.Plan plan = nav.plan(bot.getUniqueId(), cur, target);
                if (plan != null && plan.waypoints() != null && !plan.waypoints().isEmpty()) {
                    List<Location> wps = plan.waypoints();
                    // 路点太多时抽稀，保留走势即可（方向盘会自己贴合）
                    for (int i = 0; i < wps.size(); i += 2) waypoints.add(wps.get(i));
                    waypoints.add(wps.get(wps.size() - 1));
                    return;
                }
            } catch (Throwable ignored) { /* 算路失败就直奔 */ }
        }
        // 远距离/无路：朝目标取 24 格处的中继点
        if (d > 24) {
            Vector dir = target.clone().subtract(cur).toVector().setY(0).normalize().multiply(24);
            waypoints.add(cur.clone().add(dir));
        } else {
            waypoints.add(target.clone());
        }
    }

    private boolean blockedAhead(Player bot) {
        Location cur = bot.getLocation();
        Vector dir = cur.getDirection().setY(0).normalize();
        int bx = (int) Math.floor(cur.getX() + dir.getX() * 0.8);
        int bz = (int) Math.floor(cur.getZ() + dir.getZ() * 0.8);
        int by = (int) Math.floor(cur.getY());
        Material feet = bot.getWorld().getBlockAt(bx, by, bz).getType();
        Material above = bot.getWorld().getBlockAt(bx, by + 2, bz).getType();
        // 脚前那格是实心、头顶两格外有空 → 一格台阶，跳得上去
        return feet.isSolid() && !above.isSolid();
    }

    private boolean realPlayerNear(Player bot, double radius) {
        for (Player p : bot.getWorld().getPlayers()) {
            if (p == bot) continue;
            if (p.getClass().getName().contains("CraftBot")) continue;
            if (p.getLocation().distanceSquared(bot.getLocation()) <= radius * radius) return true;
        }
        return false;
    }

    private double groundY(Player bot, Location at) {
        int y = bot.getWorld().getHighestBlockYAt(at);
        return y + 1.0;
    }

    private Vector norm(double dx, double dz, double len) {
        double l = Math.hypot(dx, dz);
        if (l < 0.001) return new Vector(0, 0, 0);
        return new Vector(dx / l * len, 0, dz / l * len);
    }

    private void halt(Player bot) {
        Vector v = bot.getVelocity();
        bot.setVelocity(new Vector(0, v.getY(), 0));
        waypoints.clear();
    }
}
