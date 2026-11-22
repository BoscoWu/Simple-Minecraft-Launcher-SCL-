package com.fasterxml.jackson.databind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 读写核心：序列化与递归下降解析 */
public final class Json {

    private Json() {}


        /** 递归写入 JSON 文本。indent < 0 表示紧凑输出 */
        public static void write(StringBuilder sb, Object v, int indent, int depth) {
            if (v == null) {
                sb.append("null");
                return;
            }
            if (v instanceof JsonNode n) {
                write(sb, n.value, indent, depth);
                return;
            }
            if (v instanceof Map<?, ?> m) {
                writeObject(sb, m, indent, depth);
                return;
            }
            if (v instanceof List<?> l) {
                writeArray(sb, l, indent, depth);
                return;
            }
            if (v instanceof String s) {
                writeString(sb, s);
                return;
            }
            if (v instanceof Boolean b) {
                sb.append(b);
                return;
            }
            if (v instanceof Double d) {
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                    sb.append(d.longValue());
                } else {
                    sb.append(d);
                }
                return;
            }
            if (v instanceof Number n) {
                sb.append(n);
                return;
            }
            // 未知类型当作字符串
            writeString(sb, String.valueOf(v));
        }

        private static void writeObject(StringBuilder sb, Map<?, ?> m,
                                        int indent, int depth) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                if (indent >= 0) sb.append(' ');
                write(sb, e.getValue(), indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append('}');
        }

        private static void writeArray(StringBuilder sb, List<?> l,
                                       int indent, int depth) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                write(sb, o, indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append(']');
        }

        private static void newline(StringBuilder sb, int indent, int depth) {
            if (indent < 0) return;
            sb.append('\n');
            sb.append(" ".repeat(indent * depth));
        }

        static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        /** 递归下降解析器 */
        public static final class Parser {
            private final String s;
            private int i;

            public Parser(String s) {
                this.s = s;
            }

            public void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }

            public Object parseValue() {
                skipWs();
                if (i >= s.length()) return null;
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> { i += 4; yield Boolean.TRUE; }
                    case 'f' -> { i += 5; yield Boolean.FALSE; }
                    case 'n' -> { i += 4; yield null; }
                    default -> parseNumber();
                };
            }

            private Map<String, Object> parseObject() {
                Map<String, Object> map = new LinkedHashMap<>();
                i++;                       // {
                skipWs();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return map;
                }
                while (i < s.length()) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    if (i < s.length() && s.charAt(i) == ':') i++;
                    Object v = parseValue();
                    map.put(key, v);
                    skipWs();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == '}') i++;
                    break;
                }
                return map;
            }

            private List<Object> parseArray() {
                List<Object> list = new ArrayList<>();
                i++;                       // [
                skipWs();
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (i < s.length()) {
                    list.add(parseValue());
                    skipWs();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == ']') i++;
                    break;
                }
                return list;
            }

            private String parseString() {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') return "";
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c != '\\') {
                        sb.append(c);
                        continue;
                    }
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(
                                        s.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(e);
                    }
                }
                return sb.toString();
            }

            private Object parseNumber() {
                int start = i;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                            || (c >= '0' && c <= '9')) {
                        i++;
                    } else {
                        break;
                    }
                }
                if (start == i) {          // 非法字符，跳过
                    i++;
                    return null;
                }
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(num);
                }
            }
        }
    }
