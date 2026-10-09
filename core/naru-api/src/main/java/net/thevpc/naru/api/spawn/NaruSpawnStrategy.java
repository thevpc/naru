package net.thevpc.naru.api.spawn;

import net.thevpc.nuts.util.NOptional;

/**
 * How much of the parent's conversation context a spawned task starts with.
 * <p>
 * Mapped onto the existing {@code /start} behaviour:
 * <ul>
 *   <li>{@link #NONE} — the child starts with an empty conversation. This is what
 *       {@code /start} has always done.</li>
 *   <li>{@link #FORK} — the child starts from a full copy of the parent's conversation
 *       (a fork). A fork also inherits the parent's tags by default.</li>
 *   <li>{@link #WINDOW} — the child starts from the last {@code windowTurns()} turns of
 *       the parent's conversation.</li>
 *   <li>{@link #SUMMARY} — the child starts from a summary of the parent's conversation.
 *       Requires a context compactor (e.g. {@code naru-tools-compact}); without one the
 *       spawn degrades to the last turn and says so in a spawn-time warning.</li>
 * </ul>
 * The resolved strategy is recorded on the {@code TaskSpawned} event.
 */
public enum NaruSpawnStrategy {

    NONE, FORK, WINDOW, SUMMARY;

    /**
     * Whether this strategy implies inheriting the parent's granted tags by default
     * (the {@code /start} fork default). Explicit inherit flags override the implication;
     * {@link #NONE} never inherits.
     */
    public boolean impliesTagsInherit() {
        return this != NONE;
    }

    /**
     * Parses a strategy from a flag value. Accepts {@code none}, {@code fork},
     * {@code summary} and any {@code window*} spelling (the turn count is parsed by the
     * caller). Returns empty for anything else.
     */
    public static NOptional<NaruSpawnStrategy> parse(String value) {
        if (value == null) {
            return NOptional.ofNullable(null);
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return NOptional.ofNullable(null);
        }
        String n = s.toLowerCase();
        if (n.startsWith("window")) {
            return NOptional.of(WINDOW);
        }
        switch (n) {
            case "none":
                return NOptional.of(NONE);
            case "fork":
                return NOptional.of(FORK);
            case "summary":
                return NOptional.of(SUMMARY);
            default:
                return NOptional.ofNullable(null);
        }
    }
}