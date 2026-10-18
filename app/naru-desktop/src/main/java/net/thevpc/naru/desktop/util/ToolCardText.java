package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.agent.NaruToolCallEvent;

import java.util.Collection;
import java.util.Map;

/**
 * The text of a tool card, built in one place so the transcript row and the tools panel say the
 * same thing about the same call.
 * <p>
 * This is the whole point of E1 for the user: the terminal shows
 * {@code ImplNaruUtils.abbreviate(result, 300)} and a desktop reader cannot tell a 300-character
 * result from a 300 000-character one. Here the result goes in <em>whole</em> — {@link #cardBody}
 * deliberately has no length cap. What is short is the header, because the arguments map can hold
 * a pasted file and a header must stay on one line.
 */
public final class ToolCardText {

    /** Longest single argument value drawn in a header. */
    public static final int MAX_ARGUMENT_TEXT = 160;

    private ToolCardText() {
    }

    /**
     * The line the card grows by when {@code event} arrives. Ends in a newline so a block made of
     * these lines stays readable as plain text, which is what a live row is drawn as.
     */
    public static String header(NaruToolCallEvent event) {
        if (event == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        switch (event.kind()) {
            case STARTED -> sb.append("▶ ").append(tool(event)).append(' ')
                    .append(arguments(event.arguments())).append('\n');
            case PARKED -> sb.append("… ").append(tool(event))
                    .append(" is waiting for an answer\n");
            case RESULT -> sb.append("✔ ").append(tool(event)).append(settled(event)).append('\n');
            case FAILED -> sb.append("✗ ").append(tool(event)).append(settled(event)).append('\n');
            default -> {
                return "";
            }
        }
        return sb.toString();
    }

    /**
     * The full, uncapped result text, or null when there is nothing to show yet. This is the part
     * the console truncates and this host must not.
     */
    public static String cardBody(NaruToolCallEvent event) {
        if (event == null || event.result() == null) {
            return null;
        }
        return event.result();
    }

    /** One line, whatever the arguments are: {@code {path:"a.txt",limit:40}}. */
    public static String arguments(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append(':').append(value(e.getValue()));
            }
            String rendered = sb.append('}').toString();
            return clip(rendered, rendered.length());
        }
        if (v instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(value(o));
            }
            String rendered = sb.append(']').toString();
            return clip(rendered, rendered.length());
        }
        if (v instanceof Object[] a) {
            String rendered = java.util.Arrays.toString(a);
            return clip(rendered, rendered.length());
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        // a string, and anything a model or a Nuts JSON leaf hands us that is not a container:
        // drawn quoted so an empty value is visibly empty rather than looking like a missing key
        String raw = String.valueOf(v);
        return clip('"' + quote(raw) + '"', raw.length());
    }

    private static String quote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Keep a header to one readable line. The number in the note is the length of the <em>value</em>
     * that was cut, not of the drawing, so "…(10000 chars)" means the tool was handed ten thousand
     * characters here — which is the fact a user needs.
     */
    private static String clip(String rendered, int valueLength) {
        if (rendered.length() <= MAX_ARGUMENT_TEXT) {
            return rendered;
        }
        return rendered.substring(0, MAX_ARGUMENT_TEXT) + "…(" + valueLength + " chars)";
    }

    private static String tool(NaruToolCallEvent event) {
        String name = event.toolName();
        return name == null || name.isBlank() ? "(unnamed tool)" : name;
    }

    /** {@code " · 1.2 s · 4 123 chars"}, or the reason a duration is not known. */
    private static String settled(NaruToolCallEvent event) {
        StringBuilder sb = new StringBuilder();
        String d = duration(event.durationMillis());
        if (d != null) {
            sb.append(" · ").append(d);
        }
        if (event.result() != null) {
            sb.append(" · ").append(event.result().length()).append(" chars");
        }
        if (event.callId() != null && !event.callId().isBlank()) {
            sb.append(" · ").append(event.callId());
        }
        return sb.toString();
    }

    /** Milliseconds as a human reads them, or null when nothing was measured. */
    public static String duration(long millis) {
        if (millis < 0) {
            return null;
        }
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format("%.1f s", millis / 1000.0);
    }
}
