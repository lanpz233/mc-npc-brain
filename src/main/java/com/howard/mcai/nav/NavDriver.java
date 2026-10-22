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
 * 寻路执行器（v0.5.1 全自研驾驶）：拿着 {@link NavService.Plan} 的路点，自己开身体走。
 * <p>
 * 为什么不用原版导航的腿（v0.5.0 的教训）：村民的原版大脑一直在跑自己的日程
 * （上班去工作站、傍晚去床/钟、见怪慌跑），它和插件共用同一套寻路——插件塞一条
 * “去玩家那”，大脑回塞一条“回岗位”，两条指令抢一个身体，跟随走到一半就开始
 * 两步这边、两步那边地抽风。解法是驾驶期间把 AI 关掉（setAI(false) 只停大脑
 * 与原版寻路，物理/碰撞/受伤照常），由本类每 2 tick 直接给速度：朝向、步速、
 * 抵近减速、跳台阶、涉水、爬梯子全自己管，大脑插不进手。
 * <p>
 * 驾驶期间若外部代码把 AI 打开（对话结束会 setAI(true)），循环里发现就压回去。
 * 任务层（1 秒一跳）只读 {@link #status()} 决定完成/重规划/判失败，不参与驾驶。
 */
public final class NavDriver {

    public enum Status { RUNNING, ARRIVED, STUCK }

    /** 步速基准：倍率 1.0 ≈ 原版正常步行（约 4.4 格/秒） */
    private static final double BASE_SPEED_PER_TICK = 0.22;
    private static final double WAYPOINT_CAPTURE = 0.75;
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
    private double prevX, prevZ;
    private int blockedRuns = 0;
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

    /** 开始/恢复驾驶（幂等）：挂上 2 tick 一跳的转向循环 */
    public void start(Villager villager, double speedMultiplier) {
        this.villager = villager;
        this.speedMultiplier = speedMultiplier;
        if (loop != null || status != Status.RUNNING) return;
        sampleInit = false;
        blockedRuns = 0;
        lastProgressMs = System.currentTimeMillis();
        loop = plugin.getServer().getScheduler().runTaskTimer(plugin, this::stepGuarded, 1L, 2L);
    }

    /** 暂停驾驶（对话中/已到位等待）：停循环并刹住水平速度，状态保持 RUNNING 可恢复 */
    public void pause() {
        stopLoop();
        zeroHorizontal();
    }

    /** 彻底停驶（任务收尾/换计划）：停循环并刹住，之后应丢弃本驾驶员 */
    public void stop() {
        stopLoop();
        zeroHorizontal();
    }

    private void stopLoop() {
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    private void zeroHorizontal() {
        Villager v = villager;
        if (v == null || !v.isValid()) return;
        Vector vel = v.getVelocity();
        vel.setX(0);
        vel.setZ(0);
        v.setVelocity(vel);
    }

    private void stepGuarded() {
        try {
            step();
        } catch (Throwable t) {
            // 驾驶循环绝不能把异常抛进调度器：记一笔、按卡住收尾，交给任务层重规划/放弃
            plugin.getLogger().warning("[寻路] 驾驶循环异常，转卡住处理: " + t);
            status = Status.STUCK;
            stopLoop();
            zeroHorizontal();
        }
    }

    private void step() {
        Villager v = villager;
        if (v == null || !v.isValid() || v.isDead()) {
            status = Status.STUCK;
            stopLoop();
            return;
        }
        // 驾驶期间身体归插件：外部代码（对话结束等）打开 AI 就压回去，防原版大脑抢方向
        if (v.hasAI()) v.setAI(false);

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
            zeroHorizontal();
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
                zeroHorizontal();
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
        double dx = wp.getX() - me.getX();
        double dz = wp.getZ() - me.getZ();
        double dy = wp.getY() - me.getY();
        double hd = Math.sqrt(dx * dx + dz * dz);

        double target = BASE_SPEED_PER_TICK * speedMultiplier;
        if (hd < 1.2) target *= Math.max(0.35, hd / 1.2);   // 抵近减速，防冲过头来回摆
        double vx = 0, vz = 0;
        if (hd > 0.05) {
            vx = dx / hd * target;
            vz = dz / hd * target;
        }

        Vector vel = v.getVelocity();
        double vy = vel.getY();
        Block feet = me.getBlock();
        if (NavService.isClimb(feet.getType())) {
            // 梯子/藤蔓上：速度托着爬/滑/蹭出梯口
            vy = dy > 0.6 ? 0.38 : (dy < -0.6 ? -0.30 : 0.12);
        } else if (feet.getType() == org.bukkit.Material.WATER
                || me.clone().add(0, 0.4, 0).getBlock().getType() == org.bukkit.Material.WATER) {
            // 水里：朝路点高度游，水平打折
            vy = dy > 0.4 ? 0.16 : (dy < -0.4 ? -0.08 : vel.getY() * 0.6);
            vx *= 0.65;
            vz *= 0.65;
        } else if (v.isOnGround() && needsJump(me, wp, hd, target)) {
            vy = 0.42;   // 原版跳跃高度（约 1.25 格）
        }
        v.setVelocity(new Vector(vx, vy, vz));

        // 朝行进方向转身（平滑限速，别像指南针乱甩）
        if (hd > 0.3) {
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float cur = me.getYaw();
            float diff = ((targetYaw - cur + 540f) % 360f) - 180f;
            float turn = Math.max(-30f, Math.min(30f, diff));
            v.setRotation(cur + turn, 0f);
        }

        prevX = me.getX();
        prevZ = me.getZ();
    }

    /**
     * 要不要跳：路点在高处且已贴近（台阶）→ 跳；或者明明在使劲走却几乎没动
     * （被一格沿挡住、自动登阶 0.6 不够）连续两次 → 跳。
     */
    private boolean needsJump(Location me, Location wp, double hd, double targetSpeed) {
        double dy = wp.getY() - me.getY();
        if (dy >= 0.75 && hd <= 1.15) {
            blockedRuns = 0;
            return true;
        }
        if (targetSpeed < 0.05) return false;
        double moved = Math.hypot(me.getX() - prevX, me.getZ() - prevZ);
        if (moved < 0.03) {
            blockedRuns++;
        } else {
            blockedRuns = 0;
        }
        if (blockedRuns >= 2 && dy >= -0.5) {
            blockedRuns = 0;
            return true;
        }
        return false;
    }

    /** 兼容旧调用：粗暴停车（清原版路径） */
    public static void halt(Villager villager) {
        if (villager == null || !villager.isValid()) return;
        villager.getPathfinder().stopPathfinding();
    }
}
