package dev.bigcore;

import java.util.*;

/** Small JSON5 reader for the fixed, dependency-free configuration format. */
final class Json5 {
    private Json5() { }

    static Object parse(String source) { return new Parser(source).parse(); }

    private static final class Parser {
        private final String source;
        private int position;

        private Parser(String source) { this.source = source; }

        private Object parse() {
            Object value = value();
            skip();
            if (position != source.length()) fail("Unexpected characters");
            return value;
        }

        private Object value() {
            skip();
            if (position >= source.length()) fail("Expected a value");
            return switch (source.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '\'', '"' -> string();
                default -> bareValue();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skip();
            if (take('}')) return result;
            while (true) {
                String key = key();
                skip();
                expect(':');
                result.put(key, value());
                skip();
                if (take('}')) return result;
                expect(',');
                skip();
                if (take('}')) return result;
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skip();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                skip();
                if (take(']')) return result;
                expect(',');
                skip();
                if (take(']')) return result;
            }
        }

        private String key() {
            skip();
            if (position >= source.length()) fail("Expected an object key");
            char current = source.charAt(position);
            if (current == '\'' || current == '"') return string();
            int start = position;
            while (position < source.length() && !Character.isWhitespace(source.charAt(position))
                    && source.charAt(position) != ':') position++;
            if (start == position) fail("Expected an object key");
            return source.substring(start, position);
        }

        private String string() {
            char quote = source.charAt(position++);
            StringBuilder result = new StringBuilder();
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == quote) return result.toString();
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (position >= source.length()) fail("Unterminated escape");
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append((char) Integer.parseInt(readExact(4), 16));
                    case '\n' -> { }
                    default -> result.append(escaped);
                }
            }
            fail("Unterminated string");
            return "";
        }

        private Object bareValue() {
            int start = position;
            while (position < source.length() && ",]}".indexOf(source.charAt(position)) < 0
                    && !Character.isWhitespace(source.charAt(position))) position++;
            String token = source.substring(start, position);
            if (token.equals("true")) return true;
            if (token.equals("false")) return false;
            if (token.equals("null")) return null;
            try {
                if (token.matches("[-+]?\\d+")) return Long.parseLong(token);
                return Double.parseDouble(token);
            } catch (NumberFormatException ignored) {
                fail("Invalid value: " + token);
                return null;
            }
        }

        private String readExact(int length) {
            if (position + length > source.length()) fail("Incomplete unicode escape");
            String value = source.substring(position, position + length);
            position += length;
            return value;
        }

        private void skip() {
            while (position < source.length()) {
                if (Character.isWhitespace(source.charAt(position))) { position++; continue; }
                if (source.startsWith("//", position)) {
                    position += 2;
                    while (position < source.length() && source.charAt(position) != '\n') position++;
                    continue;
                }
                if (source.startsWith("/*", position)) {
                    int end = source.indexOf("*/", position + 2);
                    if (end < 0) fail("Unterminated comment");
                    position = end + 2;
                    continue;
                }
                break;
            }
        }

        private boolean take(char expected) {
            if (position < source.length() && source.charAt(position) == expected) { position++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) fail("Expected '" + expected + "'");
        }

        private void fail(String message) { throw new IllegalArgumentException(message + " at character " + position); }
    }
}
