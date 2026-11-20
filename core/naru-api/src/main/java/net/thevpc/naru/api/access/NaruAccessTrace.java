package net.thevpc.naru.api.access;

import java.util.Objects;

/**
 * One rule weighed against one request, for {@code explain(...)}.
 *
 * <p>A denial is only useful if it can say what refused it. The trace is that
 * "why": for each rule in order, whether it matched and, when it did not, which
 * clause failed. It is produced only by explanation, never by an ordinary check,
 * so the fast path stays allocation-light.
 */
public final class NaruAccessTrace {

    private final NaruPermissionRule rule;
    private final boolean matched;
    private final String detail;

    public NaruAccessTrace(NaruPermissionRule rule, boolean matched, String detail) {
        this.rule = rule;
        this.matched = matched;
        this.detail = detail;
    }

    public NaruPermissionRule rule() {
        return rule;
    }

    public boolean matched() {
        return matched;
    }

    public String detail() {
        return detail;
    }

    @Override
    public String toString() {
        return (matched ? "MATCH " : "skip  ") + rule + (detail == null ? "" : "  -- " + detail);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruAccessTrace)) {
            return false;
        }
        NaruAccessTrace t = (NaruAccessTrace) o;
        return matched == t.matched && Objects.equals(rule, t.rule) && Objects.equals(detail, t.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rule, matched, detail);
    }
}
