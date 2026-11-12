package net.thevpc.naru.ext.assess;

import java.util.Collection;
import java.util.Map;

/** Just enough JSON to persist results: writing is hand-rolled, reading goes through Nuts. */
final class AssessJson {
    private AssessJson() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
                sb.append(0);
            } else {
                sb.append(v);
            }
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            int i = 0;
            for (Object o : c) {
                pad(sb, indent + 1);
                write(sb, o, indent + 1);
                sb.append(++i < c.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) {
            sb.append("  ");
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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

    static String str(Map<?, ?> m, String key) {
        Object o = m.get(key);
        return o == null ? null : String.valueOf(o);
    }

    static double num(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : 0;
    }
}
