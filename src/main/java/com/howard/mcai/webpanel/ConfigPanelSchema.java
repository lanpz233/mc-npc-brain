package com.howard.mcai.webpanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页配置面板（第一期）的可编辑清单 —— config.yml 里现有设置的全集。
 *
 * <p>面板能改什么、每项是什么类型、取值范围多少、保存后是立即生效还是要重启，
 * 全部以本表为准：服务端校验、网页表单、写回 YAML 三处共用同一份定义，
 * 避免"网页能填、插件不认"或"改了没生效还以为生效了"。</p>
 *
 * <p>apply 语义：{@code live} = 保存并重载后生效（与 /ai reload 同等效果）；
 * {@code restart} = 值会写进配置，但要重启服务器才真正换上（这些值在插件启动时
 * 被某些组件记死，如节拍层周期、权限管理器、铁匠出生点、面板自身端口）。</p>
 */
public final class ConfigPanelSchema {

    private ConfigPanelSchema() {
    }

    public enum Type {BOOL, INT, DOUBLE, STRING, PASSWORD}

    /** 保存后如何生效：live = 重载即生效；restart = 需重启服务器 */
    public record Field(String path, String section, String label, String hint, Type type,
                        Double min, Double max, Object defaultValue, boolean live) {
    }

    private static Field f(String path, String section, String label, String hint, Type type,
                           Double min, Double max, Object def, boolean live) {
        return new Field(path, section, label, hint, type, min, max, def, live);
    }

