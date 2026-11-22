package com.fasterxml.jackson.databind.node;

import com.fasterxml.jackson.databind.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 可变的 JSON 对象节点 */
public class ObjectNode extends JsonNode {

    private final LinkedHashMap<String, Object> map;

    public ObjectNode() {
        this(new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    public ObjectNode(LinkedHashMap<String, Object> m) {
        super(m);
        this.map = m;
    }

    /** 写入字段，返回自身便于链式调用 */
    public ObjectNode put(String key, String v) {
        map.put(key, v);
        return this;
    }

    public ObjectNode put(String key, int v) {
        map.put(key, (long) v);
        return this;
    }

    public ObjectNode put(String key, long v) {
        map.put(key, v);
        return this;
    }

    public ObjectNode put(String key, double v) {
        map.put(key, v);
        return this;
    }

    public ObjectNode put(String key, boolean v) {
        map.put(key, v);
        return this;
    }

    /** 写入 null 字段 */
    public ObjectNode putNull(String key) {
        map.put(key, null);
        return this;
    }

    /** 写入子节点 */
    public ObjectNode set(String key, JsonNode node) {
        map.put(key, node == null ? null : node.value);
        return this;
    }

    /** 取出或创建子对象 */
    @SuppressWarnings("unchecked")
    public ObjectNode withObject(String key) {
        String k = key.startsWith("/") ? key.substring(1) : key;
        Object cur = map.get(k);
        if (cur instanceof LinkedHashMap<?, ?> m) {
            return new ObjectNode((LinkedHashMap<String, Object>) m);
        }
        LinkedHashMap<String, Object> fresh = new LinkedHashMap<>();
        map.put(k, fresh);
        return new ObjectNode(fresh);
    }

    /** 取出或创建子数组 */
    @SuppressWarnings("unchecked")
    public ArrayNode withArray(String key) {
        String k = key.startsWith("/") ? key.substring(1) : key;
        Object cur = map.get(k);
        if (cur instanceof java.util.ArrayList<?> l) {
            return new ArrayNode((java.util.ArrayList<Object>) l);
        }
        java.util.ArrayList<Object> fresh = new java.util.ArrayList<>();
        map.put(k, fresh);
        return new ArrayNode(fresh);
    }

    /** 创建并挂上一个子对象，返回它（Jackson 的 putObject 语义） */
    public ObjectNode putObject(String key) {
        LinkedHashMap<String, Object> fresh = new LinkedHashMap<>();
        map.put(key, fresh);
        return new ObjectNode(fresh);
    }

    /** 创建并挂上一个子数组 */
    public ArrayNode putArray(String key) {
        java.util.ArrayList<Object> fresh = new java.util.ArrayList<>();
        map.put(key, fresh);
        return new ArrayNode(fresh);
    }

    /** Jackson 的 with(String) 别名，语义同 withObject */
    public ObjectNode with(String key) {
        return withObject(key);
    }

    /** 无参版本：返回自身（Jackson 允许 withObject("") 取自身） */
    public ObjectNode withObject() {
        return this;
    }

    public JsonNode remove(String key) {
        Object v = map.remove(key);
        return v == null ? JsonNode.MISSING : JsonNode.wrap(v);
    }

    @Override
    public ObjectNode deepCopy() {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            copy.put(e.getKey(), JsonNode.deepCopyValue(e.getValue()));
        }
        return new ObjectNode(copy);
    }

    public Map<String, Object> rawMap() {
        return map;
    }
}
