package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.tool.NpcTask;
import com.howard.mcai.tool.NpcTaskAction;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolArgs;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * npc.move_to —— 低风险持续行动工具，用于验证任务生命周期。
 * <p>
 * 只移动 NPC 自身，不修改方块、不涉及交易和背包，因此风险等级为 LOW。
 * 对话期间禁止启动；已经开始的移动任务在 NPC 进入对话时会被取消并停止速度。
 */
public final class NpcMoveToTool implements NpcTool {

    /** 单次移动任务允许的最大水平距离，避免模型把 NPC 派到世界另一头。 */
    private static final double MAX_DISTANCE = 32.0;
    /** 到达判定半径。 */
    private static final double ARRIVE_DISTANCE = 1.5;
    /** 允许的目标高度差；NPC 只有水平速度，差太多会永远到不了。 */
    private static final double MAX_VERTICAL_DIFF = 3.0;
    /**
     * 卡住判定：连续这么久没有实际位移就判失败。
     * <p>
     * 村民没有原版寻路，移动靠 setVelocity 推 —— 撞墙/卡树/被栅栏围住时速度会被物理引擎抵消，
     * 任务会一直"RUNNING"拖到 60 秒超时，期间该 NPC 无法接受新行动（NpcTaskManager 单任务限制）。
     * 8 秒足够走过 1 格以上，正常绕障碍不该触发。
     */
    private static final long STUCK_WINDOW_MS = 8_000L;
    /** 判定"有进展"的最小水平位移（格）。 */
    private static final double STUCK_MIN_MOVE = 0.5;

    /**
     * speed=1.0 对应的实际速度（格/秒）。
     * <p>
     * 村民普通步行大约 2 格/秒。速度必须换算成"每个 tick 施加的水平速度"（除以 20），
     * 而不是每 tick 直接塞一个 0.8 —— 后者等于 16 格/秒，实测就是"飞出去再被摩擦拽住"的抽搐感。
     */
    private static final double BASE_SPEED_BLOCKS_PER_SECOND = 2.0;
    /** 默认速度倍率，约等于村民正常走路 */
    private static final double DEFAULT_SPEED_MULTIPLIER = 0.8;
    /** 逐 tick 推进时，到这个距离就停止施加速度（避免在目标点附近来回滑动） */
    private static final double HALF_ARRIVE_DISTANCE = 0.6;
    /**
     * 受阻自动跳跃的垂直初速 —— 原版跳跃初速 0.42，约能越过 1 格台阶。
     * <p>
     * 任务期间 setAI(false) 冻住了原版 AI，同时也失去了原版自动跳跃/跨台阶能力；
     * 只给水平推力的话，村民会顶在方块边上一动不动，8 秒后必然 MOVE_STUCK。
     */
    private static final double JUMP_VELOCITY = 0.42;
    /** 连续多少 tick 水平位移几乎为 0 才判定为"被挡住"（5 tick ≈ 0.25 秒） */
    private static final int BLOCKED_TICKS_BEFORE_JUMP = 5;
    /** 单 tick 水平位移低于该值视为没动（正常 0.8 倍速约 0.08 格/tick） */
    private static final double BLOCKED_MIN_MOVE_PER_TICK = 0.01;

    private final McaiPlugin plugin;

