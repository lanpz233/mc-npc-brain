package com.howard.mcai.planner;

import com.google.gson.JsonObject;
import com.howard.mcai.mob.NeedsState;

import java.util.List;
import java.util.Random;

/**
 * 自主行动决策器（纯插件，v0.1.4 起取代 1B 行为模型）。
 *
 * <p>以前这一步是把插件生成好的候选动作发给 MiniCPM-1B，让它回一个序号——
 * 候选是插件算的、合法性是插件验的、执行是插件做的，模型只当了个掷骰子的。
 * 现在改由本类按可解释的规则打分：需求有多急、目标有多远、手头有没有活、
 * 村民的性格（开放度），最后掺一点随机扰动免得全村行动像复制粘贴。
 * 纯计算、无 IO、无 Bukkit 依赖，可完整单测。</p>
 */
public final class AutonomyDecider {

    private AutonomyDecider() {
    }

    /**
     * 候选动作：由服务端预先生成并校验过的可执行选项。
     *
     * @param action   工具规范名；"idle" 表示什么都不做
     * @param label    一行人话说明（日志/调试用）
     * @param args     已通过 schema 校验的参数；idle 用空对象
     * @param distance move_to 候选的目标距离（格）；其他候选传 0
     */
    public record Candidate(String action, String label, JsonObject args, double distance) {
    }

    /**
     * 决策结果。
     *
     * @param index  选中候选的序号；-1 = 没有候选
     * @param scores 每个候选的最终得分（含扰动），与候选同序，日志/测试用
     */
    public record Decision(int index, double[] scores) {
        /** 选中的是 idle（或没有候选） */
        public boolean idle() {
            return index < 0;
        }
    }

    /**
     * 在候选里选一个。
     *
     * @param dominant       当前主导需求（NeedsState 的键）
     * @param urgency        主导需求当前数值（0–100，越大越急）
     * @param openness       村民开放度（25–95，影响无聊/好奇时愿不愿意出门）
     * @param hasActiveTask  手头是否有进行中的任务
     */
    public static Decision decide(String dominant, double urgency, int openness,
                                  boolean hasActiveTask,
                                  List<Candidate> candidates, Random rng) {
        if (candidates == null || candidates.isEmpty()) {
            return new Decision(-1, new double[0]);
        }
        double[] scores = new double[candidates.size()];
        int best = -1;
        for (int i = 0; i < candidates.size(); i++) {
            double s = score(dominant, urgency, openness, hasActiveTask, candidates.get(i));
            s += rng.nextDouble() * 5.0;  // 扰动：同分不锁死，行为有一点生活气
            scores[i] = s;
            if (best < 0 || s > scores[best]) best = i;
        }
        Candidate picked = candidates.get(best);
        if (picked.action() == null || "idle".equalsIgnoreCase(picked.action())) {
            return new Decision(-1, scores);
        }
        return new Decision(best, scores);
    }

    /** 单个候选的基础分（不含扰动）。规则都写在这里，一眼可查。 */
    static double score(String dominant, double urgency, int openness,
                        boolean hasActiveTask, Candidate c) {
        String action = c.action() == null ? "idle" : c.action();
        boolean survival = NeedsState.HUNGER.equals(dominant) || NeedsState.ENERGY.equals(dominant);
        return switch (action) {
            case "idle" -> {
                double s = 12.0;
                if (hasActiveTask) s += 25.0;      // 有活干着就别打断
                if (urgency >= 90.0) s -= 18.0;    // 急到冒烟还发呆不像话
                yield s;
            }
            case "npc.move_to" -> {
                double s = 26.0 + urgency * 0.45;  // 越急越该动身
                if (survival || NeedsState.SOCIAL.equals(dominant)) s += 8.0;
                if (NeedsState.BOREDOM.equals(dominant) || NeedsState.CURIOSITY.equals(dominant)) {
                    s += (openness - 55) * 0.25;   // 爱动的更愿意出门，宅的更倾向待着
                }
                s -= Math.min(Math.max(c.distance(), 0.0), 24.0) * 0.6;  // 近的优先
                yield s;
            }
            case "world.observe.entities" -> {
                double s = 16.0;
                if (NeedsState.CURIOSITY.equals(dominant)) s += 14.0;
                if (NeedsState.BOREDOM.equals(dominant)) s += 4.0;
                if (hasActiveTask) s -= 6.0;
                yield s;
            }
            case "npc.task.cancel" -> {
                double s = 4.0;                    // 没事别撂挑子
                if (survival) s += 30.0;           // 饿晕/困死之前先停手头活
                if (urgency >= 90.0) s += 10.0;
                yield s;
            }
            default -> 0.0;
        };
    }
}
