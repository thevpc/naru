package net.thevpc.naru.api.model;

import net.thevpc.nuts.text.NMsg;

/**
 * How much of the end of a conversation must be kept verbatim, everything older becoming
 * the input to compaction.
 *
 * <p>Three units, because "keep recent context" has no single right answer and guessing
 * one is the usual cause of a compaction that throws away what the model needed:
 *
 * <ul>
 *   <li><b>items</b> -- exact and predictable, but says nothing about size: four items can
 *       be four words or four hundred thousand tokens.</li>
 *   <li><b>turns</b> -- the natural unit of a conversation, and the one to reach for by
 *       default. A turn is one user message plus everything the agent did in response.</li>
 *   <li><b>tokens</b> -- the only unit that actually bounds the request, and the right one
 *       when the window has to fit a specific model.</li>
 * </ul>
 *
 * <p>The cut is then moved earlier if necessary so it never splits a tool call from its
 * result, or a thinking segment from its answer -- so a window of N is a lower bound on the
 * kept count, never an upper one.
 */
public final class NaruWindowSpec {

    /** Which of {@link #amount()} is counted. */
    public enum Unit {
        /** No window: the whole context view is the input to compaction. */
        NONE,
        ITEMS,
        TURNS,
        TOKENS
    }

    private static final NaruWindowSpec NONE = new NaruWindowSpec(Unit.NONE, 0);
    private static final NaruWindowSpec ALL = new NaruWindowSpec(Unit.ITEMS, Integer.MAX_VALUE);

    private final Unit unit;
    private final int amount;

    private NaruWindowSpec(Unit unit, int amount) {
        this.unit = unit;
        this.amount = amount;
    }

    /** Keep nothing: the entire context view would be compacted. */
    public static NaruWindowSpec none() {
        return NONE;
    }

    /** Keep everything: nothing would be compacted. Useful for measuring and for tests. */
    public static NaruWindowSpec all() {
        return ALL;
    }

    public static NaruWindowSpec lastItems(int n) {
        return n <= 0 ? NONE : new NaruWindowSpec(Unit.ITEMS, n);
    }

    public static NaruWindowSpec lastTurns(int n) {
        return n <= 0 ? NONE : new NaruWindowSpec(Unit.TURNS, n);
    }

    public static NaruWindowSpec lastTokens(long n) {
        return n <= 0 ? NONE : new NaruWindowSpec(Unit.TOKENS, (int) Math.min(n, Integer.MAX_VALUE));
    }

    public Unit unit() {
        return unit;
    }

    public int amount() {
        return amount;
    }

    public boolean isNone() {
        return unit == Unit.NONE;
    }

    public boolean isAll() {
        return unit == Unit.ITEMS && amount == Integer.MAX_VALUE;
    }

    /**
     * Parses a config or directive value such as {@code 4turns}, {@code lastItems=20},
     * {@code 2000tokens} or {@code none}.
     *
     * <p>Accepts the bare unit suffix and the {@code last<Unit>=} spelling, because both
     * appear in configuration and a caller should not have to know which one this parser
     * prefers.
     */
    public static NaruWindowSpec parse(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim().toLowerCase();
        if (s.isEmpty() || s.equals("none")) {
            return NONE;
        }
        if (s.equals("all")) {
            return ALL;
        }
        String digits;
        String unitName;
        // '=' is tested first, and must be: in "lastitems=20" the very first character is a
        // non-digit, so scanning for the end of the number first would read the whole string
        // as the unit name and the empty string as the number.
        int eq = s.indexOf('=');
        if (eq >= 0) {
            unitName = s.substring(0, eq).trim();
            digits = s.substring(eq + 1).trim();
        } else {
            int idx = firstNonDigit(s);
            if (idx < 0) {
                throw new IllegalArgumentException(NMsg.ofC(
                        "invalid keep spec '%s': expected a number and a unit, e.g. 4turns", value).toString());
            }
            digits = s.substring(0, idx);
            unitName = s.substring(idx);
        }
        if (unitName.startsWith("last")) {
            unitName = unitName.substring("last".length());
        }
        int n;
        try {
            n = Integer.parseInt(digits.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(NMsg.ofC(
                    "invalid keep spec '%s': '%s' is not a number", value, digits).toString());
        }
        switch (unitName) {
            case "item":
            case "items":
                return lastItems(n);
            case "turn":
            case "turns":
                return lastTurns(n);
            case "token":
            case "tokens":
                return lastTokens(n);
            default:
                throw new IllegalArgumentException(NMsg.ofC(
                        "invalid keep spec '%s': unknown unit '%s', expected items, turns or tokens",
                        value, unitName).toString());
        }
    }

    private static int firstNonDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return i;
            }
        }
        return -1;
    }

    /** The bare-unit spelling of this spec, as accepted by {@link #parse}. */
    public String toCompactString() {
        switch (unit) {
            case NONE:
                return "none";
            case ITEMS:
                return amount + "items";
            case TURNS:
                return amount + "turns";
            case TOKENS:
            default:
                return amount + "tokens";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruWindowSpec)) {
            return false;
        }
        NaruWindowSpec that = (NaruWindowSpec) o;
        return unit == that.unit && amount == that.amount;
    }

    @Override
    public int hashCode() {
        return unit.hashCode() * 31 + amount;
    }

    @Override
    public String toString() {
        switch (unit) {
            case NONE:
                return "none";
            case ITEMS:
                return isAll() ? "all" : ("lastItems=" + amount);
            case TURNS:
                return "lastTurns=" + amount;
            case TOKENS:
            default:
                return "lastTokens=" + amount;
        }
    }
}