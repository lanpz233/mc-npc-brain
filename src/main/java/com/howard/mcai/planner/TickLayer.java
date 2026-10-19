package com.howard.mcai.planner;

/**
 * Tick 分层（SPEC 2.5）—— 中央计划器的心跳由多个独立层组成。
 * <p>
 * 每层只定义 {名字, 触发频率, 处理函数}。以后加新系统（经济层、世界事件层、
 * 演化层）只需 registerLayer 注册一层，不重构 tick 主循环。
 *
 * <pre>
 * 当前两层：
 * - SocialLayer        社交层：全村范围，60 秒/轮，只更新关系/记忆/八卦，
 *                      不产生气泡和行为；玩家不在场也照常跑
 * - PresentationLayer  表现层：玩家 10 格内，3 秒/轮，行为 + 气泡 + 对话
 * </pre>
 *
 * 调度：CentralPlanner 每 20 tick（1 秒）心跳一次，本层累计经过的真实 tick，
 * 达到 intervalTicks 才执行一轮 onTick —— 所以 interval 的语义和游戏 tick 一致。
 * 所有层都在 Paper 主线程执行（BukkitRunnable 保证），可直接调 Bukkit API。
 */
public abstract class TickLayer {

    /** 中央计划器心跳间隔（tick）—— 每 20 tick = 1 秒唤醒所有层检查一次 */
    public static final int HEARTBEAT_TICKS = 20;

    /** 层名（日志/诊断用） */
    private final String name;

    /** 触发频率（游戏 tick，如 60 = 3 秒、1200 = 60 秒） */
    private final int intervalTicks;

    /** 距离上次执行累计经过的 tick */
    private int elapsedTicks;

    protected TickLayer(String name, int intervalTicks) {
        this.name = name;
        this.intervalTicks = Math.max(HEARTBEAT_TICKS, intervalTicks);
    }

    public String name() {
        return name;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    /** 中央计划器每心跳调用一次（主线程） */
    public void heartbeat(CentralPlanner planner) {
        elapsedTicks += HEARTBEAT_TICKS;
        if (elapsedTicks >= intervalTicks) {
            // 减去 interval 而不是清零：interval 不是 HEARTBEAT_TICKS 整数倍时，
            // 清零会把余数丢掉，实际执行间隔被系统性拉长（节拍漂移）；保留余数才是准确频率
            elapsedTicks -= intervalTicks;
            onTick(planner);
        }
    }

    /** 本轮实际执行（主线程，elapsed 达到 intervalTicks 时触发） */
    protected abstract void onTick(CentralPlanner planner);
}
