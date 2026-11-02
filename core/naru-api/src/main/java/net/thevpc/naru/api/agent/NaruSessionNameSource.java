package net.thevpc.naru.api.agent;

/**
 * Who owns a session's name, and therefore who may change it next.
 *
 * <p>The uuid is the session's identity; the name is only a human label, so uniqueness
 * is a display concern and never an integrity constraint. What has to be durable is
 * <em>authority</em>: without it, an automatic title that arrives late overwrites a name
 * the user typed a minute earlier, and nothing in the record says that the user spoke
 * last.
 *
 * <p>The rules the three values exist to express:
 *
 * <ul>
 *   <li>{@link #PLACEHOLDER} -- a generated {@code Session N}. The only state an
 *       automatic title may overwrite.</li>
 *   <li>{@link #AUTO} -- produced by the titler. Nothing automatic overwrites it either;
 *       only an explicit regeneration does.</li>
 *   <li>{@link #USER} -- typed by a person, at the prompt, in the UI, or supplied
 *       programmatically when the session was created. Nothing automatic ever touches
 *       it.</li>
 * </ul>
 */
public enum NaruSessionNameSource {
    PLACEHOLDER,
    AUTO,
    USER;

    /**
     * The stored spelling, or null for an absent value.
     *
     * <p>A legacy session carries no source at all, and reading that as
     * {@link #PLACEHOLDER} would let the titler rename a session somebody had named by
     * hand. Absent therefore means {@link #USER}: the safest reading, because the only
     * cost is that such a session never gets an automatic title.
     */
    public static NaruSessionNameSource parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (NaruSessionNameSource s : values()) {
            if (s.name().equalsIgnoreCase(value.trim())) {
                return s;
            }
        }
        return null;
    }
}