    public NpcMoveToTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.move_to";
    }

    @Override
    public String description() {
        return "让 NPC 走到同世界指定坐标。返回任务 ID，可用 npc.task.get 查询进度、npc.task.cancel 取消。";
    }

    @Override
    public Category category() {
        return Category.ACTION;
    }

    @Override
    public Risk risk() {
        return Risk.LOW;
    }

    @Override
    public boolean allowedWhileConversing() {
        return false;
    }

    @Override
    public long defaultTimeoutMs() {
        return 60_000L;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("x", ToolSchema.Param.number("目标 X 坐标").min(-30_000_000).max(30_000_000), true)
                .param("y", ToolSchema.Param.number("目标 Y 坐标").min(-256).max(512), true)
                .param("z", ToolSchema.Param.number("目标 Z 坐标").min(-30_000_000).max(30_000_000), true)
                .param("speed", ToolSchema.Param.number("移动速度倍率：1.0 ≈ 村民正常步行（约 2 格/秒），默认 0.8").min(0.1).max(2.0))
                .build();
    }

    @Override
    public NpcTaskAction taskAction() {
        return new MoveAction(plugin);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        // 持续行动必须创建任务；Dispatcher 不会把 sustained 工具走到这里。
        return ToolResult.rejected(ToolResult.ErrorCode.INVALID_REQUEST,
                "npc.move_to 是持续行动工具，必须通过任务系统执行");
    }

    private static final class MoveAction implements NpcTaskAction {

        private final McaiPlugin plugin;
        /** 逐 tick 推进移动的调度器；任务结束时必须取消，否则会留下"幽灵推力" */
        private BukkitTask movementTask;

        private MoveAction(McaiPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void start(NpcTask task, ToolContext context, JsonObject arguments) {
            VillagerState state = resolve(task);
            if (state == null) {
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.NPC_NOT_FOUND,
                        "NPC 不存在或实体已失效");
                return;
            }
            Villager villager = state.entity();
            Location target = target(villager, arguments);
            if (target == null) {
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标坐标超出世界高度范围");
                return;
            }
            double horizontal = horizontalDistance(villager.getLocation(), target);
            if (horizontal > MAX_DISTANCE) {
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标距离 " + round(horizontal) + " 格，超过单次移动上限 " + (int) MAX_DISTANCE + " 格");
                return;
            }
            if (Math.abs(target.getY() - villager.getLocation().getY()) > MAX_VERTICAL_DIFF) {
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标高度差超过 " + (int) MAX_VERTICAL_DIFF + " 格，NPC 无法通过水平移动到达");
                return;
            }
            if (horizontal <= ARRIVE_DISTANCE) {
                task.complete(System.currentTimeMillis(), result(task, villager));
                return;
            }

            // 冻结原版 AI：村民的随机漫步、看向玩家等目标每个 tick 都会改写 velocity，
            // 和本任务的推力叠加后就是"原地抽搐 + 速度忽快忽慢"的直接原因。
            // 顺手清掉水平动量，避免把任务开始前残余的行走速度带进来。
            villager.setAI(false);
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
            startMovement(villager, task, state, arguments);
        }

        /**
         * 逐 tick 施加速度。
         * <p>
         * 原来每秒才推一次（CentralPlanner 心跳），速度会在两次推力之间被摩擦衰减，
         * 看起来像一顿一顿地抽搐；这里改成每 tick 给一次恒定水平速度，摩擦不再影响节奏。
         */
        private void startMovement(Villager villager, NpcTask task, VillagerState state,
                                   JsonObject arguments) {
            Location target = target(villager, arguments);
            if (target == null) return;
            double speed = ToolArgs.number(arguments, "speed", DEFAULT_SPEED_MULTIPLIER);
            // 每 tick 速度 = 目标格/秒 ÷ 20；不写垂直分量，保留重力/下落，避免悬空
            double perTick = speed * BASE_SPEED_BLOCKS_PER_SECOND / 20.0;
            // 受阻检测的跨 tick 状态：上一 tick 位置 + 连续无位移计数
            double[] lastPos = {villager.getX(), villager.getZ()};
            int[] blockedTicks = {0};

            movementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!task.isActive() || !villager.isValid() || villager.isDead()) {
                    cancelMovement();
                    return;
                }
                // 进入对话后 NPC 必须站定：这里先停推，正式取消交给每秒 tick，避免重复状态流转
                if (state.interactingPlayer() != null) {
                    halt(villager);
                    return;
                }
                Location from = villager.getLocation();
                double dx = target.getX() - from.getX();
                double dz = target.getZ() - from.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= HALF_ARRIVE_DISTANCE) {
                    halt(villager);
                    return;
                }

                // 受阻自动跳跃：连续 5 tick 水平几乎没动、且站在地面上 → 补一次原版跳跃。
                // 阈值取得很小（0.01 格/tick），正常行走约 0.08 格/tick，不会误触发；
                // 只在 isOnGround 时给，避免在空中反复叠加垂直速度变成"火箭"。
                if (Math.abs(from.getX() - lastPos[0]) < BLOCKED_MIN_MOVE_PER_TICK
                        && Math.abs(from.getZ() - lastPos[1]) < BLOCKED_MIN_MOVE_PER_TICK) {
                    blockedTicks[0]++;
                    if (blockedTicks[0] >= BLOCKED_TICKS_BEFORE_JUMP && villager.isOnGround()) {
                        Vector jump = villager.getVelocity();
                        jump.setY(JUMP_VELOCITY);
                        villager.setVelocity(jump);
                        blockedTicks[0] = 0;
                    }
                } else {
                    blockedTicks[0] = 0;
                }
                lastPos[0] = from.getX();
                lastPos[1] = from.getZ();

                Vector velocity = villager.getVelocity();
                velocity.setX(dx / dist * perTick);
                velocity.setZ(dz / dist * perTick);
                villager.setVelocity(velocity);
            }, 0L, 1L);
        }

        @Override
        public void tick(NpcTask task, ToolContext context, JsonObject arguments) {
            VillagerState state = resolve(task);
            if (state == null) {
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.NPC_NOT_FOUND,
                        "NPC 不存在或实体已失效");
                return;
            }
            Villager villager = state.entity();

            // 对话状态必须站立不动：任务开始后玩家可能右键进入对话，这里要立即让路
            if (state.interactingPlayer() != null) {
                stop(state);
                task.cancel(System.currentTimeMillis(), "NPC 进入对话，移动任务已取消");
                return;
            }

            Location target = target(villager, arguments);
            if (target == null) {
                stop(state);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标坐标超出世界高度范围");
                return;
            }

            long nowMs = System.currentTimeMillis();
            Location current = villager.getLocation();
            double horizontal = horizontalDistance(current, target);
            if (horizontal <= ARRIVE_DISTANCE) {
                stop(state);
                task.complete(nowMs, result(task, villager));
                return;
            }

            // 卡住检测：进度基准点存在 task.data()，随 npc.task.get 一起可查
            JsonObject progress = task.data();
            double lastX = current.getX();
            double lastZ = current.getZ();
            long lastProgressMs = nowMs;
            if (progress != null && progress.has("last_progress_x")
                    && progress.has("last_progress_z") && progress.has("last_progress_ms")) {
                lastX = progress.get("last_progress_x").getAsDouble();
                lastZ = progress.get("last_progress_z").getAsDouble();
                lastProgressMs = progress.get("last_progress_ms").getAsLong();
            }
            double dx = current.getX() - lastX;
            double dz = current.getZ() - lastZ;
            if (Math.sqrt(dx * dx + dz * dz) >= STUCK_MIN_MOVE) {
                lastX = current.getX();
                lastZ = current.getZ();
                lastProgressMs = nowMs;
            } else if (nowMs - lastProgressMs >= STUCK_WINDOW_MS) {
                stop(state);
                task.fail(nowMs, ToolResult.ErrorCode.MOVE_STUCK,
                        "移动卡住：" + (STUCK_WINDOW_MS / 1000) + " 秒内位移不足 " + STUCK_MIN_MOVE + " 格");
                return;
            }

            JsonObject progressData = new JsonObject();
            progressData.addProperty("target_x", target.getX());
            progressData.addProperty("target_y", target.getY());
            progressData.addProperty("target_z", target.getZ());
            progressData.addProperty("remaining_distance", round(horizontal));
            progressData.addProperty("last_progress_x", lastX);
            progressData.addProperty("last_progress_z", lastZ);
            progressData.addProperty("last_progress_ms", lastProgressMs);
            task.updateData(progressData);
            // 实际的逐 tick 推进由 startMovement 里的调度器负责，这里只做进度/到达/卡住判定
        }

        @Override
        public void onCancel(NpcTask task) {
            stop(resolve(task));
        }

        private VillagerState resolve(NpcTask task) {
            VillagerState state = plugin.getVillagers().get(task.npcId());
            if (state == null) return null;
            Villager villager = state.entity();
            if (villager == null || !villager.isValid() || villager.isDead()) return null;
            return state;
        }

        private static Location target(Villager villager, JsonObject arguments) {
            World world = villager.getWorld();
            double x = ToolArgs.number(arguments, "x", 0);
            double y = ToolArgs.number(arguments, "y", 0);
            double z = ToolArgs.number(arguments, "z", 0);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return null;
            return new Location(world, x, y, z);
        }

        private static double horizontalDistance(Location from, Location to) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        private static JsonObject result(NpcTask task, Villager villager) {
            Location location = villager.getLocation();
            JsonObject data = new JsonObject();
            data.addProperty("task_id", task.id());
            data.addProperty("npc_id", task.npcId().toString());
            data.addProperty("arrived", true);
            data.addProperty("x", round(location.getX()));
            data.addProperty("y", round(location.getY()));
            data.addProperty("z", round(location.getZ()));
            return data;
        }

        private void stop(VillagerState state) {
            cancelMovement();
            if (state == null) return;
            Villager villager = state.entity();
            if (villager == null || !villager.isValid()) return;

            halt(villager);
            // 只在非对话状态恢复原版 AI：对话期间 AI 由 startConversation 冻结，
            // 这里如果无条件 setAI(true)，玩家正聊着村民就会重新乱逛。
            if (state.interactingPlayer() == null) {
                villager.setAI(true);
            }
        }

        private void cancelMovement() {
            if (movementTask != null) {
                movementTask.cancel();
                movementTask = null;
            }
        }

        /** 只清水平速度，保留垂直分量：否则走下台阶时会悬空 */
        private static void halt(Villager villager) {
            Vector velocity = villager.getVelocity();
            velocity.setX(0);
            velocity.setZ(0);
            villager.setVelocity(velocity);
        }

        private static double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }
}
