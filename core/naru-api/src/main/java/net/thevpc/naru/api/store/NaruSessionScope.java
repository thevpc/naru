package net.thevpc.naru.api.store;

/**
 * Where a session's state physically lives.
 *
 * <p>A scope is not a property of a session that gets stored alongside it -- it is the
 * <em>name of the location</em>, and that is deliberate. Two stores (a git-backed one, a
 * database) have no public/private folder split at all, and a third might store a public
 * session and a private one in the same table. Persisting a scope would mean every
 * implementation had to invent a meaning for a field its storage does not have, and the
 * first thing any reader would do is go and look at where the data actually is.
 *
 * <p>So: for the file store, the location <i>is</i> the scope. Read the path, know the
 * scope. Nothing to keep in sync, and no way for the two to disagree.
 */
public enum NaruSessionScope {

    /**
     * Shareable state, under {@code .naru/sessions/<uuid>/} in a file store. Meant to be
     * committed: it is what another person, or another checkout, is meant to read.
     */
    PUBLIC,

    /**
     * Local state, under {@code .naru/local/sessions/<uuid>/} in a file store. Never
     * shared and never committed. The default, because a session that has not been asked
     * for is a session nobody intended to publish.
     */
    PRIVATE;

    public static NaruSessionScope parse(String value) {
        if (value == null) {
            return null;
        }
        for (NaruSessionScope s : values()) {
            if (s.name().equalsIgnoreCase(value.trim())) {
                return s;
            }
        }
        return null;
    }

    public NaruSessionScope other() {
        return this == PUBLIC ? PRIVATE : PUBLIC;
    }
}