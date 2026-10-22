package com.howard.mcai.nav;

import com.howard.mcai.McaiPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 寻路执行器（v0.5.2 狗式驾驶）：路线归插件 A* 定，腿用原版导航，命令强势下达。
 * <p>
 * 两版教训钉死在这里：
 * <ul>
 *   <li>v0.5.0（AI 开 + 客气下令）：原版大脑的日程（上班/回家/慌跑）与插件共用
 *       同一套寻路，谁的指令在跑就听谁的——驾驶员只在“没现成路线”时才下令，
 *       大半时间在执行大脑的路线，跟随走一半就抽风。</li>
 *   <li>v0.5.1（AI 关 + 速度直驱）：关了大脑的村民，横向速度每帧会被原版移动
 *       结算吃掉（竖直速度能生效，所以跳跃手势正常）——根本走不动，只剩瞬移。</li>
 * </ul>
 * 驯服的狗为什么不打架：它的跟随是目标系统里的高优先指令，一直在下达。
 * 村民没有目标系统，就学个形似：驾驶期间每 2 tick 把当前路点**重新下达**
 * 给原版导航（谁最后下令听谁的，那就每次都我们最后下），大脑的指令抢不到空档；
 * 跟随任务还会把大脑的“老家/岗位/集合点”记忆临时钉在他脚下（见跟随工具），
 * 让它连想去的地方都没有。梯子原版导航爬不了，人在梯子上时竖直速度托一把。
 */
public final class NavDriver {

    public enum Status { RUNNING, ARRIVED, STUCK }

    private static final double WAYPOINT_CAPTURE = 0.9;
    private static final long STUCK_MS = 2_500L;
    private static final double PROGRESS_MIN_MOVE = 0.25;

    private final McaiPlugin plugin;
    private final List<Location> waypoints;
    private final List<int[]> doorCells;
    private final boolean reached;

    private Villager villager;
    private double speedMultiplier = 0.9;
    private int index = 0;
    private volatile Status status = Status.RUNNING;
    private BukkitTask loop;

    private long lastProgressMs = System.currentTimeMillis();
    private double sampleX, sampleZ;
    private boolean sampleInit = false;
    private long lastDoorTryMs = 0L;

    public NavDriver(McaiPlugin plugin, NavService.Plan plan) {
        this.plugin = plugin;
        this.waypoints = plan.waypoints();
        this.doorCells = plan.doorCells();
        this.reached = plan.reached();
    }

    /** 计划是否真能到终点（false = 半路路径，走完要重规划） */
    public boolean reachedGoal() {
        return reached;
    }

    public boolean finished() {
        return index >= waypoints.size();
    }

    public Status status() {
        return status;
    }

    public boolean driving() {
        return loop != null;
    }

    public void setSpeed(double multiplier) {
        this.speedMultiplier = multiplier;
    }

    /** 开始/恢复驾驶（幂等）：挂上 2 tick 一跳的下令循环 */
    public void start(Villager villager, double speedMultiplier) {
        this.villager = villager;
        this.speedMultiplier = speedMultiplier;
        if (loop != null || status != Status.RUNNING) return;
        sampleInit = false;
        lastProgressMs = System.currentTimeMillis();
        loop = plugin.getServer().getScheduler().runTaskTimer(plugin, this::stepGuarded, 1L, 2L);
    }

    /** 暂停驾驶（对话中/已到位等待）：停循环并清原版路径，人留在原地，状态可恢复 */
    public void pause() {
        stopLoop();
        haltPath();
    }

    /** 彻底停驶（任务收尾/换计划）：停循环清路径，之后应丢弃本驾驶员 */
    public void stop() {
        stopLoop();
        haltPath();
    }

    private void stopLoop() {
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    private void haltPath() {
        Villager v = villager;
        if (v != null && v.isValid()) {
            v.getPathfinder().stopPathfinding();
        }
    }

    private void stepGuarded() {
        try {
            step();
        } catch (Throwable t) {
            // 驾驶循环绝不能把异常抛进调度器：记一笔、按卡住收尾，交给任务层重规划/放弃
            plugin.getLogger().warning("[寻路] 驾驶循环异常，转卡住处理: " + t);
            status = Status.STUCK;
            stopLoop();
            haltPath();
        }
    }

    private void step() {
        Villager v = villager;
        if (v == null || !v.isValid() || v.isDead()) {
            status = Status.STUCK;
            stopLoop();
            return;
        }
        Location me = v.getLocation();
        long now = System.currentTimeMillis();

        // 跳过已到的路点（起步时可能连跳几个近点）
        while (index < waypoints.size()) {
            Location wp = waypoints.get(index);
            double dx = wp.getX() - me.getX();
            double dz = wp.getZ() - me.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= WAYPOINT_CAPTURE
                    && Math.abs(wp.getY() - me.getY()) <= 1.7) {
                index++;
                lastProgressMs = now;
                sampleInit = false;
            } else {
                break;
            }
        }
        if (finished()) {
            status = Status.ARRIVED;
            stopLoop();
            haltPath();
            return;
        }

        // 进展监测：真挪动了就刷新；2.5 秒没挪够 → 卡住（停驶，任务层重规划）
        if (!sampleInit) {
            sampleX = me.getX();
            sampleZ = me.getZ();
            sampleInit = true;
        } else {
            double mdx = me.getX() - sampleX;
            double mdz = me.getZ() - sampleZ;
            if (Math.sqrt(mdx * mdx + mdz * mdz) >= PROGRESS_MIN_MOVE) {
                sampleX = me.getX();
                sampleZ = me.getZ();
                lastProgressMs = now;
            } else if (now - lastProgressMs >= STUCK_MS) {
                status = Status.STUCK;
                stopLoop();
                haltPath();
                return;
            }
        }

        // 开门：前方 2.4 格内的门格提前开（上下两半都试）
        if (now - lastDoorTryMs >= 400L) {
            lastDoorTryMs = now;
            World w = v.getWorld();
            for (int[] d : doorCells) {
                double dx = d[0] + 0.5 - me.getX();
                double dz = d[2] + 0.5 - me.getZ();
                if (dx * dx + dz * dz <= 2.4 * 2.4 && Math.abs(d[1] - me.getBlockY()) <= 2) {
                    NavService.openDoorAt(w, d[0], d[1], d[2]);
                    NavService.openDoorAt(w, d[0], d[1] + 1, d[2]);
                }
            }
        }

        Location wp = waypoints.get(index);

        // 梯子段：原版导航不会爬梯子——人在梯子/藤蔓上且路点在上方/下方时，
        // 竖直速度托一把（竖直速度在原版移动结算里能生效，跳跃手势就是这么动的）
        Block feet = me.getBlock();
        if (NavService.isClimb(feet.getType())) {
            double dy = wp.getY() - me.getY();
            if (dy > 0.6 || dy < -0.6) {
                Vector vel = v.getVelocity();
                v.setVelocity(new Vector(vel.getX(), dy > 0 ? 0.38 : -0.30, vel.getZ()));
            }
        }

        // 强势下令：每一步都把当前路点重新下达给原版导航——不给大脑的指令留空档
        v.getPathfinder().moveTo(wp, speedMultiplier);
    }

    /** 兼容旧调用：粗暴停车（清原版路径） */
    public static void halt(Villager villager) {
        if (villager == null || !villager.isValid()) return;
        villager.getPathfinder().stopPathfinding();
    }
}
