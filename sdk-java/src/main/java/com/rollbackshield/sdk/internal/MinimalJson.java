package com.rollbackshield.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader scoped to exactly the shapes the
 * policy wire format needs (objects, arrays, strings, numbers, booleans,
 * null). This is deliberate: the SDK ships with zero runtime dependencies
 * (§9) so embedding it never forces a dependency tree onto the application.
 * It is not a general-purpose JSON library and should not become one --
 * if the wire format ever needs more than this, that is a signal to
 * reconsider the dependency-free constraint, not to grow this parser.
 */
public final class MinimalJson {

    private final String src;
    private int pos;

    private MinimalJson(String src) {
        this.src = src;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        MinimalJson parser = new MinimalJson(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object at top level");
        }
        return (Map<String, Object>) value;
    }

    private Object parseValue() {
        skipWhitespace();
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObjectInternal();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObjectInternal() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            result.put(key, value);
            skipWhitespace();
            char next = src.charAt(pos++);
            if (next == '}') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
            }
        }
        return result;
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            result.add(parseValue());
            skipWhitespace();
            char next = src.charAt(pos++);
            if (next == ']') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1));
            }
        }
        return result;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        String hex = src.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Unknown escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid boolean literal at position " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid literal at position " + pos);
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        return Double.parseDouble(src.substring(start, pos));
    }

    private void expect(char c) {
        skipWhitespace();
        if (src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
        }
        pos++;
    }

    private char peek() {
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
