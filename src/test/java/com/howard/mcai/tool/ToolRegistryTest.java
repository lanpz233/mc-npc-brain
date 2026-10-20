package com.howard.mcai.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolRegistryTest {

    @Test
    void registersAndFindsByCanonicalAndWireName() {
        ToolRegistry registry = new ToolRegistry();
        FakeTool tool = FakeTool.query("test.echo");
        registry.register(tool);

        assertSame(tool, registry.find("test.echo"));
        assertSame(tool, registry.find("test_echo"));
        assertEquals(1, registry.size());
        assertEquals(1, registry.schemasFor(ToolContext.CallerType.INTERNAL).size());
    }

    @Test
    void rejectsDuplicateCanonicalName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FakeTool.query("test.echo"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(FakeTool.query("test.echo")));
    }

    @Test
    void rejectsDuplicateWireNameWithoutPartiallyRegistering() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FakeTool.query("a.b"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(FakeTool.query("a_b")));
        assertEquals(1, registry.size());
    }

    @Test
    void hidesToolsFromCallersOutsideWhitelist() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FakeTool.restricted("test.internal", ToolContext.CallerType.INTERNAL));

        assertEquals(1, registry.schemasFor(ToolContext.CallerType.INTERNAL).size());
        assertEquals(0, registry.schemasFor(ToolContext.CallerType.DIALOG_MODEL).size());
    }
}
