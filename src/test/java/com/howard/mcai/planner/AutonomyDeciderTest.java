package com.howard.mcai.planner;

import com.google.gson.JsonObject;
import com.howard.mcai.mob.NeedsState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyDeciderTest {

    /** 扰动恒为 0 的 Random，让打分结果完全确定 */
    private static final Random NO_JITTER = new Random() {
        @Override
        public double nextDouble() {
            return 0.0;
        }
    };

    private static AutonomyDecider.Candidate idle() {
        return new AutonomyDecider.Candidate("idle", "stay", new JsonObject(), 0);
    }

    private static AutonomyDecider.Candidate move(double distance) {
        return new AutonomyDecider.Candidate("npc.move_to", "walk", new JsonObject(), distance);
    }

    private static AutonomyDecider.Candidate observe() {
        return new AutonomyDecider.Candidate("world.observe.entities", "look", new JsonObject(), 0);
    }

    private static AutonomyDecider.Candidate cancel() {
        return new AutonomyDecider.Candidate("npc.task.cancel", "stop", new JsonObject(), 0);
    }

    @Test
    void hungryAndFreeChoosesMove() {
        var decision = AutonomyDecider.decide(NeedsState.HUNGER, 85, 50, false,
                List.of(idle(), move(8), observe()), NO_JITTER);
        assertEquals(1, decision.index());
    }

    @Test
    void busyVillagerStaysOnTaskWhenNotUrgent() {
        var decision = AutonomyDecider.decide(NeedsState.BOREDOM, 82, 50, true,
                List.of(idle(), observe(), cancel()), NO_JITTER);
        assertTrue(decision.idle());
    }

    @Test
    void starvingVillagerCancelsCurrentTask() {
        var decision = AutonomyDecider.decide(NeedsState.HUNGER, 95, 50, true,
                List.of(idle(), observe(), cancel()), NO_JITTER);
        assertEquals(2, decision.index());
    }

    @Test
    void curiousOpenVillagerPrefersGoingOutOverWatching() {
        var decision = AutonomyDecider.decide(NeedsState.CURIOSITY, 85, 90, false,
                List.of(idle(), move(8), observe()), NO_JITTER);
        assertEquals(1, decision.index());
    }

    @Test
    void nearerMoveTargetScoresHigher() {
        double near = AutonomyDecider.score(NeedsState.BOREDOM, 80, 50, false, move(4));
        double far = AutonomyDecider.score(NeedsState.BOREDOM, 80, 50, false, move(20));
        assertTrue(near > far);
    }

    @Test
    void emptyCandidatesMeanIdle() {
        var decision = AutonomyDecider.decide(NeedsState.HUNGER, 99, 50, false,
                List.of(), NO_JITTER);
        assertTrue(decision.idle());
    }
}
