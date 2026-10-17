package io.github.ctxinf.cameraagent.legacy.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON parser and writer.
 *
 * <p>Android has {@code org.json} in the framework, but its behaviour differs
 * across the 4.x/5.x/newer platform versions this app targets (notably around
 * null handling and number coercion), and it throws checked JSONException
 * everywhere. A few hundred lines here buys predictable behaviour on every
 * device with no dependency and no minSdk pressure.
 *
 * <p>Values map to: {@code Map<String,Object>}, {@code List<Object>},
 * {@code String}, {@code Double}, {@code Long}, {@code Boolean}, {@code null}.
 */
public final class Json {

    private Json() { }

    // --- parsing ------------------------------------------------------------

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object v = p.readValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("trailing content at offset " + p.pos);
        }
        return v;
    }

    /** Parse and require a JSON object at the top level. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    public static class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        /** Bounds recursion so a hostile payload cannot blow the stack. */
        private static final int MAX_DEPTH = 64;

        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) throws JsonException {
            if (depth > MAX_DEPTH) {
                throw new JsonException("nesting too deep");
            }
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject(depth);
                case '[': return readArray(depth);
                case '"': return readString();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return readNumber();
            }
        }

        private void expect(String literal) throws JsonException {
            if (!s.startsWith(literal, pos)) {
                throw new JsonException("expected '" + literal + "' at offset " + pos);
            }
            pos += literal.length();
        }

        private Map<String, Object> readObject(int depth) throws JsonException {
            pos++;   // '{'
            // Linked, so a round-trip keeps field order -- much easier to eyeball.
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw new JsonException("expected a key string at offset " + pos);
                }
                String key = readString();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') {
                    throw new JsonException("expected ':' at offset " + pos);
                }
                pos++;
                out.put(key, readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("unterminated object");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return out;
                }
                throw new JsonException("expected ',' or '}' at offset " + pos);
            }
        }

        private List<Object> readArray(int depth) throws JsonException {
            pos++;   // '['
            List<Object> out = new ArrayList<Object>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("unterminated array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return out;
                }
                throw new JsonException("expected ',' or ']' at offset " + pos);
            }
        }

        private String readString() throws JsonException {
            pos++;   // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw new JsonException("truncated \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException nfe) {
                            throw new JsonException("bad \\u escape at offset " + pos);
                        }
                        pos += 4;
                        break;
                    default:
                        throw new JsonException("bad escape '\\" + e + "'");
                }
            }
        }

        /** Integers come back as Long, anything else as Double. */
        private Object readNumber() throws JsonException {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            boolean floating = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, pos);
            if (raw.length() == 0) {
                throw new JsonException("expected a value at offset " + start);
            }
            try {
                if (!floating) {
                    return Long.valueOf(Long.parseLong(raw));
                }
            } catch (NumberFormatException overflow) {
                // Falls through to double, which is the best we can do.
            }
            try {
                return Double.valueOf(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                throw new JsonException("bad number '" + raw + "'");
            }
        }
    }

    // --- writing ------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            // JSON has no way to spell these; emitting them produces a document
            // no conforming parser will read back.
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.floor(d) && !Double.isInfinite(d)
                    && Math.abs(d) < 1e15) {
                sb.append((long) d);   // 5.0 -> "5", which reads better
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    // Control characters must be escaped; everything else can go
                    // out as UTF-8 literally.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // --- convenience accessors ----------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object container, String key) {
        if (!(container instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) container).get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static String str(Object container, String key) {
        if (!(container instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) container).get(key);
        return (v instanceof String) ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    public static Integer integer(Object container, String key) {
        if (!(container instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) container).get(key);
        if (v instanceof Number) {
            return Integer.valueOf(((Number) v).intValue());
        }
        // Tolerate a numeric string: some clients stringify tool arguments.
        if (v instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt(((String) v).trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Ordered map, so generated JSON keeps a stable, readable field order. */
    public static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }

    public static List<Object> list() {
        return new ArrayList<Object>();
    }
}
