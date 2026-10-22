package de.augmentia.oc.engine.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {}

    public static String encode(JsonValue value) {
        if (value == null) return "null";
        return switch (value) {
            case JsonValue.Null n -> "null";
            case JsonValue.Bool b -> Boolean.toString(b.value());
            case JsonValue.Num n -> n.value().toPlainString();
            case JsonValue.Str s -> quote(s.value());
            case JsonValue.Arr a -> {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (JsonValue v : a.values()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(encode(v));
                }
                sb.append("]");
                yield sb.toString();
            }
            case JsonValue.Obj o -> {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, JsonValue> e : o.values().entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(quote(e.getKey())).append(":").append(encode(e.getValue()));
                }
                sb.append("}");
                yield sb.toString();
            }
        };
    }

    public static Result<JsonValue> parse(String text) {
        if (text == null) {
            return new Result.Err<>(new DecodeError("$", "input is null"));
        }
        Parser p = new Parser(text);
        try {
            JsonValue v = p.parseValue();
            p.skipWs();
            if (p.pos < p.text.length()) {
                return new Result.Err<>(new DecodeError("$", "trailing characters"));
            }
            return new Result.Ok<>(v);
        } catch (ParseException e) {
            return new Result.Err<>(new DecodeError(e.path, e.getMessage()));
        }
    }

    public static Result<JsonValue> decode(String text, Schema schema) {
        Result<JsonValue> parsed = parse(text);
        if (parsed instanceof Result.Err<JsonValue> e) return e;
        return decode(((Result.Ok<JsonValue>) parsed).value(), schema);
    }

    public static Result<JsonValue> decode(JsonValue value, Schema schema) {
        return validate(value, schema, "$");
    }

    private static Result<JsonValue> validate(JsonValue value, Schema schema, String path) {
        return switch (schema) {
            case Schema.Str s -> value instanceof JsonValue.Str
                    ? new Result.Ok<>(value)
                    : new Result.Err<>(new DecodeError(path, "expected string"));
            case Schema.Int s -> {
                if (value instanceof JsonValue.Num n && n.value().scale() <= 0) {
                    try {
                        n.value().intValueExact();
                        yield new Result.Ok<>(value);
                    } catch (ArithmeticException ex) {
                        yield new Result.Err<>(new DecodeError(path, "expected int"));
                    }
                }
                yield new Result.Err<>(new DecodeError(path, "expected int"));
            }
            case Schema.Long s -> {
                if (value instanceof JsonValue.Num n && n.value().scale() <= 0) {
                    try {
                        n.value().longValueExact();
                        yield new Result.Ok<>(value);
                    } catch (ArithmeticException ex) {
                        yield new Result.Err<>(new DecodeError(path, "expected long"));
                    }
                }
                yield new Result.Err<>(new DecodeError(path, "expected long"));
            }
            case Schema.Bool s -> value instanceof JsonValue.Bool
                    ? new Result.Ok<>(value)
                    : new Result.Err<>(new DecodeError(path, "expected boolean"));
            case Schema.Num s -> value instanceof JsonValue.Num
                    ? new Result.Ok<>(value)
                    : new Result.Err<>(new DecodeError(path, "expected number"));
            case Schema.Arr a -> {
                if (!(value instanceof JsonValue.Arr arr)) {
                    yield new Result.Err<>(new DecodeError(path, "expected array"));
                }
                List<JsonValue> out = new ArrayList<>();
                int i = 0;
                for (JsonValue item : arr.values()) {
                    Result<JsonValue> r = validate(item, a.item(), path + "[" + i + "]");
                    if (r instanceof Result.Err<JsonValue> e) yield e;
                    out.add(((Result.Ok<JsonValue>) r).value());
                    i++;
                }
                yield new Result.Ok<>(new JsonValue.Arr(out));
            }
            case Schema.Obj o -> {
                if (!(value instanceof JsonValue.Obj obj)) {
                    yield new Result.Err<>(new DecodeError(path, "expected object"));
                }
                Map<String, JsonValue> out = new LinkedHashMap<>();
                for (Map.Entry<String, Schema> f : o.fields().entrySet()) {
                    String key = f.getKey();
                    Schema fs = f.getValue();
                    JsonValue fv = obj.values().get(key);
                    if (fv == null) {
                        if (fs instanceof Schema.Opt) {
                            continue;
                        }
                        yield new Result.Err<>(new DecodeError(path + "." + key, "missing required field"));
                    }
                    Schema inner = (fs instanceof Schema.Opt opt) ? opt.inner() : fs;
                    Result<JsonValue> r = validate(fv, inner, path + "." + key);
                    if (r instanceof Result.Err<JsonValue> e) yield e;
                    out.put(key, ((Result.Ok<JsonValue>) r).value());
                }
                if (!o.additionalProperties()) {
                    for (String key : obj.values().keySet()) {
                        if (!o.fields().containsKey(key)) {
                            yield new Result.Err<>(new DecodeError(path + "." + key, "unknown field"));
                        }
                    }
                } else {
                    for (Map.Entry<String, JsonValue> e : obj.values().entrySet()) {
                        if (!o.fields().containsKey(e.getKey())) {
                            out.put(e.getKey(), e.getValue());
                        }
                    }
                }
                yield new Result.Ok<>(new JsonValue.Obj(out));
            }
            case Schema.Opt s -> {
                if (value instanceof JsonValue.Null) {
                    yield new Result.Ok<>(value);
                }
                yield validate(value, s.inner(), path);
            }
            case Schema.Enum e -> {
                if (value instanceof JsonValue.Str s && e.values().contains(s.value())) {
                    yield new Result.Ok<>(value);
                }
                yield new Result.Err<>(new DecodeError(path, "expected one of " + e.values()));
            }
            case Schema.Union u -> {
                for (Schema opt : u.options()) {
                    Result<JsonValue> r = validate(value, opt, path);
                    if (r instanceof Result.Ok<JsonValue>) yield r;
                }
                yield new Result.Err<>(new DecodeError(path, "no union branch matched"));
            }
            case Schema.Any s -> new Result.Ok<>(value);
        };
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
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
        sb.append("\"");
        return sb.toString();
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) { this.text = text; }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        JsonValue parseValue() {
            skipWs();
            if (pos >= text.length()) throw new ParseException("$", "unexpected end");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        JsonValue parseObject() {
            expect('{');
            skipWs();
            Map<String, JsonValue> map = new LinkedHashMap<>();
            if (peek() == '}') { pos++; return new JsonValue.Obj(map); }
            while (true) {
                skipWs();
                if (peek() != '"') throw new ParseException(path(), "expected key");
                String key = parseStringRaw();
                skipWs();
                expect(':');
                JsonValue v = parseValue();
                map.put(key, v);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                throw new ParseException(path(), "expected , or }");
            }
            return new JsonValue.Obj(map);
        }

        JsonValue parseArray() {
            expect('[');
            skipWs();
            List<JsonValue> list = new ArrayList<>();
            if (peek() == ']') { pos++; return new JsonValue.Arr(list); }
            while (true) {
                JsonValue v = parseValue();
                list.add(v);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                throw new ParseException(path(), "expected , or ]");
            }
            return new JsonValue.Arr(list);
        }

        JsonValue parseString() {
            return new JsonValue.Str(parseStringRaw());
        }

        String parseStringRaw() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= text.length()) throw new ParseException(path(), "bad escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new ParseException(path(), "bad unicode");
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new ParseException(path(), "bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException(path(), "unterminated string");
        }

        JsonValue parseBool() {
            if (text.startsWith("true", pos)) { pos += 4; return new JsonValue.Bool(true); }
            if (text.startsWith("false", pos)) { pos += 5; return new JsonValue.Bool(false); }
            throw new ParseException(path(), "expected boolean");
        }

        JsonValue parseNull() {
            if (text.startsWith("null", pos)) { pos += 4; return new JsonValue.Null(); }
            throw new ParseException(path(), "expected null");
        }

        JsonValue parseNumber() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '-' || text.charAt(pos) == '+' || text.charAt(pos) == '.' || text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
            }
            if (start == pos) throw new ParseException(path(), "expected number");
            String num = text.substring(start, pos);
            try {
                return new JsonValue.Num(new BigDecimal(num));
            } catch (NumberFormatException ex) {
                throw new ParseException(path(), "bad number");
            }
        }

        char peek() {
            skipWs();
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        void expect(char c) {
            skipWs();
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw new ParseException(path(), "expected '" + c + "'");
            }
            pos++;
        }

        String path() { return "$"; }
    }

    private static final class ParseException extends RuntimeException {
        final String path;
        ParseException(String path, String message) {
            super(message);
            this.path = path;
        }
    }
}