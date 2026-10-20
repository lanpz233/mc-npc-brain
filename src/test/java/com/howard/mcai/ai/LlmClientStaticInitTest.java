package com.howard.mcai.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 回归测试：LLMClient 的静态字段初始化顺序。
 * <p>
 * 曾经把 extraToolNames 声明在三个 Pattern 之后，Pattern 初始化时调用 toolNameAlternation()
 * 读到 null → ExceptionInInitializerError，整个插件在 onEnable 阶段被禁用。
 */
class LlmClientStaticInitTest {

    @Test
    void staticInitializerSurvivesDynamicToolRegistration() {
        assertDoesNotThrow(() -> LLMClient.registerFallbackToolNames(List.of("npc_move_to")));
    }
}
