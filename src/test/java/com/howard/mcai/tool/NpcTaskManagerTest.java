package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.ToolContext.CallerType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcTaskManagerTest {

    private static final UUID NPC = UUID.randomUUID();

    private static ToolContext context() {
        return new ToolContext(CallerType.INTERNAL, "test", NPC, null, "req-1");
    }

    @Test
    void runsAndCompletesTask() {
        ControlledAction action = new ControlledAction();
        NpcTaskManager manager = new NpcTaskManager();
        NpcTask task = manager.createAndStart(
                FakeTool.sustained("test.move", action, 60_000L), context(), new JsonObject());

        assertNotNull(task);
        assertEquals(ToolResult.Status.RUNNING, task.state());

        manager.tick(System.currentTimeMillis());
        assertEquals(1, action.ticks);

        action.completeOnTick = true;
        manager.tick(System.currentTimeMillis());
        assertEquals(ToolResult.Status.COMPLETED, task.state());
    }

    @Test
    void cancelsTaskAndNotifiesAction() {
        ControlledAction action = new ControlledAction();
        NpcTaskManager manager = new NpcTaskManager();
        NpcTask task = manager.createAndStart(
                FakeTool.sustained("test.move", action, 60_000L), context(), new JsonObject());

        assertTrue(manager.cancel(task.id(), "测试取消"));
        assertEquals(ToolResult.Status.CANCELLED, task.state());
        assertTrue(action.cancelled);
        assertEquals(0, manager.cancelForNpc(NPC, "再次取消"));
    }

    @Test
    void timesOutAfterConfiguredTimeout() {
        ControlledAction action = new ControlledAction();
        NpcTaskManager manager = new NpcTaskManager();
        NpcTask task = manager.createAndStart(
                FakeTool.sustained("test.move", action, 1_000L), context(), new JsonObject());

        manager.tick(System.currentTimeMillis() + 1_500L);
        assertEquals(ToolResult.Status.TIMEOUT, task.state());
        assertTrue(action.cancelled);
    }

    @Test
    void rejectsConcurrentTaskForSameNpc() {
        NpcTaskManager manager = new NpcTaskManager();
        NpcTask first = manager.createAndStart(
                FakeTool.sustained("test.move", new ControlledAction(), 60_000L), context(), new JsonObject());
        NpcTask second = manager.createAndStart(
                FakeTool.sustained("test.move", new ControlledAction(), 60_000L), context(), new JsonObject());

        assertNotNull(first);
        assertNull(second);
        assertEquals(first.id(), manager.activeFor(NPC).id());
    }

    @Test
    void removesFinishedTasksAfterRetention() {
        NpcTaskManager manager = new NpcTaskManager(30_000L);
        NpcTask task = manager.createAndStart(
                FakeTool.sustained("test.move", new ControlledAction(), 60_000L), context(), new JsonObject());
        manager.cancel(task.id(), "完成测试");

        long finishedAt = task.finishedAtMs();
        manager.tick(finishedAt + 31_000L);
        assertNull(manager.get(task.id()));
    }

    private static final class ControlledAction implements NpcTaskAction {
        int ticks;
        boolean cancelled;
        boolean completeOnTick;
        private final JsonObject data = new JsonObject();

        @Override
        public void start(NpcTask task, ToolContext context, JsonObject arguments) {
        }

        @Override
        public void tick(NpcTask task, ToolContext context, JsonObject arguments) {
            ticks++;
            if (completeOnTick) {
                task.complete(System.currentTimeMillis(), data);
            }
        }

        @Override
        public void onCancel(NpcTask task) {
            cancelled = true;
        }
    }
}
