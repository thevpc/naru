package net.thevpc.naru.ext.assess;

import java.util.function.Function;

/**
 * One scored criterion of a task. The evaluator returns the fraction of the points earned
 * (0..1) plus a short human-readable detail, so a check can award partial credit.
 */
public final class AssessCheck {
    /** Outcome of evaluating a check. */
    public record Verdict(double fraction, String detail) {
        public static Verdict pass() {
            return new Verdict(1.0, "ok");
        }

        public static Verdict fail(String detail) {
            return new Verdict(0.0, detail);
        }

        public static Verdict partial(double fraction, String detail) {
            return new Verdict(Math.max(0, Math.min(1, fraction)), detail);
        }
    }

    private final String id;
    private final String description;
    private final int points;
    private final Function<AssessContext, Verdict> evaluator;
    private final boolean gated;

    public AssessCheck(String id, String description, int points, Function<AssessContext, Verdict> evaluator) {
        this(id, description, points, false, evaluator);
    }

    /**
     * @param gated a hygiene check that an untouched project already satisfies (it compiles, tests are
     *              unmodified...). Its points are scaled by the share of hidden acceptance tests passed,
     *              so doing nothing cannot earn them.
     */
    public AssessCheck(String id, String description, int points, boolean gated, Function<AssessContext, Verdict> evaluator) {
        this.gated = gated;
        this.id = id;
        this.description = description;
        this.points = points;
        this.evaluator = evaluator;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public int points() {
        return points;
    }

    public boolean gated() {
        return gated;
    }

    public Verdict evaluate(AssessContext context) {
        try {
            return evaluator.apply(context);
        } catch (RuntimeException e) {
            return Verdict.fail("check crashed: " + e);
        }
    }
}
