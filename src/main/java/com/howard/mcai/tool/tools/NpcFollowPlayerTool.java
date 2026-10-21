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
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;

/**
 * npc.follow_player —— 持续跟随一名玩家（统一柜版，任务制）。
 * <p>
 * 替代老后门的「朝玩家推两步就完事」：老实现只给一脚速度 + 1 秒后再推一步，
 * 根本跟不住人。现在是正经的持续任务：原版寻路跟着走、保持约 3 格距离、
 * 玩家走远自动追、进对话自动停、跟丢（&gt;64 格）/玩家离线/跨世界自动结束，
 * 进度可经 npc.task.get 查询、可取消。默认 5 分钟超时收尾，不会无限跟下去。
 * <p>
 * 别名 {@code follow_player} 桥接老工具名。与 move_to 一致：对话期间禁止启动
 * （站立不动约束），任务运行中 NPC 进入对话也会被 tick 取消。
 */
public final class NpcFollowPlayerTool implements NpcTool {

    /** 保持的跟随距离：比这个远就走过去，比这个近就停下等 */
    private static final double FOLLOW_DISTANCE = 3.0;
    /** 超过这个距离判跟丢（玩家跑太快/传送走了） */
    private static final double LOST_DISTANCE = 64.0;
    /** 重下寻路指令的间隔（玩家一直在动，目标点要不断刷新） */
    private static final long REPATH_INTERVAL_MS = 2_000L;
    /** 跟随速度倍率（略慢于玩家步行，避免贴脸） */
    private static final double FOLLOW_SPEED = 0.9;

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
        return "让 NPC 持续跟随一名玩家（保持约 3 格距离）。返回任务 ID，跟随会一直持续到"
                + "玩家离线、距离过远、进入对话或任务超时（5 分钟）。";
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
        return 300_000L;
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
        private long lastRepathMs;
        private UUID targetUuid;

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
            Villager villager = state.entity();
            // 与 move_to 同口径：寻路必须由原版 AI 驱动，先恢复 AI 并清掉残余动量
            villager.setAI(true);
            villager.setTarget(null);
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
            lastRepathMs = 0L;
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
            // 对话状态必须站立不动：进入对话立即让路（与 move_to 一致）
            if (state.interactingPlayer() != null) {
                stop(state);
                task.cancel(System.currentTimeMillis(), "NPC 进入对话，跟随已停止");
                return;
            }
            Player target = targetUuid != null ? Bukkit.getPlayer(targetUuid) : null;
            if (target == null || !target.isOnline()) {
                stop(state);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_REQUEST,
                        "跟随的玩家已离线");
                return;
            }
            Villager villager = state.entity();
            Location mine = villager.getLocation();
            Location theirs = target.getLocation();
            if (mine.getWorld() == null || !mine.getWorld().equals(theirs.getWorld())) {
                stop(state);
                task.fail(System.currentTimeMillis(), ToolResult.ErrorCode.INVALID_REQUEST,
                        "跟随的玩家已不在同一世界");
                return;
            }
            double dx = theirs.getX() - mine.getX();
            double dz = theirs.getZ() - mine.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            long now = System.currentTimeMillis();

            JsonObject data = new JsonObject();
            data.addProperty("following", target.getName());
            data.addProperty("distance", Math.round(dist * 10.0) / 10.0);
            task.updateData(data);

            if (dist > LOST_DISTANCE) {
                stop(state);
                task.fail(now, ToolResult.ErrorCode.MOVE_STUCK,
                        "跟丢了：玩家已跑到 " + (int) dist + " 格外");
                return;
            }
            if (dist <= FOLLOW_DISTANCE) {
                // 到位就停下等，不贴脸；玩家再走远 tick 会重新起步
                if (villager.getPathfinder().hasPath()) {
                    villager.getPathfinder().stopPathfinding();
                }
                return;
            }
            if (!villager.getPathfinder().hasPath() || now - lastRepathMs >= REPATH_INTERVAL_MS) {
                villager.getPathfinder().moveTo(theirs, FOLLOW_SPEED);
                lastRepathMs = now;
            }
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
            Villager villager = state.entity();
            if (villager == null || !villager.isValid()) return;
            villager.getPathfinder().stopPathfinding();
            villager.setVelocity(new Vector(0, villager.getVelocity().getY(), 0));
        }
    }
}
