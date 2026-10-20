package com.howard.mcai.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数定义 + 参数校验 + OpenAI function schema 输出。
 * <p>
 * 只支持当前工具真正需要的简单类型，不引入完整 JSON Schema 实现。
 * 校验在进入工具实现之前完成，模型永远拿不到未校验的参数。
 */
public final class ToolSchema {

    public enum Type {
        STRING, INTEGER, NUMBER, BOOLEAN
    }

    /**
     * 单个参数的声明。不可变，链式方法返回新对象。
     */
    public static final class Param {
        private final Type type;
        private final String description;
        private final Double min;
        private final Double max;
        private final Integer maxLength;
        private final Set<String> allowedValues;

        private Param(Type type, String description, Double min, Double max,
                      Integer maxLength, Set<String> allowedValues) {
            this.type = type;
            this.description = description;
            this.min = min;
            this.max = max;
            this.maxLength = maxLength;
            this.allowedValues = allowedValues;
        }

        public static Param string(String description) {
            return new Param(Type.STRING, description, null, null, null, null);
        }

        public static Param integer(String description) {
            return new Param(Type.INTEGER, description, null, null, null, null);
        }

        public static Param number(String description) {
            return new Param(Type.NUMBER, description, null, null, null, null);
        }

        public static Param bool(String description) {
            return new Param(Type.BOOLEAN, description, null, null, null, null);
        }

        public Param min(double min) {
            return new Param(type, description, min, max, maxLength, allowedValues);
        }

        public Param max(double max) {
            return new Param(type, description, min, max, maxLength, allowedValues);
        }

        public Param maxLength(int maxLength) {
            return new Param(type, description, min, max, maxLength, allowedValues);
        }

        public Param allowedValues(String... values) {
            return new Param(type, description, min, max, maxLength, Set.of(values));
        }

        public Type type() { return type; }
        public String description() { return description; }
        public Double min() { return min; }
        public Double max() { return max; }
        public Integer maxLength() { return maxLength; }
        public Set<String> allowedValues() { return allowedValues; }
    }

    private final Map<String, Param> params;
    private final Set<String> required;

    private ToolSchema(Map<String, Param> params, Set<String> required) {
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ToolSchema empty() {
        return builder().build();
    }

    public Map<String, Param> params() { return params; }
    public Set<String> required() { return required; }

    /**
     * 参数校验：未知字段、必填缺失、类型、整数性、范围、枚举白名单、字符串长度。
     *
     * @return 错误列表；空列表表示通过
     */
    public List<String> validate(JsonObject args) {
        List<String> errors = new ArrayList<>();
        JsonObject actual = args == null ? new JsonObject() : args;

        // 未知字段一律拒绝：避免模型塞入未声明的参数被静默忽略
        for (String key : actual.keySet()) {
            if (!params.containsKey(key)) {
                errors.add("未知参数: " + key);
            }
        }

        for (Map.Entry<String, Param> entry : params.entrySet()) {
            String name = entry.getKey();
            Param param = entry.getValue();
            JsonElement value = actual.get(name);

            if (value == null || value.isJsonNull()) {
                if (required.contains(name)) {
                    errors.add("缺少必填参数: " + name);
                }
                continue;
            }
            if (!value.isJsonPrimitive()) {
                errors.add("参数 " + name + " 必须是 " + param.type + " 值");
                continue;
            }

            JsonPrimitive primitive = value.getAsJsonPrimitive();
            switch (param.type) {
                case STRING -> validateString(name, param, primitive, errors);
                case BOOLEAN -> {
                    if (!primitive.isBoolean()) {
                        errors.add("参数 " + name + " 必须是布尔值");
                    }
                }
                case INTEGER -> validateNumber(name, param, primitive, errors, true);
                case NUMBER -> validateNumber(name, param, primitive, errors, false);
            }
        }
        return errors;
    }

    private static void validateString(String name, Param param, JsonPrimitive primitive,
                                       List<String> errors) {
        if (!primitive.isString()) {
            errors.add("参数 " + name + " 必须是字符串");
            return;
        }
        String text = primitive.getAsString();
        if (param.maxLength != null && text.length() > param.maxLength) {
            errors.add("参数 " + name + " 长度不能超过 " + param.maxLength);
        }
        if (param.allowedValues != null && !param.allowedValues.contains(text)) {
            errors.add("参数 " + name + " 只能是 " + param.allowedValues);
        }
    }

    private static void validateNumber(String name, Param param, JsonPrimitive primitive,
                                       List<String> errors, boolean integerOnly) {
        if (!primitive.isNumber()) {
            errors.add("参数 " + name + " 必须是数字");
            return;
        }
        double value = primitive.getAsDouble();
        if (!Double.isFinite(value)) {
            errors.add("参数 " + name + " 必须是有限数字");
            return;
        }
        if (integerOnly && value != Math.rint(value)) {
            errors.add("参数 " + name + " 必须是整数");
            return;
        }
        if (param.min != null && value < param.min) {
            errors.add("参数 " + name + " 不能小于 " + format(param.min));
        }
        if (param.max != null && value > param.max) {
            errors.add("参数 " + name + " 不能大于 " + format(param.max));
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * 生成 OpenAI function schema 中 parameters 字段的内容。
     */
    public Map<String, Object> toOpenAiParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Param> entry : params.entrySet()) {
            Param param = entry.getValue();
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", switch (param.type) {
                case STRING -> "string";
                case INTEGER -> "integer";
                case NUMBER -> "number";
                case BOOLEAN -> "boolean";
            });
            if (param.description != null) {
                property.put("description", param.description);
            }
            if (param.allowedValues != null) {
                property.put("enum", new ArrayList<>(param.allowedValues));
            }
            if (param.min != null) {
                property.put("minimum", param.min);
            }
            if (param.max != null) {
                property.put("maximum", param.max);
            }
            if (param.maxLength != null) {
                property.put("maxLength", param.maxLength);
            }
            properties.put(entry.getKey(), property);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        return schema;
    }

    public static final class Builder {
        private final Map<String, Param> params = new LinkedHashMap<>();
        private final Set<String> required = new LinkedHashSet<>();

        public Builder param(String name, Param param) {
            params.put(name, param);
            return this;
        }

        public Builder param(String name, Param param, boolean isRequired) {
            params.put(name, param);
            if (isRequired) required.add(name);
            return this;
        }

        public Builder required(String name) {
            required.add(name);
            return this;
        }

        public ToolSchema build() {
            for (String name : required) {
                if (!params.containsKey(name)) {
                    throw new IllegalStateException("必填参数未声明: " + name);
                }
            }
            return new ToolSchema(params, required);
        }
    }
}
