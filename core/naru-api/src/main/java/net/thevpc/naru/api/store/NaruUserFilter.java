package net.thevpc.naru.api.store;

/**
 * A three-state filter over the opaque {@code user} field: any user, nobody
 * ({@code user == null}, its own namespace) or one specific user.
 *
 * <p>Shared by project and session queries so that "--no-user" means the same thing on
 * both sides of the tree.
 */
public final class NaruUserFilter {

    private enum Mode {ANY, NONE, EXACT}

    private final Mode mode;
    private final String value;

    private NaruUserFilter(Mode mode, String value) {
        this.mode = mode;
        this.value = value;
    }

    /** No user constraint. */
    public static NaruUserFilter any() {
        return new NaruUserFilter(Mode.ANY, null);
    }

    /** Only records whose user is null. */
    public static NaruUserFilter none() {
        return new NaruUserFilter(Mode.NONE, null);
    }

    /** Only records whose user equals this value (trimmed; blank means {@link #none()}). */
    public static NaruUserFilter ofName(String user) {
        if (user == null || user.trim().isEmpty()) {
            return none();
        }
        return new NaruUserFilter(Mode.EXACT, user.trim());
    }

    public boolean matches(String user) {
        switch (mode) {
            case ANY:
                return true;
            case NONE:
                return user == null;
            default:
                return value.equalsIgnoreCase(user == null ? null : user.trim());
        }
    }

    /** True when the filter admits null-user records; used by stores to prune scans. */
    public boolean admitsNone() {
        return mode != Mode.EXACT;
    }

    public boolean isExact() {
        return mode == Mode.EXACT;
    }

    public String exactValue() {
        return value;
    }

    @Override
    public String toString() {
        return mode == Mode.EXACT ? "user(" + value + ')' : mode.name();
    }
}
