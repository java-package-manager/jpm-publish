package dev.jpm.publish;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader (for `jpm info --json` and the Central Portal's answers). Objects keep their order. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.error("trailing content");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Invalid JSON (" + msg + ") at offset " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || Character.isDigit(c)) return num();
                throw error("unexpected '" + c + "'");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw error("expected " + w);
        i += w.length();
    }

    private Object num() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        return new BigDecimal(s.substring(start, i));
    }

    private String str() {
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Map<String, Object> obj() {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (s.charAt(i) != '"') throw error("expected a key");
            String k = str();
            ws();
            if (s.charAt(i++) != ':') throw error("expected ':'");
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> arr() {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }
}
