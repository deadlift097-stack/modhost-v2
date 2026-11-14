package org.modhost.jar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free JSON reader. It is only used for mod metadata such as
 * fabric.mod.json, so it favours being tolerant over being fast.
 *
 * <p>Values come back as {@link Map} (objects), {@link List} (arrays), {@link String},
 * {@link Double}, {@link Boolean} or {@code null}.
 */
public final class Json {
    private final String s;
    private int pos;

    private Json(String text) {
        this.s = text;
    }

    /** Parses a JSON document. Throws IllegalArgumentException with a position on bad input. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no JSON text");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Json p = new Json(text);
        p.skipSpace();
        Object v = p.value(0);
        p.skipSpace();
        if (p.pos != p.s.length()) {
            throw p.error("unexpected text after the document");
        }
        return v;
    }

    /** Like {@link #parse} but returns null instead of throwing. */
    public static Object tryParse(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** Reads a string at a dotted path such as "quilt_loader.metadata.name"; null when absent. */
    public static String string(Object root, String path) {
        Object v = path(root, path);
        return v instanceof String ? (String) v : null;
    }

    /** Walks object keys separated by dots. */
    public static Object path(Object root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            Map<String, Object> m = asObject(cur);
            if (m == null) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }

    // ------------------------------------------------------------ parser

    private IllegalArgumentException error(String what) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException(what + " at offset " + pos + " (line " + line + ")");
    }

    private void skipSpace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '/') {
                while (pos < s.length() && s.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '*') {
                int end = s.indexOf("*/", pos + 2);
                pos = end < 0 ? s.length() : end + 2;
            } else {
                break;
            }
        }
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw error("nesting is too deep");
        }
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object result) {
        if (!s.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
        return result;
    }

    private Double number() {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        try {
            return Double.valueOf(s.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("bad number");
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("unterminated escape");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case 'n':
                    b.append('\n');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("short unicode escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    pos += 4;
                    break;
                default:
                    b.append(e);
            }
        }
    }

    private List<Object> array(int depth) {
        pos++;
        List<Object> out = new ArrayList<>();
        skipSpace();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value(depth + 1));
            skipSpace();
            if (pos >= s.length()) {
                throw error("unterminated array");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or ']'");
            }
        }
    }

    private Map<String, Object> object(int depth) {
        pos++;
        Map<String, Object> out = new LinkedHashMap<>();
        skipSpace();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw error("expected a string key");
            }
            String key = string();
            skipSpace();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipSpace();
            out.put(key, value(depth + 1));
            skipSpace();
            if (pos >= s.length()) {
                throw error("unterminated object");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or '}'");
            }
        }
    }
}
