package net.thevpc.naru.api.project;

import java.util.List;
import java.util.Map;

/**
 * The outcome of {@code moveSession}/{@code moveSessions}: per-session results, because
 * the operation is atomic per session and not across them — one failure never aborts the
 * others, and each failed session is named with its reason.
 *
 * @param moved     session ids whose {@code projectId} changed, as {@code id -> toProject}
 *                  (the to-value is null for a move to standalone)
 * @param unchanged session ids already in the target: a successful no-op
 * @param failed    session id to failure reason
 */
public final class NaruMoveReport {

    private final Map<String, String> moved;
    private final List<String> unchanged;
    private final Map<String, String> failed;

    public NaruMoveReport(Map<String, String> moved, List<String> unchanged, Map<String, String> failed) {
        // LinkedHashMap, not Map.copyOf: a move to standalone legitimately carries a null
        // "to-project" value, and an immutable map that rejects nulls would turn the most
        // ordinary detach into a NullPointerException
        this.moved = moved == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(moved));
        this.unchanged = unchanged == null ? List.of() : List.copyOf(unchanged);
        this.failed = failed == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(failed));
    }

    public static NaruMoveReport empty() {
        return new NaruMoveReport(Map.of(), List.of(), Map.of());
    }

    public Map<String, String> moved() {
        return moved;
    }

    public List<String> unchanged() {
        return unchanged;
    }

    public Map<String, String> failed() {
        return failed;
    }

    public boolean isSuccess() {
        return failed.isEmpty();
    }

    public int size() {
        return moved.size() + unchanged.size() + failed.size();
    }

    @Override
    public String toString() {
        return "NaruMoveReport{moved=" + moved.size() + ", unchanged=" + unchanged.size()
                + ", failed=" + failed.size() + '}';
    }
}
