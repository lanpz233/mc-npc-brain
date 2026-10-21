package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具别名（迁移桥接）：老工具名能命中统一柜工具，且别名冲突会被注册表拒绝。
 */
class ToolRegistryAliasTest {

    private static NpcTool toolWithAlias(String name, Set<String> aliases) {
        return new NpcTool() {
            @Override public String name() { return name; }
            @Override public Set<String> aliases() { return aliases; }
            @Override public String description() { return "test"; }
            @Override public Category category() { return Category.ACTION; }
            @Override public Risk risk() { return Risk.MEDIUM; }
            @Override public ToolSchema schema() { return ToolSchema.builder().build(); }
            @Override public ToolResult execute(ToolContext context, JsonObject arguments) {
                return ToolResult.completed(new JsonObject());
            }
        };
    }

    @Test
    void findByAliasHitsTool() {
        ToolRegistry registry = new ToolRegistry();
        NpcTool tool = toolWithAlias("npc.give_item", Set.of("give_item"));
        registry.register(tool);
        assertSame(tool, registry.find("give_item"));
        assertSame(tool, registry.find("npc.give_item"));
        assertSame(tool, registry.find("npc_give_item"));
        assertNull(registry.find("npc.unknown"));
    }

    @Test
    void duplicateAliasRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(toolWithAlias("npc.a", Set.of("shared_name")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(toolWithAlias("npc.b", Set.of("shared_name"))));
    }

    @Test
    void aliasCollidingWithCanonicalNameRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(toolWithAlias("npc.a", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(toolWithAlias("npc.b", Set.of("npc.a"))));
    }
}
