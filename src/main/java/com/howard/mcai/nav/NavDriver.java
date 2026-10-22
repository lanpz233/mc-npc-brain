package com.howard.mcai.nav;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;

import java.util.List;

/**
 * 寻路执行器：拿着 {@link NavService.Plan} 的路点，一段一段喂给原版导航走。
 * <p>
 * 分工：全局路线（绕哪面墙、过哪扇门、上哪段梯子）由自研 A* 定死；每两个路点
 * 之间的几步路交给原版导航的腿（自动上台阶、跳一格、避让实体，不抽搐不飞）。
 * 路过门格时提前伸手开门。卡住判定看“实际位移”：2.5 秒挪不动 0.3 格 → STUCK，
 * 交给任务层重规划（而不是在这里无限重发指令烧 CPU）。
 */
public final class NavDriver {

    public enum Status { RUNNING, ARRIVED, STUCK }

    private final List<Location> waypoints;
    private final List<int[]> doorCells;
    private final boolean reached;

    private int index = 0;
    private long lastProgressMs = System.currentTimeMillis();
    private double lastX, lastZ;
    private boolean progressInit = false;
    private long lastDoorTryMs = 0L;

    private static final double WAYPOINT_ARRIVE = 1.0;
    private static final long STUCK_MS = 2_500L;
    private static final double STUCK_MIN_MOVE = 0.3;

    public NavDriver(NavService.Plan plan) {
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

    /** 当前路点（调试/日志用） */
    public Location currentWaypoint() {
        return finished() ? null : waypoints.get(index);
    }

    public Status tick(Villager villager, double speed) {
        if (finished()) return Status.ARRIVED;
        Location me = villager.getLocation();
        long now = System.currentTimeMillis();

        // 路点到达：逐个跳过已到的（起步时可能一连跳过好几个近点）
        while (index < waypoints.size()) {
            Location wp = waypoints.get(index);
            double dx = wp.getX() - me.getX();
            double dz = wp.getZ() - me.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= WAYPOINT_ARRIVE
                    && Math.abs(wp.getY() - me.getY()) <= 1.6) {
                index++;
                lastProgressMs = now;
                progressInit = false;
            } else {
                break;
            }
        }
        if (finished()) return Status.ARRIVED;

        // 开门：前方 2.4 格内的门格，提前开（上下两半都试）
        if (now - lastDoorTryMs >= 400L) {
            lastDoorTryMs = now;
            World w = villager.getWorld();
            for (int[] d : doorCells) {
                double dx = d[0] + 0.5 - me.getX();
                double dz = d[2] + 0.5 - me.getZ();
                if (dx * dx + dz * dz <= 2.4 * 2.4 && Math.abs(d[1] - me.getBlockY()) <= 2) {
                    NavService.openDoorAt(w, d[0], d[1], d[2]);
                    NavService.openDoorAt(w, d[0], d[1] + 1, d[2]);
                }
            }
        }

        // 梯子段：原版导航不会爬梯子 —— 人在梯子/藤蔓上时用速度托着爬/滑/蹭出梯子口，
        // 否则 A* 认了梯子路、腿却过不去，会在梯子脚下无限卡住重规划
        boolean climbing = false;
        if (NavService.isClimb(villager.getLocation().getBlock().getType())) {
            Location wp = waypoints.get(index);
            double dy = wp.getY() - me.getY();
            double dx = wp.getX() - me.getX();
            double dz = wp.getZ() - me.getZ();
            double hd = Math.sqrt(dx * dx + dz * dz);
            double vx = hd > 0.05 ? dx / hd * 0.12 : 0.0;
            double vz = hd > 0.05 ? dz / hd * 0.12 : 0.0;
            if (dy > 0.6) {
                villager.setVelocity(new org.bukkit.util.Vector(vx, 0.38, vz));
                climbing = true;
            } else if (dy < -0.6) {
                villager.setVelocity(new org.bukkit.util.Vector(vx, -0.30, vz));
                climbing = true;
            } else if (hd > 0.4) {
                // 同层蹭出梯子口：略带上托，防被梯子口的沿卡住
                villager.setVelocity(new org.bukkit.util.Vector(
                        dx / Math.max(hd, 0.001) * 0.2, 0.12, dz / Math.max(hd, 0.001) * 0.2));
                climbing = true;
            }
        }

        // 喂路点：没路径时下发一次（原版导航自己走完这一小段）
        if (!climbing) {
            Pathfinder pf = villager.getPathfinder();
            if (!pf.hasPath()) {
                pf.moveTo(waypoints.get(index), speed);
            }
        }

        // 进展监测：挪动过就刷新基准；超时没挪动 = 卡住
        if (!progressInit) {
            lastX = me.getX();
            lastZ = me.getZ();
            lastProgressMs = now;
            progressInit = true;
        } else {
            double dx = me.getX() - lastX;
            double dz = me.getZ() - lastZ;
            if (Math.sqrt(dx * dx + dz * dz) >= STUCK_MIN_MOVE) {
                lastX = me.getX();
                lastZ = me.getZ();
                lastProgressMs = now;
            } else if (now - lastProgressMs >= STUCK_MS) {
                return Status.STUCK;
            }
        }
        return Status.RUNNING;
    }

    /** 停下（任务结束/被打断时）：清原版路径，防任务完了人还在沿旧路走 */
    public static void halt(Villager villager) {
        if (villager == null || !villager.isValid()) return;
        villager.getPathfinder().stopPathfinding();
    }
}