    public static final List<Field> FIELDS = List.of(
            // ===== 模型与对话 =====
            f("llm.url", "模型与对话", "对话模型地址", "Ollama 或云端接口地址，填到域名或 /v1 也会自动补全", Type.STRING, null, null, "http://127.0.0.1:11434/v1/chat/completions", true),
            f("llm.api-key", "模型与对话", "接口密钥", "本机 Ollama 不需要，留空即可；云端接口才填", Type.PASSWORD, null, null, "", true),
            f("llm.model", "模型与对话", "模型名", "填 auto = 自动跟随 Ollama 当前加载的模型", Type.STRING, null, null, "Qwen3.5-9B-Uncensored-Q4_K_M:latest", true),
            f("llm.connect-timeout-ms", "模型与对话", "连接超时（毫秒）", "", Type.INT, 100.0, 60000.0, 5000, true),
            f("llm.read-timeout-ms", "模型与对话", "读取超时（毫秒）", "云端排队慢时可调大，如 60000", Type.INT, 1000.0, 300000.0, 30000, true),
            f("llm.max-dialogue-chars", "模型与对话", "台词最长字数", "村民一句话的最长字数，超了自动在句号处收尾；嫌他话长就调小", Type.INT, 30.0, 200.0, 60, true),
            f("llm.max-tokens", "模型与对话", "单次回复长度上限", "越大村民越能说长句，也越慢", Type.INT, 64.0, 32768.0, 1024, true),
            f("llm.reasoning-effort", "模型与对话", "思考控制", "关思考填 none；不需要控制就留空", Type.STRING, null, null, "", true),
            f("llm.keep-alive", "模型与对话", "模型驻留时长", "Ollama 专用，如 60m；只对本机地址发送", Type.STRING, null, null, "60m", true),
            // ===== 运行节拍 =====
            f("tick.interval-ticks", "运行节拍", "表现层间隔（tick）", "20 tick = 1 秒；控制村民表情/对话检查频率", Type.INT, 20.0, 2400.0, 60, false),
            f("tick.social-interval-ticks", "运行节拍", "社交层间隔（tick）", "八卦传话、好感衰减的全村节拍", Type.INT, 20.0, 72000.0, 1200, false),
            f("tick.player-scan-radius", "运行节拍", "玩家扫描半径（格）", "玩家在此范围内，村民才会醒着活动", Type.INT, 1.0, 64.0, 10, true),
            // ===== 聊天与社交 =====
            f("social.ambient-chat-enabled", "聊天与社交", "村民自动闲聊", "没人搭理时，村民之间自己聊天", Type.BOOL, null, null, true, true),
            f("social.ambient-chat-radius", "聊天与社交", "自动闲聊范围（格）", "此范围内有村民才会开一轮闲聊", Type.DOUBLE, 1.0, 128.0, 32.0, true),
            f("social.ambient-chat-interval-seconds", "聊天与社交", "自动闲聊间隔（秒）", "", Type.INT, 5.0, 3600.0, 30, true),
            f("social.group-chat-enabled", "聊天与社交", "玩家群体聊天", "玩家在公共频道说话时，附近村民附和/回应", Type.BOOL, null, null, true, true),
            f("social.group-chat-radius", "聊天与社交", "群聊收集半径（格）", "", Type.DOUBLE, 1.0, 64.0, 12.0, true),
            // ===== 对话气泡 =====
            f("bubble.duration-ticks", "对话气泡", "气泡显示时长（tick）", "20 tick = 1 秒；长台词会按字数自动加时", Type.INT, 20.0, 2400.0, 120, true),
            f("bubble.offset-y", "对话气泡", "气泡高度偏移", "相对村民头顶的高度，0.4 左右观感较好", Type.DOUBLE, -1.0, 3.0, 0.4, true),
            f("bubble.text-scale", "对话气泡", "气泡文字缩放", "0.5~0.7 观感最佳", Type.DOUBLE, 0.1, 2.0, 0.6, true),
            f("bubble.background-alpha", "对话气泡", "气泡底板不透明度", "0~255，70 = 半透明，255 = 全黑", Type.INT, 0.0, 255.0, 70, true),
            // ===== 记忆 =====
            f("memory.max-chat-history-tokens", "记忆", "对话记忆上限", "每个村民记住的对话量，越大越连贯也越慢", Type.INT, 100.0, 100000.0, 1500, true),
            f("memory.debounce-flush-ticks", "记忆", "记忆保存防抖（tick）", "改动后等这么久再统一落盘，防频繁写盘", Type.INT, 1.0, 600.0, 10, true),
            // ===== 村民需求 =====
            f("needs.autonomy-enabled", "村民需求", "自主行动", "村民按需求自己找吃、闲逛、找人聊天", Type.BOOL, null, null, true, true),
            f("needs.interval-seconds", "村民需求", "需求检查间隔（秒）", "", Type.INT, 5.0, 600.0, 30, false),
            f("needs.threshold", "村民需求", "需求触发阈值", "任一需求涨到这个数，村民就会行动", Type.DOUBLE, 1.0, 100.0, 80.0, true),
            f("needs.autonomy-cooldown-seconds", "村民需求", "自主行动冷却（秒）", "同一个村民两次自主行动的最小间隔", Type.INT, 0.0, 3600.0, 120, true),
            f("needs.visibility-radius", "村民需求", "自主行动观众半径（格）", "范围内没玩家就不触发，省算力", Type.DOUBLE, 1.0, 128.0, 32.0, true),
            // ===== 权限 =====
            f("permissions.confidant-favor", "权限", "亲信好感门槛", "好感达到这个数，玩家自动成为该村民的亲信", Type.DOUBLE, 0.0, 10000.0, 60.0, false),
            f("permissions.territory-radius", "权限", "领地半径（格）", "以玩家床为圆心，平民村民不得在此范围内拆方块", Type.DOUBLE, 0.0, 256.0, 24.0, false),
            f("permissions.teleport-confirm-timeout-seconds", "权限", "传送确认有效期（秒）", "神使让村民传送玩家时，玩家回答“好”的有效时间", Type.INT, 5.0, 600.0, 30, false),
            // ===== 调试 =====
            f("debug.enabled", "调试", "调试消息进聊天框", "打开后 MC-AI 的调试行会发到玩家聊天框（平时只进控制台）", Type.BOOL, null, null, false, true),
            // ===== 村民出生点 =====
            f("shop.resource-pack-skin", "商店与资源包", "商店资源包皮肤", "打开后开店是木柜台界面，进服自动发资源包", Type.BOOL, null, null, false, true),
            f("shop.resource-pack-port", "商店与资源包", "资源包下载端口", "外网玩家进服时，路由器要放行这个端口", Type.INT, 1.0, 65535.0, 8765, true),
            f("shop.resource-pack-host", "商店与资源包", "资源包主机名覆盖", "留空 = 按玩家连入地址自动推导；穿透/代理时才填", Type.STRING, null, null, "", true),
            // ===== 网页配置面板 =====
            f("web-panel.enabled", "网页配置面板", "启用配置面板", "关掉后插件不再生成面板、也不再开本机管理服务", Type.BOOL, null, null, true, false),
            f("web-panel.port", "网页配置面板", "配置面板端口", "只绑本机 127.0.0.1，不要在路由器上放行它", Type.INT, 1.0, 65535.0, 8767, false)
    );

