package net.thevpc.naru.api.model;

import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

/**
 * Which registration files a registration is written to.
 *
 * <p>{@code USER} is the personal, machine-wide layer ({@code ~/.naru/config/registrations.tson},
 * plus the private {@code ~/.naru/local/config/registrations.tson} for literal keys);
 * {@code PROJECT} is the workspace layer under {@code .naru/}. The project always wins over
 * the user layer, field by field, so a project can complete a user registration with just
 * its own {@code apiKey} — or replace it wholesale when it names a different endpoint.
 *
 * <p>Each scope is itself split on the public/private visibility axis, exactly like
 * {@code env.tson}: {@link NaruModelRegistration} keeps literal credentials in the private
 * file and structure, parameters and {@code $NAME} references in the public one.
 */
public enum NaruRegistrationScope {
    USER,
    PROJECT;

    /**
     * Parse a scope name as written on a command line or in a config file. Case-insensitive.
     * An unknown word is an error rather than a guess: which file a write lands in is not
     * something a typo should decide.
     */
    public static NOptional<NaruRegistrationScope> parse(String scope) {
        if (NBlankable.isBlank(scope)) {
            return NOptional.ofNamedEmpty("scope : '" + scope + "'");
        }
        String s = NStringUtils.stripToNull(scope).toLowerCase();
        for (NaruRegistrationScope t : values()) {
            if (t.name().toLowerCase().equals(s)) {
                return NOptional.of(t);
            }
        }
        return NOptional.ofNamedEmpty("scope : '" + scope + "'");
    }

    /** The scope name, lowercased, as it appears in messages and command lines. */
    public String word() {
        return name().toLowerCase();
    }
}
