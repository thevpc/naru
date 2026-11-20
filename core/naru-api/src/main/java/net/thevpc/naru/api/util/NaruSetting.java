package net.thevpc.naru.api.util;

/**
 * One field of a patch object: either <b>unset</b> (leave the stored value alone) or
 * <b>set</b> to a value — including an explicit {@code null}, which means "clear it".
 *
 * <p>Kernel vocabulary, not project vocabulary: a session-title patch, a plan-item patch and
 * a project patch all need the same three-valued field, and none of them may pull in the
 * others to get it.
 *
 * <p>The distinction is why this wrapper exists: a bare {@code null} cannot say both
 * "not mentioned" and "set to nothing", and a patch that confuses the two would silently
 * clear a user's owner whenever the caller only meant to rename the row.
 */
public final class NaruSetting<T> {

    private static final NaruSetting<?> UNSET = new NaruSetting<>(null, false);

    private final T value;
    private final boolean set;

    private NaruSetting(T value, boolean set) {
        this.value = value;
        this.set = set;
    }

    /** Leave the field as it is. */
    @SuppressWarnings("unchecked")
    public static <T> NaruSetting<T> unset() {
        return (NaruSetting<T>) UNSET;
    }

    /** Write the field. A {@code null} value is an explicit clear, not an omission. */
    public static <T> NaruSetting<T> set(T value) {
        return new NaruSetting<>(value, true);
    }

    public boolean isSet() {
        return set;
    }

    /** The value to write, only meaningful when {@link #isSet()}. */
    public T value() {
        return value;
    }

    @Override
    public String toString() {
        return set ? "set(" + value + ')' : "unset";
    }
}