    public static final Map<String, Field> BY_PATH = new LinkedHashMap<>();

    static {
        for (Field field : FIELDS) BY_PATH.put(field.path(), field);
    }

    /** 必须非空的字符串项 */
    private static boolean required(Field field) {
        return switch (field.path()) {
            case "llm.url", "llm.model" -> true;
            default -> false;
        };
    }

    /**
     * 校验并归一化一整批网页提交的配置值（path → JSON 值）。
     * 任一项不合法就整体拒绝（一项都不写盘），错误消息是给服主看的人话。
     *
     * @return path → 归一化后的 Java 值（Boolean / Integer / Double / String）
     */
    public static Map<String, Object> validateAll(JsonObject values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("没有收到任何修改");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (var entry : values.entrySet()) {
            Field field = BY_PATH.get(entry.getKey());
            if (field == null) {
                errors.add("不认识的设置项：" + entry.getKey());
                continue;
            }
            try {
                out.put(field.path(), validateValue(field, entry.getValue()));
            } catch (IllegalArgumentException e) {
                errors.add(field.label() + "：" + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
        return out;
    }

    /** 校验单个值并归一化类型 */
    public static Object validateValue(Field field, JsonElement el) {
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("不能为空");
        }
        return switch (field.type()) {
            case BOOL -> {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("应该是开关（开/关）");
                }
                yield el.getAsBoolean();
            }
            case INT -> {
                double d = number(field, el);
                if (d != Math.rint(d)) throw new IllegalArgumentException("应该是整数");
                checkRange(field, d);
                yield (int) d;
            }
            case DOUBLE -> {
                double d = number(field, el);
                checkRange(field, d);
                yield d;
            }
            case STRING, PASSWORD -> {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("应该是文字");
                }
                String s = el.getAsString().trim();
                if (s.length() > 500) throw new IllegalArgumentException("太长了（最多 500 字）");
                if (required(field) && s.isBlank()) throw new IllegalArgumentException("不能为空");
                if ("llm.url".equals(field.path())) {
                    try {
                        URI uri = URI.create(s);
                        if (uri.getScheme() == null
                                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                                || uri.getHost() == null) {
                            throw new IllegalArgumentException("地址格式不对，应以 http:// 或 https:// 开头");
                        }
                    } catch (IllegalArgumentException e) {
                        if (e.getMessage() != null && e.getMessage().startsWith("地址格式")) throw e;
                        throw new IllegalArgumentException("地址格式不对，应以 http:// 或 https:// 开头");
                    }
                }
                yield s;
            }
        };
    }

    private static double number(Field field, JsonElement el) {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("应该是数字");
        }
        double d = el.getAsDouble();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("数字不合法");
        return d;
    }

    private static void checkRange(Field field, double d) {
        if (field.min() != null && d < field.min()) {
            throw new IllegalArgumentException("不能小于 " + trimNum(field.min()));
        }
        if (field.max() != null && d > field.max()) {
            throw new IllegalArgumentException("不能大于 " + trimNum(field.max()));
        }
    }

    private static String trimNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** 把归一化后的值填上默认值，凑齐全部字段（读不到配置文件时用） */
    public static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : FIELDS) out.put(field.path(), field.defaultValue());
        return out;
    }
}
