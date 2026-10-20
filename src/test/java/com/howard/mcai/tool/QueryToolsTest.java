package com.howard.mcai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.tools.NpcInventoryGetTool;
import com.howard.mcai.tool.tools.NpcStatusGetTool;
import com.howard.mcai.tool.tools.NpcTaskCancelTool;
import com.howard.mcai.tool.tools.NpcTaskGetTool;
import com.howard.mcai.tool.tools.WorldObserveEntitiesTool;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryToolsTest {

    private final UUID npc = UUID.randomUUID();
    private final FakeState state = new FakeState();
    private final ToolContext context = new ToolContext(
            CallerType.INTERNAL, "test", npc, UUID.randomUUID(), "req");

    @Test
    void statusReturnsSnapshot() {
        state.status.addProperty("name", "老王");
        ToolResult result = new NpcStatusGetTool(state).execute(context, new JsonObject());

        assertEquals(ToolResult.Status.COMPLETED, result.status());
        assertEquals("老王", result.data().get("name").getAsString());
    }

    @Test
    void missingNpcReturnsNotFound() {
        state.exists = false;

        ToolResult status = new NpcStatusGetTool(state).execute(context, new JsonObject());
        ToolResult inventory = new NpcInventoryGetTool(state).execute(context, new JsonObject());
        ToolResult entities = new WorldObserveEntitiesTool(state).execute(context, new JsonObject());

        assertEquals(ToolResult.ErrorCode.NPC_NOT_FOUND.code(), status.errorCode());
        assertEquals(ToolResult.ErrorCode.NPC_NOT_FOUND.code(), inventory.errorCode());
        assertEquals(ToolResult.ErrorCode.NPC_NOT_FOUND.code(), entities.errorCode());
    }

    @Test
    void observeEntitiesUsesDefaultsAndReportsCount() {
        state.entities.add(new JsonObject());
        state.entities.add(new JsonObject());

        ToolResult result = new WorldObserveEntitiesTool(state).execute(context, new JsonObject());

        assertEquals(ToolResult.Status.COMPLETED, result.status());
        assertEquals(8.0, result.data().get("radius").getAsDouble());
        assertEquals(2, result.data().get("count").getAsInt());
        assertEquals(2, result.data().getAsJsonArray("entities").size());
    }

    @Test
    void taskToolsReadAndCancelRealTaskState() {
        NpcTaskManager manager = new NpcTaskManager();
        NpcTask task = manager.createAndStart(
                FakeTool.sustained("test.move", new NoopAction(), 60_000L),
                context, new JsonObject());

        ToolResult list = new NpcTaskGetTool(manager).execute(context, new JsonObject());
        assertEquals(ToolResult.Status.COMPLETED, list.status());
        assertEquals(1, list.data().get("count").getAsInt());
        assertFalse(list.data().get("active_task_id").isJsonNull());

        JsonObject args = new JsonObject();
        args.addProperty("task_id", task.id());
        ToolResult cancelled = new NpcTaskCancelTool(manager).execute(context, args);

        assertEquals(ToolResult.Status.COMPLETED, cancelled.status());
        assertTrue(cancelled.data().get("cancelled").getAsBoolean());
        assertEquals(ToolResult.Status.CANCELLED, manager.get(task.id()).state());

        ToolResult missing = new NpcTaskGetTool(manager)
                .execute(context, args);
        assertEquals(ToolResult.Status.COMPLETED, missing.status());
        assertEquals("CANCELLED", missing.data().get("state").getAsString());
    }

    @Test
    void taskGetRejectsTaskOfAnotherNpc() {
        NpcTaskManager manager = new NpcTaskManager();
        UUID otherNpc = UUID.randomUUID();
        NpcTask task = manager.createAndStart(
                FakeTool.sustained("test.move", new NoopAction(), 60_000L),
                new ToolContext(CallerType.INTERNAL, "test", otherNpc, null, "req2"),
                new JsonObject());

        JsonObject args = new JsonObject();
        args.addProperty("task_id", task.id());
        ToolResult result = new NpcTaskGetTool(manager).execute(context, args);

        assertEquals(ToolResult.ErrorCode.TASK_NOT_FOUND.code(), result.errorCode());
    }

    private static final class NoopAction implements NpcTaskAction {
        @Override
        public void start(NpcTask task, ToolContext context, JsonObject arguments) {
        }

        @Override
        public void tick(NpcTask task, ToolContext context, JsonObject arguments) {
        }
    }

    private final class FakeState implements GameStateProvider {
        boolean exists = true;
        final JsonObject status = new JsonObject();
        final JsonObject inventory = new JsonObject();
        final JsonArray entities = new JsonArray();

        @Override
        public boolean npcExists(UUID npcId) {
            return exists;
        }

        @Override
        public JsonObject npcStatus(UUID npcId, UUID playerUuid) {
            return exists ? status : null;
        }

        @Override
        public JsonObject npcInventory(UUID npcId) {
            return exists ? inventory : null;
        }

        @Override
        public JsonArray nearbyEntities(UUID npcId, double radius, int limit) {
            return entities;
        }

        @Override
        public JsonObject nearbyBlocks(UUID npcId, double radius, int limit) {
            return exists ? new JsonObject() : null;
        }

        @Override
        public JsonObject environment(UUID npcId) {
            return exists ? new JsonObject() : null;
        }
    }
}
