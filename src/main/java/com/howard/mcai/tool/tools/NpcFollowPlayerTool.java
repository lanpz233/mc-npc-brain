package com.howard.mcai.tool.tools;

import com.google.gson.JsonObject;
import com.howard.mcai.McaiPlugin;
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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;

/**
 * npc.follow_player —— 持续跟随一名玩家（统一柜版，任务制）。
 * <p>
 * v0.5.0 起走自研寻路（NavDriver 分段领路、会开门/绕整栋房子），保持约 3 格距离；
 * 玩家跑太远且没人看着他时，允许“抄近道”瞬移追上（服主拍板）；对话期间暂停
 * （站着聊天，聊完接着跟，不再一进对话就散伙——取消跟随在 F 界面/聊天喊停）；
 * 玩家离线/跨世界自动结束。超时放宽到 3 小时，跟随主要靠玩家主动取消。
 * <p>
 * 别名 {@code follow_player} 桥接老工具名。对话期间禁止启动（站立不动约束）；
 * 插件跟随服务（聊天认话/F 界面按钮）会先结束对话再启动本任务。
 */
public final class NpcFollowPlayerTool implements NpcTool {

    /** 保持的跟随距离：比这个远就走过去，比这个近就停下等 */
    private static final double FOLLOW_DISTANCE = 3.0;
    /** 跟随速度倍率（略慢于玩家步行，避免贴脸） */
    private static final double FOLLOW_SPEED = 0.9;
    /** 追赶速度：离玩家远了小跑追 */
    private static final double CATCHUP_SPEED = 1.15;
    /** 瞬移追赶的触发距离：离这么远、且没人看着他，才许抄近道 */
    private static final double TELEPORT_DISTANCE = 24.0;
    /** 瞬移追赶冷却，防止连环瞬移 */
    private static final long TELEPORT_COOLDOWN_MS = 5_000L;
    /** 重新规划的节流：玩家挪出计划目标这么远、或计划过期，就重算路线 */
    private static final double REPLAN_GOAL_DRIFT = 5.0;
    private static final long REPLAN_MAX_AGE_MS = 6_000L;

    private final McaiPlugin plugin;

    public NpcFollowPlayerTool(McaiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "npc.follow_player";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("follow_player");
    }

    @Override
    public String description() {
        return "让 NPC 持续跟随一名玩家（保持约 3 格距离，走自研寻路）。返回任务 ID，跟随会一直"
                + "持续到玩家离线、跨世界、在 F 界面取消或任务超时（3 小时）。";
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
        // 跟随是玩家主动开关的长期状态（取消在 F 界面/聊天喊停），超时只作兜底
        return 3 * 60 * 60 * 1000L;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.builder()
                .param("player_id", ToolSchema.Param.string("目标玩家 id（如 player_xxx）或名字；省略时跟随当前互动玩家").maxLength(64))
                .param("message", ToolSchema.Param.string("跟随时说的中文台词（仅对话模型会带，任务本身不使用）").maxLength(120))
                .build();
    }

    @Override
    public NpcTaskAction taskAction() {
        return new FollowAction(plugin);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        // 持续行动必须创建任务；Dispatcher 不会把 sustained 工具走到这里。
        return ToolResult.rejected(ToolResult.ErrorCode.INVALID_REQUEST,
                "npc.follow_player 是持续行动工具，必须通过任务系统执行");
    }

