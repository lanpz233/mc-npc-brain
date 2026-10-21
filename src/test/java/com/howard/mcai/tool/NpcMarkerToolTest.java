package com.howard.mcai.tool;

import com.google.gson.JsonObject;
import com.howard.mcai.tool.tools.NpcMarkerTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表达登记工具的参数校验回归 —— 实测事故（2026-10-21）：
 * say 的 message 曾是必填，但台词实际走对话正文、登记工具不消费该参数，
 * 模型只发空参数时每轮对话都校验失败，原始校验文本漏进玩家聊天框，
 * 且失败标记污染历史后被模型学成台词。say 的 message 必须可缺省。
 */
class NpcMarkerToolTest {

    @Test
    void sayAcceptsMissingMessage() {
        assertTrue(NpcMarkerTool.say().schema().validate(new JsonObject()).isEmpty(),
                "say 不带 message 必须校验通过（台词走正文，登记工具不消费该参数）");
    }

    @Test
    void sayAcceptsMessage() {
        JsonObject args = new JsonObject();
        args.addProperty("message", "你好啊");
        assertTrue(NpcMarkerTool.say().schema().validate(args).isEmpty());
    }

    @Test
    void gestureMarkersAcceptEmptyArgs() {
        assertTrue(NpcMarkerTool.wave().schema().validate(new JsonObject()).isEmpty());
        assertTrue(NpcMarkerTool.nod().schema().validate(new JsonObject()).isEmpty());
        assertTrue(NpcMarkerTool.shakeHead().schema().validate(new JsonObject()).isEmpty());
        assertTrue(NpcMarkerTool.idle().schema().validate(new JsonObject()).isEmpty());
    }
}
