package com.howard.mcai.bot;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 假人的运行时句柄（不落盘）：档案 + 活体 Player + 方向盘 + 当前活计的草稿纸。
 * BotService 持有全部 BotAgent；玩家离线/假人被删时 player 为 null，档案还在。
 */
public final class BotAgent {

    public enum Mode {
        IDLE,        // 待命（站着，偶尔打招呼）
        FOLLOW,      // 跟随某个玩家
        GOTO,        // 前往某点（通用移动，handle 跟进）
        WORK,        // 干活中（具体活看 job）
        RETURN_BASE, // 回据点（到了转 DEPOSIT_REST 或 IDLE）
        DEPOSIT,     // 正在往箱子里存货
        BUILD,       // 盖房中
        REST,        // 夜里休息（据点床边或原地）
        FLEE         // 逃命中（低血+有威胁）
    }

    public enum Job { NONE, FISH, CHOP, BREAK, MINE, GUARD }

    public final BotState state;
    public final BotDriver driver;

    public Player player;                 // 活体（可能为 null）
    public Mode mode = Mode.IDLE;
    public Job job = Job.NONE;

    /** 移动句柄（driver.goTo 的返回），大脑轮询完成/失败。 */
    public BotDriver.Handle handle;
    /** 通用目标点（GOTO/RETURN/WORK 的站位等）。 */
    public Location target;
    /** 跟随的玩家。 */
    public UUID followUuid;
    /** 干活的阶段号（各 Job 自己定义）。 */
    public int phase;
    /** 干活计时（毫秒时间戳：阶段截止/下次动作）。 */
    public long phaseUntil;
    public long nextActAt;
    /** 挖矿方向（单位向量分量，卡迪尔方向）。 */
    public int mineDx, mineDz;
    /** 挖矿已前进的步数 / 计划步数。 */
    public int mineSteps, minePlan;
    /** 挖矿入口（回程传送兜底用）。 */
    public Location mineEntry;
    /** 建房原点（小屋西南角地面）与已放块数。 */
    public Location buildOrigin;
    public int buildIndex;
    /** 当前交战目标。 */
    public UUID combatTarget;
    /** 上次打招呼/上次喊话（节流）。 */
    public long lastGreetAt, lastShoutAt;
    /** 正在对话的玩家 + 最近对话时间（聊天路由续接用）。 */
    public UUID chatPartner;
    public long lastChatAt;
    /** 右键对话锁定：锁定对象 + 到期时间（毫秒）。锁定中聊天直达、不用贴脸。 */
    public UUID convPartner;
    public long convUntil;
    /** 上次 look 目标与时间（lookAt 降频用：同目标短时间不重发，防控制台刷屏）。 */
    public double lastLookX, lastLookY, lastLookZ;
    public long lastLookAt;
    /** 模型请求在途标记（防并发刷屏）。 */
    public boolean llmBusy;
    /** 上次记死亡的时间（PlayerDeath/EntityDeath 双通道去重）。 */
    public long deadRecordedAt;
    /** 预期消失（插件遣散/已记死亡）：认领扫描的"消失按死亡记账"跳过他。 */
    public boolean expectGone;
    /** 背包满已提醒过（回满再提醒）。 */
    public boolean fullWarned;
    // ===== v0.8.0 活气/自主（不落盘） =====
    /** 上次溜达/张望/伸懒腰/去捡东西的时间（节流）。 */
    public long lastWanderAt, lastGlanceAt, lastStretchAt, lastPickupAt;
    /** 下次可以自发找活干的时间（冷却）。 */
    public long nextSelfWorkAt;
    /** 正在自发干活（收尾不喊派活台词，干完尝试存箱子）。 */
    public boolean selfWork;
    /** 自发干活的截止时间（钓鱼的内部计时更长，用这个封顶）。 */
    public long selfWorkUntil;
    /** 刚干完的活一句话（给聊天简报用，如"刚砍了会儿树"）。 */
    public String lastWorkReport = "";
    public long lastWorkAt;
    /** 上次打招呼用的词库下标（防连着说同一句）。 */
    public int lastGreetIdx = -1;
    /** 正在去捡掉落物（gather 流程借 handle 赶路中）。 */
    public boolean pickupRun;

    public BotAgent(BotState state, BotDriver driver) {
        this.state = state;
        this.driver = driver;
    }

    public boolean alive() { return player != null && player.isValid() && !player.isDead(); }

    public Location loc() { return player == null ? null : player.getLocation(); }

    /** 停下手头一切（动作命令 + 方向盘 + 模式回待命）。 */
    public void haltAll(BotService svc) {
        if (player != null) svc.dispatchAction(state.accountName, "stop");
        if (driver != null) driver.stop(player);
        mode = Mode.IDLE;
        job = Job.NONE;
        handle = null;
        combatTarget = null;
        followUuid = null;
    }
}