    /** 解析跟随目标：优先 player_id 参数（名字/UUID 前缀），否则用可信上下文里的玩家 */
    static Player resolveTarget(ToolContext context, JsonObject arguments) {
        String id = ToolArgs.string(arguments, "player_id", null);
        if (id != null && !id.isBlank()) {
            String t = id.trim();
            if (t.startsWith("player_")) t = t.substring("player_".length());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getUniqueId().toString().startsWith(t) || p.getName().equalsIgnoreCase(t)) {
                    return p;
                }
            }
            return null;
        }
        return context.playerUuid() != null ? Bukkit.getPlayer(context.playerUuid()) : null;
    }

    private static final class FollowAction implements NpcTaskAction {

        private final McaiPlugin plugin;
        private UUID targetUuid;
        /** 自研寻路执行器与当前计划目标（玩家挪远了就重规划） */
        private NavDriver driver;
        private Location planGoal;
        private long lastPlanMs;
        private long lastTeleportMs;

        private FollowAction(McaiPlugin plugin) {
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
            Player target = resolveTarget(context, arguments);
            if (target == null || !target.isOnline()) {
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_REQUEST,
                        "没有可跟随的玩家（不在线或未指定）");
                return;
            }
            targetUuid = target.getUniqueId();
            state.setFollowingPlayer(targetUuid);
            Villager villager = state.entity();
            // 全自研驾驶（v0.5.1）：AI 先关掉，身体随后归 NavDriver 开，防原版大脑抢方向抽风
            villager.setAI(false);
            villager.setTarget(null);
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
            driver = null;
            planGoal = null;
            JsonObject data = new JsonObject();
            data.addProperty("following", target.getName());
            task.updateData(data);
            plugin.getLogger().info("[task] npc.follow_player 开始跟随: npc=" + task.npcId()
                    + " → " + target.getName());
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
            // 对话中：站着聊，跟随暂停但不散伙（v0.5.0 服主定：取消跟随在 F 界面/聊天喊停，
            // 不再像旧版一进对话就把跟随掐了）；聊完自动接着跟
            if (state.interactingPlayer() != null) {
                pauseDriver();
                return;
            }
            Player target = targetUuid != null ? Bukkit.getPlayer(targetUuid) : null;
            if (target == null || !target.isOnline()) {
                stop(state);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_REQUEST,
                        "跟随的玩家已离线");
                return;
            }
            Location mine = villager.getLocation();
            Location theirs = target.getLocation();
            if (mine.getWorld() == null || !mine.getWorld().equals(theirs.getWorld())) {
                stop(state);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_REQUEST,
                        "跟随的玩家已不在同一世界");
                return;
            }
            double dist = horizontal(mine, theirs);
            long now = System.currentTimeMillis();

            JsonObject data = new JsonObject();
            data.addProperty("following", target.getName());
            data.addProperty("distance", Math.round(dist * 10.0) / 10.0);
            task.updateData(data);

            if (dist <= FOLLOW_DISTANCE) {
                // 到位就停下等，不贴脸；玩家再走远 tick 会重新起步
                pauseDriver();
                return;
            }

            // 抄近道（服主拍板）：离得太远、且没人看着他时，瞬移到玩家附近再接着跟
            if (dist >= TELEPORT_DISTANCE && now - lastTeleportMs >= TELEPORT_COOLDOWN_MS
                    && unseen(villager, target)) {
                if (teleportBehind(villager, target)) {
                    lastTeleportMs = now;
                    dropDriver();
                    plugin.getLogger().info("[task] npc.follow_player 瞬移追赶: npc=" + task.npcId()
                            + " 原距离 " + (int) dist + " 格");
                    return;
                }
            }

            // 需要（重）规划：没计划 / 玩家挪出计划目标太远 / 计划太旧
            boolean needPlan = driver == null || planGoal == null
                    || horizontal(planGoal, theirs) >= REPLAN_GOAL_DRIFT
                    || now - lastPlanMs >= REPLAN_MAX_AGE_MS;
            if (needPlan) {
                NavService.Plan plan = plugin.getNavService() != null
                        ? plugin.getNavService().plan(task.npcId(), mine, theirs) : null;
                if (plan == null) plan = NavService.direct(theirs);   // 规划不出先直着追，走一段再重算
                dropDriver();
                driver = new NavDriver(plugin, plan);
                driver.start(villager, FOLLOW_SPEED);
                planGoal = theirs.clone();
                lastPlanMs = now;
            }
            if (driver != null) {
                driver.setSpeed(dist > 10 ? CATCHUP_SPEED : FOLLOW_SPEED);
                // 暂停过（对话中/到位等待）就恢复驾驶；start 幂等，驾驶中调用无副作用
                if (driver.status() == NavDriver.Status.RUNNING) {
                    driver.start(villager, FOLLOW_SPEED);
                } else {
                    // 走完（多半是半路路径）或卡住：丢掉计划，下个 tick 重新规划
                    dropDriver();
                }
            }
        }

        /** 暂停当前驾驶员（对话中/到位等待）：停循环刹车，人留在原地 */
        private void pauseDriver() {
            if (driver != null) driver.pause();
        }

        /** 丢掉当前驾驶员（换计划/瞬移/收尾）：停循环刹车并清计划目标 */
        private void dropDriver() {
            if (driver != null) {
                driver.stop();
                driver = null;
            }
            planGoal = null;
        }

        /** 他是不是没人盯着：被跟的玩家视线之外（或远到看不清），且 14 格内没有别的玩家在场 */
        private boolean unseen(Villager villager, Player target) {
            Location vl = villager.getLocation();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(target) || !p.getWorld().equals(vl.getWorld())) continue;
                if (p.getLocation().distanceSquared(vl) <= 14 * 14) return false;
            }
            Location pl = target.getLocation();
            double dx = vl.getX() - pl.getX();
            double dz = vl.getZ() - pl.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist >= 48) return true;
            Vector look = pl.getDirection();
            double lx = look.getX(), lz = look.getZ();
            double ll = Math.sqrt(lx * lx + lz * lz);
            if (ll < 0.001 || dist < 0.001) return true;
            double dot = (dx * lx + dz * lz) / (dist * ll);
            return dot < 0.42;   // 约 65° 视锥之外 = 他看不见
        }

        /** 瞬移到玩家身后/身侧的安全地面；找不到安全落点就放弃（继续用跑的） */
        private boolean teleportBehind(Villager villager, Player target) {
            Location pl = target.getLocation();
            Vector facing = pl.getDirection();
            facing.setY(0);
            if (facing.lengthSquared() < 0.001) facing = new Vector(0, 0, 1);
            facing.normalize();
            Vector side = new Vector(-facing.getZ(), 0, facing.getX());
            Location[] candidates = {
                    pl.clone().add(facing.clone().multiply(-2.5)),
                    pl.clone().add(side.clone().multiply(2.5)),
                    pl.clone().add(side.clone().multiply(-2.5)),
                    pl.clone(),
            };
            for (Location c : candidates) {
                Location ground = findGround(c);
                if (ground != null) {
                    villager.teleport(ground);
                    villager.setVelocity(new Vector(0, 0, 0));
                    return true;
                }
            }
            return false;
        }

        /** 在候选点附近上下找安全落点：实心地面（非岩浆类）+ 脚/头两格空 + 不落水 */
        private Location findGround(Location around) {
            org.bukkit.World w = around.getWorld();
            if (w == null) return null;
            int bx = around.getBlockX(), bz = around.getBlockZ();
            int top = Math.min(w.getMaxHeight() - 2, around.getBlockY() + 3);
            int bottom = Math.max(w.getMinHeight(), around.getBlockY() - 8);
            for (int y = top; y >= bottom; y--) {
                Material below = w.getBlockAt(bx, y - 1, bz).getType();
                Material feet = w.getBlockAt(bx, y, bz).getType();
                Material head = w.getBlockAt(bx, y + 1, bz).getType();
                if (!below.isSolid()) continue;
                if (below == Material.LAVA || below == Material.MAGMA_BLOCK
                        || below == Material.CACTUS) continue;
                if (feet == Material.LAVA || feet == Material.WATER || head == Material.LAVA) continue;
                if (feet.isSolid() || head.isSolid()) continue;
                return new Location(w, bx + 0.5, y, bz + 0.5);
            }
            return null;
        }

        private static double horizontal(Location a, Location b) {
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            return Math.sqrt(dx * dx + dz * dz);
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

        private void stop(VillagerState state) {
            if (state == null) return;
            dropDriver();
            // 跟随标记与任务同生共死：任何收尾路径（失败/取消/超时）都经这里清掉
            if (targetUuid != null && targetUuid.equals(state.followingPlayer())) {
                state.setFollowingPlayer(null);
            }
            Villager villager = state.entity();
            if (villager == null || !villager.isValid()) return;
            villager.getPathfinder().stopPathfinding();
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
        }
    }
}
