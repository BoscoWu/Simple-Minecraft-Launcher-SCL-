package com.fasterxml.jackson.databind;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写器，替代 Jackson 的 ObjectMapper。
 *
 * <p>只提供本项目用到的接口：readTree / writeValueAsString /
 * createObjectNode / createArrayNode / writerWithDefaultPrettyPrinter。</p>
 */
public final class ObjectMapper {

    public JsonNode readTree(String text) {
        if (text == null || text.isBlank()) return JsonNode.MISSING;
        Json.Parser p = new Json.Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        return wrap(v);
    }

    /**
     * 把解析出来的原始结构包成合适的节点类型。
     *
     * <p>必须与 Jackson 行为一致：JSON 对象返回 {@link ObjectNode}、
     * 数组返回 {@link ArrayNode}，否则调用方 {@code (ObjectNode) node}
     * 这样的强转会抛 ClassCastException。</p>
     */
    @SuppressWarnings("unchecked")
    static JsonNode wrap(Object v) {
        if (v instanceof LinkedHashMap<?, ?> m) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(String.valueOf(e.getKey()), wrapValue(e.getValue()));
            }
            return new ObjectNode(copy);
        }
        if (v instanceof java.util.ArrayList<?> l) {
            ArrayList<Object> copy = new ArrayList<>(l.size());
            for (Object o : l) copy.add(wrapValue(o));
            return new ArrayNode(copy);
        }
        return new JsonNode(v);
    }

    /** 递归把子节点也转换成具体类型（ObjectNode / ArrayNode） */
    static Object wrapValue(Object v) {
        if (v instanceof LinkedHashMap<?, ?> m) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(String.valueOf(e.getKey()), wrapValue(e.getValue()));
            }
            return copy;
        }
        if (v instanceof java.util.ArrayList<?> l) {
            ArrayList<Object> copy = new ArrayList<>(l.size());
            for (Object o : l) copy.add(wrapValue(o));
            return copy;
        }
        return v;
    }

    public JsonNode readTree(byte[] bytes) {
        if (bytes == null) return JsonNode.MISSING;
        return readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    public String writeValueAsString(Object value) {
        StringBuilder sb = new StringBuilder();
        Json.write(sb, value, -1, 0);
        return sb.toString();
    }

    public ObjectNode createObjectNode() {
        return new ObjectNode();
    }

    public ArrayNode createArrayNode() {
        return new ArrayNode();
    }

    /** 返回一个带缩进格式化的写入器，便于人类阅读的配置文件 */
    public Writer writerWithDefaultPrettyPrinter() {
        return new Writer();
    }

    /** 带缩进的 JSON 输出 */
    public static final class Writer {
        public String writeValueAsString(Object value) {
            StringBuilder sb = new StringBuilder();
            Json.write(sb, value, 2, 0);
            return sb.toString();
        }
    }

    // ---------------- 内部实现 ----------------
}
