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
import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;
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
    /** 允许的目标高度差；超过这个高度差，原版寻路也基本走不过去。 */
    private static final double MAX_VERTICAL_DIFF = 3.0;
    /**
     * 卡住判定：连续这么久没有实际位移就判失败。
     * <p>
     * 改用 Paper 原生寻路后，正常情况会绕墙、上台阶；但目标可能在封闭空间/水里/悬空，
     * 寻路算不出路径时会原地不动。8 秒足够走 1 格以上，正常绕障碍不该触发。
     */
    private static final long STUCK_WINDOW_MS = 8_000L;
    /** 判定"有进展"的最小水平位移（格）。 */
    private static final double STUCK_MIN_MOVE = 0.5;

    /** 默认寻路速度倍率：1.0 = 原版正常步行，0.8 稍慢一点更自然 */
    private static final double DEFAULT_SPEED_MULTIPLIER = 0.8;
    /**
     * 重新寻路的间隔（毫秒）。
     * <p>
     * Pathfinder 的路径走完、或被动态方块挡掉后会清空；此时隔一段时间重下一次指令，
     * 让 NPC 能继续绕路，而不是守着一条废弃路径原地罚站。
     */
    private static final long REPATH_INTERVAL_MS = 3_000L;
    /** 单个任务最多重下一次寻路指令的次数，防止不可达目标无限重算烧 CPU */
    private static final int MAX_REPATHS = 8;
    /** 允许 NPC 在任务中偏离目标的额外距离（格）—— 被僵尸赶跑等外部因素，超过就放弃 */
    private static final double DRIFT_TOLERANCE = 8.0;

    private final McaiPlugin plugin;

    public NpcMoveToTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.move_to";
    }

    /**
     * 别名 move_to：老后门的同名动作就此退役——对话/行为模型继续用旧名调用，
     * 执行统一走本工具的寻路任务（原版 Pathfinder 绕墙上台阶，不再朝坐标直线硬推）。
     */
    @Override
    public java.util.Set<String> aliases() {
        return java.util.Set.of("move_to");
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
                .param("speed", ToolSchema.Param.number("移动速度倍率：1.0 = 原版正常步行，默认 0.8（稍慢）").min(0.1).max(2.0))
                .param("message", ToolSchema.Param.string("出发时说的中文台词（仅对话模型会带，任务本身不使用）").maxLength(120))
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
        /** 上次下发寻路指令的时刻 —— Pathfinder 路径失效后按 {@link #REPATH_INTERVAL_MS} 重下 */
        private long lastRepathMs;
        /** 已重下寻路指令的次数 */
        private int repathCount;

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

            // 改用 Paper 原生寻路：必须由原版 AI 驱动，setAI(false) 会让算好的路径也走不动。
            // 这里恢复 AI 并清掉任务开始前的残余水平动量，避免起步"滑一下"。
            villager.setAI(true);
            villager.setTarget(null);
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
            repathCount = 0;
            double speed = ToolArgs.number(arguments, "speed", DEFAULT_SPEED_MULTIPLIER);
            startPathing(villager, target, speed);
        }

        /**
         * 下发一次原生寻路指令。
         * <p>
         * 不再逐 tick 塞速度 —— 原版导航自己做 A* 寻路、绕墙、上台阶、跨栅栏，
         * 速度和碰撞交给原版物理，不会出现抽搐、飞出去、顶墙不动的问题。
         */
        private void startPathing(Villager villager, Location target, double speed) {
            Pathfinder pathfinder = villager.getPathfinder();
            pathfinder.moveTo(target, speed);
            lastRepathMs = System.currentTimeMillis();
            plugin.getLogger().info("[task] npc.move_to 寻路: 目标 ("
                    + round(target.getX()) + ", " + round(target.getY()) + ", " + round(target.getZ())
                    + ") speed=" + speed);
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

            // 不可达提前失败：重寻路次数用尽仍没有路径，说明目标大概率在封闭建筑/围栏/水里，
            // 再耗下去只会拖满 60 秒超时（实测两个任务都是这样 TIMEOUT 的）。直接判失败，
            // 让上层尽早换目标。
            Pathfinder pathfinder = villager.getPathfinder();
            if (!pathfinder.hasPath() && repathCount >= MAX_REPATHS) {
                stop(state);
                task.fail(nowMs, ToolResult.ErrorCode.MOVE_STUCK,
                        "无可行路径：重寻路 " + MAX_REPATHS + " 次后仍没有路径，剩余 " + round(horizontal) + " 格");
                plugin.getLogger().info("[task] npc.move_to 无可行路径, 提前失败: npc=" + task.npcId()
                        + " 剩余 " + round(horizontal) + " 格");
                return;
            }

            // 偏离守卫：村民会被僵尸惊到逃跑（AI 开启后恐慌寻路优先级更高），
            // 一旦被赶得比原任务允许范围还远，就没必要再追了 —— 判失败让上层重新决策。
            if (horizontal > MAX_DISTANCE + DRIFT_TOLERANCE) {
                stop(state);
                task.fail(nowMs, ToolResult.ErrorCode.MOVE_STUCK,
                        "NPC 偏离目标：剩余 " + round(horizontal) + " 格，超出允许范围");
                plugin.getLogger().info("[task] npc.move_to 偏离目标, 提前失败: npc=" + task.npcId()
                        + " 剩余 " + round(horizontal) + " 格");
                return;
            }

            // 寻路维护：路径走完/被方块挡掉后 Pathfinder 会清空路径，原地不动。
            // 隔 REPATH_INTERVAL_MS 重下一次指令（最多 MAX_REPATHS 次），让它重新绕路；
            // 次数用尽后仍无路径由上面的不可达判定收尾。
            if (!pathfinder.hasPath()
                    && nowMs - lastRepathMs >= REPATH_INTERVAL_MS
                    && repathCount < MAX_REPATHS) {
                double speed = ToolArgs.number(arguments, "speed", DEFAULT_SPEED_MULTIPLIER);
                pathfinder.moveTo(target, speed);
                lastRepathMs = nowMs;
                repathCount++;
                plugin.getLogger().info("[task] npc.move_to 重新寻路(" + repathCount + "/" + MAX_REPATHS
                        + ") npc=" + task.npcId() + " 剩余 " + round(horizontal) + " 格");
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
            // 实际移动由原版导航（Pathfinder）驱动，这里只做重寻路/进度/到达/卡住判定
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
            if (state == null) return;
            Villager villager = state.entity();
            if (villager == null || !villager.isValid()) return;

            // 必须显式停止原版寻路，否则任务结束后村民还会沿着旧路径继续走
            villager.getPathfinder().stopPathfinding();
            halt(villager);
            // 只在非对话状态恢复原版 AI：对话期间 AI 由 startConversation 冻结，
            // 这里如果无条件 setAI(true)，玩家正聊着村民就会重新乱逛。
            if (state.interactingPlayer() == null) {
                villager.setAI(true);
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
