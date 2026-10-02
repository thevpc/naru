package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.time.NDuration;

import java.time.Instant;
import java.util.Objects;

/**
 * How hard a compactor worked to shrink what it covered.
 *
 * <p>A level is a preset: it picks a target size and what happens to bulky tool output.
 * Anything set explicitly on {@link NaruSummaryOptions} overrides the preset, so the level
 * never has to be restated in full to adjust one thing.
 */
public enum NaruSummaryLevel {

    /**
     * Keep most of the shape of the original. Target roughly 25% of the covered size, and
     * shorten tool output rather than dropping it.
     *
     * <p>For a conversation that is merely getting long and is still cheap to send.
     */
    LIGHT(0.25d, NaruToolOutputPolicy.TRUNCATE),

    /**
     * The default balance. Target roughly 12% of the covered size, keeping tool output
     * only where it failed -- a failed call usually explains what happens next.
     */
    NORMAL(0.12d, NaruToolOutputPolicy.KEEP_ERRORS),

    /**
     * Keep only what a reader would need to continue: goals, decisions, current state,
     * open TODOs, and the file paths and identifiers that refer to them. Target roughly 5%.
     *
     * <p>Verbose tool output is dropped entirely. This is lossy by design and the summary
     * item records that, so a reader can see that it happened.
     */
    AGGRESSIVE(0.05d, NaruToolOutputPolicy.DROP);

    private final double targetRatio;
    private final NaruToolOutputPolicy toolOutputs;

    NaruSummaryLevel(double targetRatio, NaruToolOutputPolicy toolOutputs) {
        this.targetRatio = targetRatio;
        this.toolOutputs = toolOutputs;
    }

    /** Fraction of the covered size this level aims for, as a token-equivalent ratio. */
    public double targetRatio() {
        return targetRatio;
    }

    public NaruToolOutputPolicy toolOutputs() {
        return toolOutputs;
    }

    /** The next stricter level, or this one when already the strictest. */
    public NaruSummaryLevel stricter() {
        switch (this) {
            case LIGHT:
                return NORMAL;
            case NORMAL:
                return AGGRESSIVE;
            case AGGRESSIVE:
            default:
                return AGGRESSIVE;
        }
    }

    public static NaruSummaryLevel parse(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase()) {
            case "light":
                return LIGHT;
            case "normal":
                return NORMAL;
            case "aggressive":
                return AGGRESSIVE;
            default:
                return null;
        }
    }
}