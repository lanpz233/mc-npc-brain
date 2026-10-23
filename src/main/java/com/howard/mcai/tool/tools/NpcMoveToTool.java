package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
import com.howard.mcai.mob.NeedsState;
import com.howard.mcai.mob.VillagerState;
import com.howard.mcai.nav.NavDriver;
import com.howard.mcai.nav.NavService;
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

    /** 单次移动任务允许的最大水平距离，避免模型把 NPC 派到世界另一头（自研寻路后放宽）。 */
    private static final double MAX_DISTANCE = 48.0;
    /** 到达判定半径。 */
    private static final double ARRIVE_DISTANCE = 1.5;
    /** 允许的目标高度差；自研寻路认台阶/楼梯/梯子，不再是原版的 3 格。 */
    private static final double MAX_VERTICAL_DIFF = 8.0;
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
    /** 单个任务最多重下一次寻路指令的次数，防止不可达目标无限重算烧 CPU（8→4：不可达时早点收场） */
    private static final int MAX_REPATHS = 4;

    /** 无进展判卡死：离目标的最近距离在这么久内没有实质缩短（≥1 格），就算人还在动也判到不了 */
    private static final long NO_PROGRESS_WINDOW_MS = 9_000L;
    private static final double PROGRESS_MIN_GAIN = 1.0;
    /** 到门算到：曾经到达过目标这个距离内，无进展窗口到期仍进不去时按基本到达完成（目标在屋里、门难进） */
    private static final double DOORSTEP_DISTANCE = 3.0;
    /** 绕行宽限：从"最近点"出发的净位移达到这个数，算真在绕路赶路（不是蹭墙），可刷新无进展窗口 */
    private static final double DETOUR_MIN_NET = 4.0;
    /** 绕行宽限最多次数（每次约多给一个窗口时长），防真走不通时无限拖延 */
    private static final int MAX_DETOUR_EXTENDS = 3;
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
        /** 自研代驾执行器：仅在接管段非 null（原版走不通时接管一小段，见 escalateOrFail） */
        private NavDriver driver;
        /** 当前是否处于自研接管段 */
        private boolean takeoverMode;
        /** 本任务已接管次数（防原版 ↔ 自研来回踢皮球） */
        private int takeoverCount;
        /** 单个任务最多接管次数，用尽判走不通 */
        private static final int MAX_TAKEOVERS = 2;
        /** 无进展判定的工作值：本 tick 算出的"离目标最近距离/刷新时刻"，tick 末随 progressData 写回 */
        private double pendingBest = Double.MAX_VALUE;
        private long pendingBestSince;
        private double pendingBestX;
        private double pendingBestZ;
        private int pendingDetourExtends;

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
                settleNeedFailure(state, null);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标坐标超出世界高度范围");
                return;
            }
            double horizontal = horizontalDistance(villager.getLocation(), target);
            if (horizontal > MAX_DISTANCE) {
                settleNeedFailure(state, target);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标距离 " + round(horizontal) + " 格，超过单次移动上限 " + (int) MAX_DISTANCE + " 格");
                return;
            }
            if (Math.abs(target.getY() - villager.getLocation().getY()) > MAX_VERTICAL_DIFF) {
                settleNeedFailure(state, target);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_ARGUMENT,
                        "目标高度差超过 " + (int) MAX_VERTICAL_DIFF + " 格，NPC 无法通过水平移动到达");
                return;
            }
            if (horizontal <= ARRIVE_DISTANCE) {
                settleNeedSuccess(state);
                task.complete(System.currentTimeMillis(), result(task, villager));
                return;
            }

            // 改用 Paper 原生寻路：必须由原版 AI 驱动，setAI(false) 会让算好的路径也走不动。
            // 这里恢复 AI 并清掉任务开始前的残余水平动量，避免起步"滑一下"。
            villager.setAI(true);
            villager.setTarget(null);
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
            repathCount = 0;
            takeoverCount = 0;
            takeoverMode = false;
            double speed = ToolArgs.number(arguments, "speed", DEFAULT_SPEED_MULTIPLIER);
            // 代驾模式（v0.6.0，Fong 拍板）：原版导航做主先走；它走不通（没路/
            // 没进展/卡住）时才由自研 A* 接管一小段（梯子、宽水这类原版死角），
            // 过了死角立刻交还 —— 方向盘只有一个，见 escalateOrFail。
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
                settleNeedSuccess(state);
                task.complete(nowMs, result(task, villager));
                return;
            }

            // 自研接管段：驾驶归 NavDriver，本层只看交接/卡住做交还
            if (takeoverMode) {
                tickTakeover(task, state, villager, target, arguments, nowMs, horizontal);
                return;
            }

            // 不可达提前失败：重寻路次数用尽仍没有路径，说明目标大概率在封闭建筑/围栏/水里，
            // 再耗下去只会拖满 60 秒超时（实测两个任务都是这样 TIMEOUT 的）。直接判失败，
            // 让上层尽早换目标。
            Pathfinder pathfinder = villager.getPathfinder();
            if (!pathfinder.hasPath() && repathCount >= MAX_REPATHS) {
                escalateOrFail(task, state, target, arguments, nowMs, horizontal,
                        "无可行路径：重寻路 " + MAX_REPATHS + " 次后仍没有路径，剩余 " + round(horizontal) + " 格");
                return;
            }

            // 无进展判卡死（与下面的位移判定互补）：位移判定只看"人动没动"，贴墙来回蹭永远骗得过它
            //（实测剩余距离 12.87↔14.08 跳了 28 秒）。这里看"离目标的最近距离有没有在缩"：
            // 9 秒内没能靠近至少 1 格 → 原则上判走不通，但两个例外（2026-10-21 李强安实测日志）：
            // ① 绕行宽限：目标在屋里时导航带他绕房子找门，绕行段离目标越来越远是正常的。
            //    从"最近点"出发净位移 ≥4 格 = 真在赶路（蹭墙净位移≈0），刷新窗口，最多 3 次。
            // ② 到门算到：宽限用尽仍进不去、但曾到过目标 3 格内（墙根/门口）→ 按基本到达完成。
            //    李强安摸到 2.35 格又被导航带走、最后判失败，冤的就是这两条都没有。
            {
                JsonObject prog = task.data();
                double best = prog != null && prog.has("best_distance")
                        ? prog.get("best_distance").getAsDouble() : horizontal;
                long bestSince = prog != null && prog.has("best_since_ms")
                        ? prog.get("best_since_ms").getAsLong() : nowMs;
                double bestX = prog != null && prog.has("best_x")
                        ? prog.get("best_x").getAsDouble() : current.getX();
                double bestZ = prog != null && prog.has("best_z")
                        ? prog.get("best_z").getAsDouble() : current.getZ();
                int detourExtends = prog != null && prog.has("detour_extends")
                        ? prog.get("detour_extends").getAsInt() : 0;
                if (horizontal < best - PROGRESS_MIN_GAIN) {
                    best = horizontal;
                    bestSince = nowMs;
                    bestX = current.getX();
                    bestZ = current.getZ();
                } else if (nowMs - bestSince >= NO_PROGRESS_WINDOW_MS) {
                    double netDx = current.getX() - bestX;
                    double netDz = current.getZ() - bestZ;
                    double netFromBest = Math.sqrt(netDx * netDx + netDz * netDz);
                    if (detourExtends < MAX_DETOUR_EXTENDS && netFromBest >= DETOUR_MIN_NET) {
                        // 真在绕路赶路：刷新窗口、锚点挪到当前位置，给他把圈绕完的机会
                        bestSince = nowMs;
                        bestX = current.getX();
                        bestZ = current.getZ();
                        detourExtends++;
                        plugin.getLogger().info("[task] npc.move_to 绕行宽限(" + detourExtends
                                + "/" + MAX_DETOUR_EXTENDS + ") npc=" + task.npcId()
                                + " 最近 " + round(best) + " 格 当前剩余 " + round(horizontal) + " 格");
                    } else if (best <= DOORSTEP_DISTANCE) {
                        // 到过门口就是到了：按完成收尾，挂账照常结算，不记失败地点
                        stop(state);
                        settleNeedSuccess(state);
                        JsonObject data = result(task, villager);
                        data.addProperty("close_enough", true);
                        data.addProperty("best_distance", round(best));
                        task.complete(nowMs, data);
                        plugin.getLogger().info("[task] npc.move_to 抵近完成: npc=" + task.npcId()
                                + " 曾到达 " + round(best) + " 格，当前剩余 " + round(horizontal) + " 格");
                        return;
                    } else {
                        escalateOrFail(task, state, target, arguments, nowMs, horizontal,
                                "靠近不了目标：" + (NO_PROGRESS_WINDOW_MS / 1000) + " 秒内没能更接近，剩余 "
                                        + round(horizontal) + " 格");
                        return;
                    }
                }
                // 暂存，tick 末随 progressData 一起写回 task.data
                pendingBest = best;
                pendingBestSince = bestSince;
                pendingBestX = bestX;
                pendingBestZ = bestZ;
                pendingDetourExtends = detourExtends;
            }

            // 偏离守卫：村民会被僵尸惊到逃跑（AI 开启后恐慌寻路优先级更高），
            // 一旦被赶得比原任务允许范围还远，就没必要再追了 —— 判失败让上层重新决策。
            if (horizontal > MAX_DISTANCE + DRIFT_TOLERANCE) {
                stop(state);
                settleNeedFailure(state, target);
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
                escalateOrFail(task, state, target, arguments, nowMs, horizontal,
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
            progressData.addProperty("best_distance", pendingBest);
            progressData.addProperty("best_since_ms", pendingBestSince);
            progressData.addProperty("best_x", pendingBestX);
            progressData.addProperty("best_z", pendingBestZ);
            progressData.addProperty("detour_extends", pendingDetourExtends);
            task.updateData(progressData);
            // 实际移动由原版导航（Pathfinder）驱动，这里只做重寻路/进度/到达/卡住判定
        }

        /**
         * 代驾接管段的 tick：驾驶员开到交接点（特殊段之后的第一个普通格）就交还；
         * 接管段里也卡住 → 同样交还，让原版监测再判一次（接管次数有上限，
         * 不会无限踢皮球）。到达判定在 tick 开头已全局处理，这里不会漏收尾。
         */
        private void tickTakeover(NpcTask task, VillagerState state, Villager villager, Location target,
                                  JsonObject arguments, long nowMs, double horizontal) {
            if (driver == null) {  // 防御：接管标志与驾驶员不一致时退回原版监测
                takeoverMode = false;
                return;
            }
            // 偏离守卫（与原版模式同口径）：被僵尸赶得比允许范围还远就放弃
            if (horizontal > MAX_DISTANCE + DRIFT_TOLERANCE) {
                stop(state);
                settleNeedFailure(state, target);
                task.fail(nowMs, ToolResult.ErrorCode.MOVE_STUCK,
                        "NPC 偏离目标：剩余 " + round(horizontal) + " 格，超出允许范围");
                return;
            }
            NavDriver.Status status = driver.status();
            if (status == NavDriver.Status.ARRIVED || status == NavDriver.Status.STUCK) {
                boolean arrived = status == NavDriver.Status.ARRIVED;
                plugin.getLogger().info("[task] npc.move_to 代驾" + (arrived ? "到达交接点" : "接管段卡住")
                        + ", 交还原版: npc=" + task.npcId() + " 剩余 " + round(horizontal) + " 格");
                handBackToVanilla(task, villager, target, arguments, nowMs, horizontal);
                return;
            }
            JsonObject progressData = new JsonObject();
            progressData.addProperty("target_x", target.getX());
            progressData.addProperty("target_y", target.getY());
            progressData.addProperty("target_z", target.getZ());
            progressData.addProperty("remaining_distance", round(horizontal));
            progressData.addProperty("engine", "takeover");
            progressData.addProperty("takeovers", takeoverCount);
            task.updateData(progressData);
        }

        /** 交还方向盘：停自研驾驶、重置原版侧的进展锚点、重新向目标下令 */
        private void handBackToVanilla(NpcTask task, Villager villager, Location target,
                                       JsonObject arguments, long nowMs, double horizontal) {
            if (driver != null) {
                driver.stop();
                driver = null;
            }
            takeoverMode = false;
            repathCount = 0;
            lastRepathMs = nowMs;
            Location current = villager.getLocation();
            int detourExtends = task.data() != null && task.data().has("detour_extends")
                    ? task.data().get("detour_extends").getAsInt() : 0;
            JsonObject anchors = new JsonObject();
            anchors.addProperty("target_x", target.getX());
            anchors.addProperty("target_y", target.getY());
            anchors.addProperty("target_z", target.getZ());
            anchors.addProperty("remaining_distance", round(horizontal));
            anchors.addProperty("last_progress_x", current.getX());
            anchors.addProperty("last_progress_z", current.getZ());
            anchors.addProperty("last_progress_ms", nowMs);
            anchors.addProperty("best_distance", horizontal);
            anchors.addProperty("best_since_ms", nowMs);
            anchors.addProperty("best_x", current.getX());
            anchors.addProperty("best_z", current.getZ());
            anchors.addProperty("detour_extends", detourExtends);
            task.updateData(anchors);
            double speed = ToolArgs.number(arguments, "speed", DEFAULT_SPEED_MULTIPLIER);
            villager.getPathfinder().moveTo(target, speed);
        }

        /**
         * 原版走不通时的升级处理（代驾模式的核心）：先问自研 A* 有没有路 ——
         * 有路就只接管到「特殊段（梯子/宽水）之后的第一个普通格」为止
         * （没有特殊段就只拨正几个路点），开完立刻交还；没路、或接管次数
         * 用尽 → 按原失败口径收尾。方向盘每一刻只在一双手里，不打架。
         */
        private void escalateOrFail(NpcTask task, VillagerState state, Location target,
                                    JsonObject arguments, long nowMs, double horizontal,
                                    String failMessage) {
            Villager villager = state.entity();
            if (takeoverCount >= MAX_TAKEOVERS || plugin.getNavService() == null) {
                hardFail(task, state, target, nowMs, failMessage);
                return;
            }
            NavService.Plan plan = plugin.getNavService().plan(state.getUniqueId(), villager.getLocation(), target);
            if (plan == null || plan.waypoints().isEmpty()) {
                hardFail(task, state, target, nowMs, failMessage);
                return;
            }
            int handover = com.howard.mcai.nav.TakeoverSegments.handoverIndex(plan.cellTypes());
            if (handover < 0) {
                hardFail(task, state, target, nowMs, failMessage);
                return;
            }
            takeoverCount++;
            java.util.List<Location> subWaypoints = plan.waypoints().subList(0, handover + 1);
            boolean subReached = handover == plan.waypoints().size() - 1 && plan.reached();
            NavService.Plan subPlan = new NavService.Plan(subWaypoints, plan.doorCells(),
                    subReached, plan.cellTypes().subList(0, handover + 1));
            if (driver != null) driver.stop();
            driver = new NavDriver(plugin, subPlan);
            double speed = ToolArgs.number(arguments, "speed", DEFAULT_SPEED_MULTIPLIER);
            driver.start(villager, speed);
            takeoverMode = true;
            plugin.getLogger().info("[task] npc.move_to 代驾接管(" + takeoverCount + "/" + MAX_TAKEOVERS
                    + "): npc=" + task.npcId() + " 全程 " + plan.waypoints().size()
                    + " 个路点，接管到第 " + (handover + 1) + " 个，剩余 " + round(horizontal) + " 格");
        }

        /** 接管也救不了（或次数用尽）：按原失败口径收尾 */
        private void hardFail(NpcTask task, VillagerState state, Location target,
                              long nowMs, String failMessage) {
            stop(state);
            settleNeedFailure(state, target);
            task.fail(nowMs, ToolResult.ErrorCode.MOVE_STUCK, failMessage);
            plugin.getLogger().info("[task] npc.move_to 失败收尾: npc=" + task.npcId() + " " + failMessage);
        }

        @Override
        public void onCancel(NpcTask task) {
            VillagerState state = resolve(task);
            // 取消（含进入对话被打断）：挂账的需求直接作废，不降也不记失败地点
            if (state != null) state.consumePendingNeedSatisfy();
            stop(state);
        }

        /** 任务真走到了才结算挂账的自主需求：降需求并标脏落盘（活干完才记功） */
        private void settleNeedSuccess(VillagerState state) {
            if (state == null) return;
            String need = state.consumePendingNeedSatisfy();
            if (need != null) {
                state.needs().satisfy(need, NeedsState.SATISFY_ON_ACTION);
                plugin.getMemoryStore().markDirty(state.getUniqueId());
            }
        }

        /** 任务没走成：挂账作废（不降需求），失败地点记账，自主选点短期内避开这片 */
        private void settleNeedFailure(VillagerState state, Location target) {
            if (state == null) return;
            state.consumePendingNeedSatisfy();
            state.recordFailedMoveTarget(target);
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
            // 自研代驾先停：不停的话驾驶循环还会继续下令，任务完了人还在走
            if (driver != null) {
                driver.stop();
                driver = null;
            }
            takeoverMode = false;
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
