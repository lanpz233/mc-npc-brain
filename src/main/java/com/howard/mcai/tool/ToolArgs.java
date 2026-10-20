package com.howard.mcai.tool;

import com.google.gson.JsonObject;

/**
 * 已通过 {@link ToolSchema#validate} 的参数读取工具。
 * <p>
 * 这里不再做类型判断，只提供默认值，避免每个工具重复写 asXxx + 判空。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String string(JsonObject args, String key, String defaultValue) {
        return args != null && args.has(key) && !args.get(key).isJsonNull()
                ? args.get(key).getAsString() : defaultValue;
    }

    public static int integer(JsonObject args, String key, int defaultValue) {
        return args != null && args.has(key) && !args.get(key).isJsonNull()
                ? args.get(key).getAsInt() : defaultValue;
    }

    public static double number(JsonObject args, String key, double defaultValue) {
        return args != null && args.has(key) && !args.get(key).isJsonNull()
                ? args.get(key).getAsDouble() : defaultValue;
    }

    public static boolean bool(JsonObject args, String key, boolean defaultValue) {
        return args != null && args.has(key) && !args.get(key).isJsonNull()
                ? args.get(key).getAsBoolean() : defaultValue;
    }
}
