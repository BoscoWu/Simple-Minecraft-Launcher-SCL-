package com.fasterxml.jackson.databind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 节点。
 *
 * <p>只实现本项目实际用到的 API，用来替代 Jackson（约 1.9MB）。
 * 支持 JSON 的完整语法：对象、数组、字符串、数字、布尔、null。</p>
 *
 * <p>内部用 {@code Object} 直接承载：
 * {@code LinkedHashMap} / {@code List} / {@code String} /
 * {@code Double} / {@code Long} / {@code Boolean} / {@code null}。</p>
 */
public class JsonNode implements Iterable<JsonNode> {

    public static final JsonNode MISSING = new JsonNode(null);
    public static final JsonNode NULL_NODE = new JsonNode(null);

    public final Object value;

    public JsonNode(Object v) {
        this.value = v;
    }

    // ---------------- 类型判断 ----------------

    public boolean isMissingNode() { return this == MISSING; }
    public boolean isNull() { return value == null; }
    public boolean isObject() { return value instanceof Map; }
    public boolean isArray() { return value instanceof List; }
    public boolean isTextual() { return value instanceof String; }
    public boolean isNumber() { return value instanceof Number; }
    public boolean isBoolean() { return value instanceof Boolean; }

    /** 对象字段数 / 数组元素数；其他类型返回 0 */
    public int size() {
        if (value instanceof Map<?, ?> m) return m.size();
        if (value instanceof List<?> l) return l.size();
        return 0;
    }

    public boolean isEmpty() { return size() == 0; }

    // ---------------- 取值 ----------------

    @SuppressWarnings("unchecked")
    public JsonNode path(String field) {
        if (value instanceof Map<?, ?> m) {
            Object v = m.get(field);
            return wrap(v);
        }
        return MISSING;
    }

    @SuppressWarnings("unchecked")
    public JsonNode path(int index) {
        if (value instanceof List<?> l && index >= 0 && index < l.size()) {
            Object v = l.get(index);
            return wrap(v);
        }
        return MISSING;
    }

    /**
     * 把原始值包成具体节点类型。
     *
     * <p>必须返回 {@code ObjectNode} / {@code ArrayNode}，
     * 否则调用方 {@code (ObjectNode) node.path("x")} 会抛 ClassCastException。
     * 与 Jackson 行为保持一致。</p>
     */
    @SuppressWarnings("unchecked")
    public static JsonNode wrap(Object v) {
        if (v == null) return MISSING;
        if (v instanceof JsonNode n) return n;
        if (v instanceof Map<?, ?> m) {
            // 直接复用底层 Map，保证修改能回写到原节点
            return new com.fasterxml.jackson.databind.node.ObjectNode(
                    (java.util.LinkedHashMap<String, Object>) m);
        }
        if (v instanceof List<?> l) {
            return new com.fasterxml.jackson.databind.node.ArrayNode(
                    (java.util.ArrayList<Object>) l);
        }
        return new JsonNode(v);
    }

    public JsonNode get(String field) {
        JsonNode n = path(field);
        return n.isMissingNode() ? null : n;
    }

    public JsonNode get(int index) {
        JsonNode n = path(index);
        return n.isMissingNode() ? null : n;
    }

    public boolean has(String field) {
        return value instanceof Map<?, ?> m && m.containsKey(field);
    }

    public String asText() { return asText(""); }

    public String asText(String def) {
        if (value == null) return def;
        if (value instanceof String s) return s;
        if (value instanceof Double d) {
            // 整数形式的浮点去掉小数点，和 Jackson 行为一致
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                return String.valueOf(d.longValue());
            }
            return String.valueOf(d);
        }
        if (value instanceof Number n) return String.valueOf(n);
        if (value instanceof Boolean b) return String.valueOf(b);
        return def;
    }

    public int asInt() { return asInt(0); }

    public int asInt(int def) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    public long asLong() { return asLong(0L); }

    public long asLong(long def) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    public double asDouble() { return asDouble(0); }

    public double asDouble(double def) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    public boolean asBoolean() { return asBoolean(false); }

    public boolean asBoolean(boolean def) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s.trim());
        if (value instanceof Number n) return n.intValue() != 0;
        return def;
    }

    public BigDecimal decimalValue() {
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    // ---------------- 遍历 ----------------

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<JsonNode> iterator() {
        if (value instanceof List<?> l) {
            List<JsonNode> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(wrap(o));
            return out.iterator();
        }
        return java.util.Collections.emptyIterator();
    }

    /** 对象字段迭代（Jackson 的 fields 是 Iterator<Map.Entry<String,JsonNode>>） */
    @SuppressWarnings("unchecked")
    public Iterator<Map.Entry<String, JsonNode>> fields() {
        if (!(value instanceof Map<?, ?> m)) {
            return java.util.Collections.emptyIterator();
        }
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object v = e.getValue();
            out.add(Map.entry(String.valueOf(e.getKey()), wrap(v)));
        }
        return out.iterator();
    }

    /** 把当前对象节点转成普通 Map（用于需要原始数据的场景） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public List<Object> asList() {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    /** 生成独立副本（保持具体类型：对象→ObjectNode，数组→ArrayNode） */
    public JsonNode deepCopy() {
        return wrap(deepCopyValue(value));
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(deepCopyValue(o));
            return out;
        }
        return v;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Json.write(sb, value, -1, 0);
        return sb.toString();
    }
}
