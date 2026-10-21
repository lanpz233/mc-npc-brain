package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.ToolRegistry;
import com.howard.mcai.tool.ToolRequest;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 适配层只做名字归一与请求包装：
 * wire 名（下划线）和规范名（点号）必须都能找到同一个工具，未知名字不能变成请求。
 */
class LlmToolAdapterTest {

    @Test
    void resolvesWireNameToCanonicalTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("npc.move_to", NpcTool.Category.ACTION));
        LlmToolAdapter adapter = new LlmToolAdapter(registry);

        JsonObject args = new JsonObject();
        ToolRequest request = adapter.toRequest(new ToolCall("npc_move_to", args),
                CallerType.DIALOG_MODEL, "steve", UUID.randomUUID(), UUID.randomUUID());

        assertNotNull(request);
        assertEquals("npc.move_to", request.tool());
        assertEquals(CallerType.DIALOG_MODEL, request.callerType());
        assertEquals(ToolRequest.PROTOCOL_VERSION, request.protocol());
    }

    @Test
    void returnsNullForUnknownTool() {
        LlmToolAdapter adapter = new LlmToolAdapter(new ToolRegistry());

        assertNull(adapter.toRequest(new ToolCall("npc.fly", new JsonObject()),
                CallerType.AUTONOMY, "autonomy", UUID.randomUUID(), null));
        assertNull(adapter.toRequest(null, CallerType.AUTONOMY, "autonomy",
                UUID.randomUUID(), null));
    }

    @Test
    void recognisesBothNameForms() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("npc.status.get", NpcTool.Category.QUERY));
        LlmToolAdapter adapter = new LlmToolAdapter(registry);

        assertEquals(true, adapter.isRegisteredTool("npc.status.get"));
        assertEquals(true, adapter.isRegisteredTool("npc_status_get"));
        assertEquals(false, adapter.isRegisteredTool("npc.status.set"));
    }

    @Test
    void dialogSchemasExposeOnlyQueryTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("npc.status.get", NpcTool.Category.QUERY));
        registry.register(new StubTool("npc.move_to", NpcTool.Category.ACTION));
        LlmToolAdapter adapter = new LlmToolAdapter(registry);

        List<Map<String, Object>> schemas = adapter.dialogSchemas();

        assertEquals(1, schemas.size());
        Map<?, ?> function = (Map<?, ?>) schemas.get(0).get("function");
        assertEquals("npc_status_get", function.get("name"));
    }

    /** 测试替身：不访问 Bukkit，只要名字与类别 */
    private static final class StubTool implements NpcTool {

        private final String name;
        private final Category category;

        private StubTool(String name, Category category) {
            this.name = name;
            this.category = category;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public Category category() {
            return category;
        }

        @Override
        public Risk risk() {
            return Risk.LOW;
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.empty();
        }

        @Override
        public ToolResult execute(ToolContext context, JsonObject arguments) {
            return ToolResult.completed(new JsonObject());
        }
    }
}
