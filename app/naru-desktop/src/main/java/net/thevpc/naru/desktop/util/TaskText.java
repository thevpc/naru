package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.task.NaruTaskStackFrame;
import net.thevpc.naru.api.task.NaruTaskStackItem;
import net.thevpc.naru.desktop.model.NaruSessionModel.TaskDetail;
import net.thevpc.naru.desktop.model.NaruSessionModel.TaskRow;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Text for the task monitor: one line per row, and the whole detail block for the selected task.
 *
 * <p>Kept separate from the widgets for the same reason as {@code PermissionsText} and
 * {@code ToolCardText}: the formatting is the part worth testing, and it should not need a session,
 * a window, or a scheduler to prove. The wording follows the terminal's own {@code /task} directive
 * — same flags, same {@code [%s] %s <%s> %s} frame line — so the two hosts read alike and a user
 * who has seen one recognises the other.
 */
public final class TaskText {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private TaskText() {
    }

    /** One list row, indented by tree depth. */
    public static String rowLabel(TaskRow r) {
        return rowLabel(r, Instant.now());
    }

    /**
     * Testable form: {@code now} fixes the age, so a row that has not changed produces the same
     * label twice and the pane can keep its "nothing changed, don't rebuild" fast path.
     */
    public static String rowLabel(TaskRow r, Instant now) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.depth(); i++) {
            sb.append("   ");
        }
        if (r.depth() > 0) {
            sb.append("└ ");
        }
        sb.append('#').append(r.id());
        sb.append(' ').append(r.status() == null ? "GONE" : r.status().name());
        String flags = flags(r);
        if (!flags.isEmpty()) {
            sb.append(" [").append(flags).append(']');
        }
        if (r.parentId() > 0) {
            sb.append(" ←#").append(r.parentId());
        }
        if (r.created() != null && now != null) {
            sb.append(' ').append(age(r.created(), now));
        }
        if (r.name() != null && !r.name().isBlank()) {
            sb.append(' ').append(r.name());
        }
        if (r.error() != null) {
            sb.append(" — ").append(r.error());
        }
        return sb.toString();
    }

    /**
     * The {@code /task list} flags this row can answer without reading the task again: {@code H} for
     * held, {@code F} for foreground, in the terminal's order.
     * <p>
     * A letter is absent rather than dotted out, unlike the terminal's {@code "H.F.I."}: a monitor
     * row is scanned at a glance, and {@code [..]} on every line says nothing. The interactive and
     * status letters need {@code taskMode()}, so they are in the detail block, where the terminal's
     * full spelling is kept.
     */
    public static String flags(TaskRow r) {
        StringBuilder sb = new StringBuilder(2);
        if (r.held()) {
            sb.append('H');
        }
        if (r.foreground()) {
            sb.append('F');
        }
        return sb.toString();
    }

    /** A short age, the way the terminal says "3s" rather than an ISO timestamp. */
    public static String age(Instant created, Instant now) {
        if (created == null || now == null) {
            return "";
        }
        long seconds = Math.max(0, Duration.between(created, now).getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m" + (seconds % 60 == 0 ? "" : "+" + (seconds % 60) + "s");
        }
        return (seconds / 3600) + "h" + (seconds % 3600) / 60 + "m";
    }

    /**
     * The detail block for the selected task: identity, timings, then the frames and the stacktrace
     * the terminal would print for {@code /task frames} and {@code /task stacktrace}.
     */
    public static String detail(TaskDetail d) {
        return detail(d, Instant.now());
    }

    public static String detail(TaskDetail d, Instant now) {
        if (d == null) {
            return "no task selected";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Task #").append(d.id());
        if (d.name() != null && !d.name().isBlank()) {
            sb.append(" · ").append(d.name());
        }
        sb.append('\n');
        sb.append(pad("status")).append(d.status() == null ? "—" : d.status().name())
                .append("   flags [").append(flagLine(d)).append("]\n");
        sb.append(pad("parent")).append(d.parentId() > 0 ? "#" + d.parentId() : "— (root)")
                .append("   pc ").append(d.pc() < 0 ? "—" : String.valueOf(d.pc())).append('\n');
        sb.append(pad("created")).append(d.created() == null ? "—" : CLOCK.format(d.created()))
                .append(d.created() == null || now == null ? "" : " (" + age(d.created(), now) + " ago)")
                .append("   ran ").append(d.ran() == null ? "still running" : String.valueOf(d.ran())).append('\n');
        sb.append(pad("mode")).append(d.taskMode() == null ? "—" : d.taskMode().name())
                .append("   scheduler ").append(d.schedulerMode() == null ? "—" : d.schedulerMode().name())
                .append("   model ").append(d.model() == null ? "none selected" : d.model()).append('\n');
        if (d.error() != null) {
            sb.append(pad("error")).append(d.error()).append('\n');
        }
        sb.append(frames(d.frames()));
        sb.append(stacktrace(d.stacktrace()));
        return sb.toString();
    }

    private static String flagLine(TaskDetail d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.held() ? "held" : "free");
        if (d.foreground()) {
            sb.append("  fg");
        }
        if (d.killRequested()) {
            sb.append("  kill-requested");
        }
        if (d.interruptRequested()) {
            sb.append("  interrupt-requested");
        }
        return sb.toString();
    }

    /** The terminal's frame block: {@code [1] 3 <routine> instruction}, then params and locals. */
    public static String frames(List<NaruTaskStackFrame> frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nFrames\n");
        if (frames == null || frames.isEmpty()) {
            return sb.append("  (none)\n").toString();
        }
        int index = 1;
        for (NaruTaskStackFrame f : frames) {
            sb.append(String.format("  [%d] %s <%s> %s%n", index,
                    f.index() < 0 ? "?" : String.valueOf(f.index()),
                    f.name() == null ? "?" : f.name(),
                    f.instruction() == null ? "" : f.instruction()));
            appendVars(sb, "params", f.params());
            appendVars(sb, "local vars", f.localVars());
            index++;
        }
        return sb.toString();
    }

    public static String stacktrace(List<NaruTaskStackItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nStacktrace\n");
        if (items == null || items.isEmpty()) {
            return sb.append("  (none)\n").toString();
        }
        int index = 1;
        for (NaruTaskStackItem i : items) {
            sb.append(String.format("  [%d] %s <%s> %s%n", index,
                    i.index() < 0 ? "?" : String.valueOf(i.index()),
                    i.name() == null ? "?" : i.name(),
                    i.instruction() == null ? "" : i.instruction()));
            index++;
        }
        return sb.toString();
    }

    private static void appendVars(StringBuilder sb, String title, Map<String, Object> vars) {
        List<Map.Entry<String, Object>> entries = vars == null ? new ArrayList<>()
                : vars.entrySet().stream()
                .sorted(Comparator.comparing(x -> String.valueOf(x.getKey())))
                .toList();
        sb.append("      ").append(title).append(" : ").append(entries.size()).append('\n');
        for (Map.Entry<String, Object> e : entries) {
            sb.append("        ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
    }

    private static String pad(String label) {
        StringBuilder sb = new StringBuilder("  ").append(label).append(' ');
        while (sb.length() < 12) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
