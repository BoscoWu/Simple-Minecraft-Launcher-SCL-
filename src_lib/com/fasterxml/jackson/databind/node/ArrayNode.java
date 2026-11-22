package com.fasterxml.jackson.databind.node;

import com.fasterxml.jackson.databind.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;

/** 可变的 JSON 数组节点 */
public class ArrayNode extends JsonNode {

    private final ArrayList<Object> list;

    public ArrayNode() {
        this(new ArrayList<>());
    }

    public ArrayNode(ArrayList<Object> l) {
        super(l);
        this.list = l;
    }

    public ArrayNode add(JsonNode node) {
        list.add(node == null ? null : node.value);
        return this;
    }

    public ArrayNode add(String v) {
        list.add(v);
        return this;
    }

    public ArrayNode add(int v) {
        list.add((long) v);
        return this;
    }

    public ArrayNode add(long v) {
        list.add(v);
        return this;
    }

    public ArrayNode add(boolean v) {
        list.add(v);
        return this;
    }

    @Override
    public ArrayNode deepCopy() {
        ArrayList<Object> copy = new ArrayList<>();
        for (Object o : list) copy.add(JsonNode.deepCopyValue(o));
        return new ArrayNode(copy);
    }

    public int size() {
        return list.size();
    }
}
