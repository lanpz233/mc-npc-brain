package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import com.howard.mcai.security.NpcRank;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.tools.NpcTaskCancelTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDispatcherTest {

    private final UUID npc = UUID.randomUUID();
    private final AtomicBoolean conversing = new AtomicBoolean(false);

    private ToolRegistry registry;
    private NpcTaskManager tasks;
    private ToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        tasks = new NpcTaskManager();
        ToolPolicyService policy = new ToolPolicyService(
                id -> id.equals(npc),
                id -> conversing.get(),
                (npcId, playerUuid) -> NpcRank.CIVILIAN);
        dispatcher = new ToolDispatcher(registry, policy, tasks, null);
    }

    @Test
    void rejectsUnknownTool() {
        ToolResult result = dispatcher.dispatchSync(request("missing.tool", new JsonObject()));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.UNKNOWN_TOOL.code(), result.errorCode());
    }

    @Test
    void rejectsInvalidArgumentsWithoutExecutingTool() {
        FakeTool tool = FakeTool.withSchema("test.args", ToolSchema.builder()
                .param("value", ToolSchema.Param.string("值"), true)
                .build());
        registry.register(tool);

        ToolResult result = dispatcher.dispatchSync(request("test.args", new JsonObject()));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENT.code(), result.errorCode());
        assertEquals(0, tool.executions.get());
    }

    @Test
    void rejectsCallerOutsideWhitelist() {
        registry.register(FakeTool.restricted("test.internal", CallerType.INTERNAL));

        ToolRequest modelRequest = ToolRequest.create(
                CallerType.DIALOG_MODEL, "qwen", npc, null, "test.internal", new JsonObject());
        ToolResult result = dispatcher.dispatchSync(modelRequest);

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.TOOL_NOT_ALLOWED.code(), result.errorCode());
    }

    @Test
    void rejectsUnknownNpc() {
        registry.register(FakeTool.query("test.echo"));

        ToolRequest request = ToolRequest.create(
                CallerType.INTERNAL, "test", UUID.randomUUID(), null, "test.echo", new JsonObject());
        ToolResult result = dispatcher.dispatchSync(request);

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.NPC_NOT_FOUND.code(), result.errorCode());
    }

    @Test
    void rejectsToolRequiringPlayerWhenNoPlayerContext() {
        NpcTool tool = new FakeTool("test.player", ToolSchema.empty(), null, null, 30_000L) {
            @Override
            public boolean requiresPlayer() {
                return true;
            }
        };
        registry.register(tool);

        ToolResult result = dispatcher.dispatchSync(request("test.player", new JsonObject()));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.INVALID_REQUEST.code(), result.errorCode());
    }

    @Test
    void rejectsConversationBlockedTool() {
        NpcTool tool = new FakeTool("test.locked", ToolSchema.empty(), null, null, 30_000L) {
            @Override
            public boolean allowedWhileConversing() {
                return false;
            }
        };
        registry.register(tool);
        conversing.set(true);

        ToolResult result = dispatcher.dispatchSync(request("test.locked", new JsonObject()));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.TOOL_NOT_ALLOWED.code(), result.errorCode());
    }

    @Test
    void duplicateRequestDoesNotExecuteTwice() {
        FakeTool tool = FakeTool.query("test.echo");
        registry.register(tool);
        ToolRequest request = new ToolRequest(
                ToolRequest.PROTOCOL_VERSION, "same-request", CallerType.INTERNAL,
                "test", npc, null, "test.echo", new JsonObject());

        ToolResult first = dispatcher.dispatchSync(request);
        ToolResult second = dispatcher.dispatchSync(request);

        assertEquals(ToolResult.Status.COMPLETED, first.status());
        assertEquals(ToolResult.Status.DUPLICATE, second.status());
        assertEquals(1, tool.executions.get());
        assertEquals(first.data(), second.data());
    }

    @Test
    void sustainedActionReturnsTaskAndCanBeCancelled() {
        ControlledAction action = new ControlledAction();
        registry.register(FakeTool.sustained("test.move", action, 60_000L));
        registry.register(new NpcTaskCancelTool(tasks));

        ToolResult accepted = dispatcher.dispatchSync(request("test.move", new JsonObject()));
        assertEquals(ToolResult.Status.ACCEPTED, accepted.status());
        assertNotNull(accepted.taskId());
        assertEquals(ToolResult.Status.RUNNING, tasks.get(accepted.taskId()).state());

        JsonObject args = new JsonObject();
        args.addProperty("task_id", accepted.taskId());
        ToolResult cancelled = dispatcher.dispatchSync(request("npc.task.cancel", args));

        assertEquals(ToolResult.Status.COMPLETED, cancelled.status());
        assertTrue(cancelled.data().get("cancelled").getAsBoolean());
        assertEquals(ToolResult.Status.CANCELLED, tasks.get(accepted.taskId()).state());
        assertTrue(action.cancelled);
    }

    @Test
    void asyncDispatchWorksWithoutMainThreadExecutor() {
        registry.register(FakeTool.query("test.echo"));

        ToolResult result = dispatcher.dispatchAsync(request("test.echo", new JsonObject())).join();

        assertEquals(ToolResult.Status.COMPLETED, result.status());
    }

    @Test
    void wrongProtocolVersionIsRejected() {
        registry.register(FakeTool.query("test.echo"));
        ToolRequest request = new ToolRequest(
                "npc-tool/v0", "r1", CallerType.INTERNAL, "test", npc, null,
                "test.echo", new JsonObject());

        ToolResult result = dispatcher.dispatchSync(request);

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(ToolResult.ErrorCode.INVALID_REQUEST.code(), result.errorCode());
        assertNull(result.taskId());
    }

    private ToolRequest request(String tool, JsonObject arguments) {
        return ToolRequest.create(CallerType.INTERNAL, "test", npc, null, tool, arguments);
    }

    private static final class ControlledAction implements NpcTaskAction {
        boolean cancelled;

        @Override
        public void start(NpcTask task, ToolContext context, JsonObject arguments) {
        }

        @Override
        public void tick(NpcTask task, ToolContext context, JsonObject arguments) {
        }

        @Override
        public void onCancel(NpcTask task) {
            cancelled = true;
        }
    }
}
