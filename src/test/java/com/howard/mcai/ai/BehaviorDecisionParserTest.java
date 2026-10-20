package com.howard.mcai.ai;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.NpcTool;
import com.howard.mcai.tool.ToolContext;
import com.howard.mcai.tool.ToolContext.CallerType;
import com.howard.mcai.tool.ToolRegistry;
import com.howard.mcai.tool.ToolResult;
import com.howard.mcai.tool.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为模型输出解析：MiniCPM 常见坏格式必须能被收敛成可校验请求，
 * 解析不了的输出要返回可读错误（供上层追问一次），不能静默通过。
 */
class BehaviorDecisionParserTest {

    private final UUID npc = UUID.randomUUID();
    private BehaviorDecisionParser parser;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubMoveTool());
        registry.register(new StubQueryTool());
        parser = new BehaviorDecisionParser(registry);
    }

    @Test
    void parsesCanonicalJson() {
        BehaviorDecisionParser.Outcome outcome = parse(
                "{\"action\":\"npc.move_to\",\"args\":{\"x\":100,\"y\":64,\"z\":120},\"reason\":\"walk\"}");

        assertTrue(outcome.ok());
        assertNotNull(outcome.request());
        assertEquals("npc.move_to", outcome.request().tool());
        assertEquals(100.0, outcome.request().arguments().get("x").getAsDouble(), 0.001);
        assertEquals(CallerType.BEHAVIOR_MODEL, outcome.request().callerType());
    }

    @Test
    void stripsThinkBlockAndMarkdownFence() {
        String raw = "<think>thinking...</think>\n```json\n"
                + "{\"action\":\"npc_status_get\",\"args\":{}}\n```";

        BehaviorDecisionParser.Outcome outcome = parse(raw);

        assertTrue(outcome.ok());
        assertEquals("npc.status.get", outcome.request().tool());
    }

    @Test
    void repairsActionWithInlineLooseArgs() {
        // 实测 MiniCPM 输出：action 的字符串没有闭合，参数直接粘在名字后面，末尾还多一个逗号和引号
        String raw = "{\"action\":\"npc_move_to {x:101,y:64,z:100,speed:1.0},\"";

        BehaviorDecisionParser.Outcome outcome = parse(raw);

        assertTrue(outcome.ok(), "应能修复内联参数，实际错误: " + outcome.error());
        assertEquals("npc.move_to", outcome.request().tool());
        assertEquals(101.0, outcome.request().arguments().get("x").getAsDouble(), 0.001);
        assertEquals(64.0, outcome.request().arguments().get("y").getAsDouble(), 0.001);
    }

    @Test
    void repairsActionWithParenthesizedInlineArgs() {
        // 实测 MiniCPM 输出：工具名后面直接跟圆括号参数，且 args 里混入 reason 字段
        String raw = "{\"action\":\"npc_move_to(x:100.7,y:66.0,z:715.54)\","
                + "\"args\":{\"x\":100.7,\"y\":66.0,\"z\":715.54,\"speed\":1.0,"
                + "\"reason\":\"走到玩家面前以获取信息\"}}";

        BehaviorDecisionParser.Outcome outcome = parse(raw);

        assertTrue(outcome.ok(), "应能解析圆括号内联参数，实际错误: " + outcome.error());
        assertEquals("npc.move_to", outcome.request().tool());
        assertEquals(715.54, outcome.request().arguments().get("z").getAsDouble(), 0.001);
    }

    @Test
    void dropsUnknownExtraArgsWhenKnownOnesAreValid() {
        // 实测 MiniCPM 会照抄任务状态字段：status/progress/error/result
        String raw = "{\"action\":\"npc.move_to\",\"args\":{\"x\":100,\"y\":64,\"z\":120,"
                + "\"status\":\"RUNNING\",\"progress\":0.5,\"error\":null,\"result\":{}}}";

        BehaviorDecisionParser.Outcome outcome = parse(raw);

        assertTrue(outcome.ok(), "多余状态字段应被剔除后接受，实际错误: " + outcome.error());
        assertNull(outcome.request().arguments().get("status"));
        assertEquals(100.0, outcome.request().arguments().get("x").getAsDouble(), 0.001);
    }

    @Test
    void treatsIdleWordsAsNoAction() {
        BehaviorDecisionParser.Outcome outcome = parse(
                "{\"action\":\"idle\",\"args\":{},\"reason\":\"nothing to do\"}");

        assertTrue(outcome.ok());
        assertTrue(outcome.idle());
        assertNull(outcome.request());
    }

    @Test
    void rejectsUnknownActionWithAvailableNames() {
        BehaviorDecisionParser.Outcome outcome = parse("{\"action\":\"npc_fly\",\"args\":{}}");

        assertFalse(outcome.ok());
        assertTrue(outcome.error().contains("未知 action"));
        assertTrue(outcome.error().contains("npc.move_to"));
    }

    @Test
    void rejectsInvalidArguments() {
        // 缺少必填 x/z
        BehaviorDecisionParser.Outcome outcome = parse(
                "{\"action\":\"npc.move_to\",\"args\":{\"y\":64}}");

        assertFalse(outcome.ok());
        assertTrue(outcome.error().contains("参数不合法"));
    }

    @Test
    void resolvesShortToolNameFromSmallModel() {
        // 实测 MiniCPM 会漏掉命名空间，只输出 move_to
        BehaviorDecisionParser.Outcome outcome = parse(
                "{\"action\":\"move_to\",\"args\":{\"x\":100,\"y\":64,\"z\":100}}");

        assertTrue(outcome.ok(), "短名应唯一映射回 npc.move_to，实际错误: " + outcome.error());
        assertEquals("npc.move_to", outcome.request().tool());
    }

    @Test
    void rejectsAmbiguousShortToolName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubMoveTool());
        registry.register(new StubQueryTool());
        // 再加一个后缀同样以 _get 结尾的工具，让裸 get 无法唯一命中
        registry.register(new StubNamedTool("world.observe.get"));
        BehaviorDecisionParser ambiguous = new BehaviorDecisionParser(registry);

        BehaviorDecisionParser.Outcome outcome = ambiguous.parse(
                "{\"action\":\"get\",\"args\":{}}", CallerType.BEHAVIOR_MODEL, "minicpm-act", npc, null);

        assertFalse(outcome.ok());
        assertTrue(outcome.error().contains("未知 action"));
    }

    @Test
    void rejectsEmptyOutput() {
        BehaviorDecisionParser.Outcome outcome = parse("   ");

        assertFalse(outcome.ok());
        assertTrue(outcome.error().contains("输出为空"));
    }

    private BehaviorDecisionParser.Outcome parse(String raw) {
        return parser.parse(raw, CallerType.BEHAVIOR_MODEL, "minicpm-act", npc, null);
    }

    private static final class StubMoveTool implements NpcTool {

        @Override
        public String name() {
            return "npc.move_to";
        }

        @Override
        public String description() {
            return "走到坐标";
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
        public ToolSchema schema() {
            return ToolSchema.builder()
                    .param("x", ToolSchema.Param.number("x"), true)
                    .param("y", ToolSchema.Param.number("y"), true)
                    .param("z", ToolSchema.Param.number("z"), true)
                    .param("speed", ToolSchema.Param.number("speed").min(0.1).max(2.0))
                    .build();
        }

        @Override
        public ToolResult execute(ToolContext context, JsonObject arguments) {
            return ToolResult.completed(new JsonObject());
        }
    }

    private static final class StubQueryTool implements NpcTool {

        @Override
        public String name() {
            return "npc.status.get";
        }

        @Override
        public String description() {
            return "查询状态";
        }

        @Override
        public Category category() {
            return Category.QUERY;
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

    private static final class StubNamedTool implements NpcTool {

        private final String name;

        private StubNamedTool(String name) {
            this.name = name;
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
            return Category.QUERY;
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
