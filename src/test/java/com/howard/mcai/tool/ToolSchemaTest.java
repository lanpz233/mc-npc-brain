package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaTest {

    private final ToolSchema schema = ToolSchema.builder()
            .param("radius", ToolSchema.Param.number("半径").min(1).max(10))
            .param("mode", ToolSchema.Param.string("模式").maxLength(2).allowedValues("a", "b"), true)
            .build();

    @Test
    void rejectsMissingRequiredArgument() {
        List<String> errors = schema.validate(new JsonObject());
        assertEquals(List.of("缺少必填参数: mode"), errors);
    }

    @Test
    void rejectsWrongType() {
        JsonObject args = new JsonObject();
        args.addProperty("mode", 1);
        assertTrue(schema.validate(args).stream().anyMatch(e -> e.contains("必须是字符串")));
    }

    @Test
    void rejectsOutOfRangeNumber() {
        JsonObject args = new JsonObject();
        args.addProperty("mode", "a");
        args.addProperty("radius", 11);
        assertTrue(schema.validate(args).stream().anyMatch(e -> e.contains("不能大于 10")));
    }

    @Test
    void rejectsNonIntegralInteger() {
        ToolSchema integerSchema = ToolSchema.builder()
                .param("count", ToolSchema.Param.integer("数量").min(1).max(5))
                .build();
        JsonObject args = new JsonObject();
        args.addProperty("count", 1.5);
        assertTrue(integerSchema.validate(args).stream().anyMatch(e -> e.contains("必须是整数")));
    }

    @Test
    void rejectsEnumViolationAndUnknownField() {
        JsonObject args = new JsonObject();
        args.addProperty("mode", "c");
        args.addProperty("extra", true);
        List<String> errors = schema.validate(args);
        assertTrue(errors.stream().anyMatch(e -> e.contains("只能是")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("未知参数: extra")));
    }

    @Test
    void acceptsValidArgumentsAndExportsOpenAiSchema() {
        JsonObject args = new JsonObject();
        args.addProperty("mode", "a");
        args.addProperty("radius", 3);
        assertTrue(schema.validate(args).isEmpty());

        var parameters = schema.toOpenAiParameters();
        assertEquals("object", parameters.get("type"));
        assertTrue(parameters.containsKey("required"));
        assertTrue(parameters.containsKey("properties"));
    }
}
