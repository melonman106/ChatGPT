package com.eaglercraft.patcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Strict parser and canonical writer for patcher JSON. */
final class Json {
    private Json() {
    }

    static Object parse(byte[] bytes, String label) {
        try {
            Parser parser = new Parser(new String(bytes, StandardCharsets.UTF_8), label);
            Object value = parser.value();
            parser.whitespace();
            if (!parser.atEnd()) {
                throw parser.error("trailing data");
            }
            return value;
        } catch (JsonError ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new JsonError(label + ": invalid UTF-8/JSON", ex);
        }
    }

    static byte[] canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        out.append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            string(string, out);
        } else if (value instanceof Long number) {
            out.append(number);
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new JsonError("JSON object key is not a string");
                }
                sorted.put(key, entry.getValue());
            }
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            throw new JsonError("unsupported JSON value: " + value.getClass());
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }

    static final class JsonError extends RuntimeException {
        JsonError(String message) {
            super(message);
        }

        JsonError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Parser {
        private final String text;
        private final String label;
        private int index;

        Parser(String text, String label) {
            this.text = text;
            this.label = label;
        }

        boolean atEnd() {
            return index == text.length();
        }

        void whitespace() {
            while (!atEnd()) {
                char ch = text.charAt(index);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        Object value() {
            whitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> stringValue();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object object() {
            index++;
            java.util.LinkedHashMap<String, Object> object = new java.util.LinkedHashMap<>();
            whitespace();
            if (take('}')) {
                return object;
            }
            while (true) {
                whitespace();
                if (atEnd() || text.charAt(index) != '"') {
                    throw error("object key must be a string");
                }
                String key = stringValue();
                if (object.containsKey(key)) {
                    throw error("duplicate object key: " + key);
                }
                whitespace();
                require(':');
                Object value = value();
                object.put(key, value);
                whitespace();
                if (take('}')) {
                    return object;
                }
                require(',');
            }
        }

        private Object array() {
            index++;
            ArrayList<Object> array = new ArrayList<>();
            whitespace();
            if (take(']')) {
                return array;
            }
            while (true) {
                array.add(value());
                whitespace();
                if (take(']')) {
                    return array;
                }
                require(',');
            }
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, index)) {
                throw error("invalid literal");
            }
            index += expected.length();
            return value;
        }

        private Long number() {
            int start = index;
            if (take('-')) {
                if (atEnd()) {
                    throw error("invalid number");
                }
            }
            if (take('0')) {
                if (!atEnd() && Character.isDigit(text.charAt(index))) {
                    throw error("leading zero in number");
                }
            } else {
                if (atEnd() || !Character.isDigit(text.charAt(index))) {
                    throw error("invalid number");
                }
                while (!atEnd() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (!atEnd() && (text.charAt(index) == '.' || text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                throw error("floating point values are not allowed in the source bundle");
            }
            try {
                return Long.valueOf(text.substring(start, index));
            } catch (NumberFormatException ex) {
                throw error("number is outside the supported range");
            }
        }

        private String stringValue() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (!atEnd()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch < 0x20) {
                    throw error("control character in string");
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (atEnd()) {
                    throw error("unfinished string escape");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicodeEscape());
                    default -> throw error("unknown string escape");
                }
            }
            throw error("unterminated string");
        }

        private char unicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("short unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private boolean take(char expected) {
            if (!atEnd() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        JsonError error(String message) {
            return new JsonError(label + " at byte " + index + ": " + message);
        }
    }
}
