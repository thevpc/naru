package net.thevpc.naru.api.model;

/**
 * What a compactor does with bulky {@code tool} items while summarizing.
 *
 * <p>Tool output is usually the majority of a long agent conversation, and usually the
 * least worth re-reading verbatim. How much of it survives is the single biggest lever on
 * the size of a summary, which is why it is a separate setting from the level rather than
 * buried inside it.
 */
public enum NaruToolOutputPolicy {

    /**
     * Drop tool output entirely, keeping only the call itself. The smallest summary, and
     * the only one that can lose information the model will need.
     */
    DROP,

    /**
     * Keep tool output that looks like a failure, shorten the rest. A failed call usually
     * explains what happens next, so it is worth more per token than a successful one.
     */
    KEEP_ERRORS,

    /**
     * Keep every tool call and shorten each output to its opening. The most faithful of the
     * three, and still far smaller than the original.
     */
    TRUNCATE;

    /**
     * Parses a policy name, case-insensitively, or returns null.
     *
     * <p>Null rather than a default so a caller can tell "not set" from "set to the default":
     * an explicit {@code toolOutputs} on a directive has to be distinguishable from none
     * being given, or the level's own policy would silently win.
     */
    public static NaruToolOutputPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        for (NaruToolOutputPolicy p : values()) {
            if (p.name().equalsIgnoreCase(v)) {
                return p;
            }
        }
        // tolerate the kebab and space spellings a user is likely to type
        String normalized = v.replace('-', '_').replace(' ', '_');
        for (NaruToolOutputPolicy p : values()) {
            if (p.name().equalsIgnoreCase(normalized)) {
                return p;
            }
        }
        return null;
    }
}