package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What is done to a {@link NaruResource}: read, write, execute, call, fetch, …
 *
 * <p>Like {@link NaruResourceType} this is an open, id-keyed value type, so a
 * domain can name its own verb. The well-known actions carry short aliases
 * ({@code read}/{@code r}, {@code write}/{@code w}, {@code execute}/{@code x})
 * because those three are what a file-like grant is written with in practice, and
 * the aliases are resolved to the canonical id on construction so that a rule
 * written {@code r} and a query written {@code read} compare equal.
 *
 * <p>{@link #matches(NaruAction)} is deliberately asymmetric: {@link #ANY}
 * matches anything, and otherwise equality is required. A rule holding
 * {@code ANY} therefore covers a query for {@code write}, but a rule holding
 * {@code write} does not cover a query for {@code ANY}.
 */
public final class NaruAction implements NToElement {

    private static final Map<String, NaruAction> KNOWN = new LinkedHashMap<>();

    public static final NaruAction READ = of("read", "r");
    public static final NaruAction WRITE = of("write", "w");
    public static final NaruAction EXECUTE = of("execute", "x");
    public static final NaruAction CALL = of("call");
    public static final NaruAction DELETE = of("delete", "d");
    public static final NaruAction LIST = of("list", "ls");
    public static final NaruAction FETCH = of("fetch");
    public static final NaruAction APPEND = of("append", "a");
    public static final NaruAction CREATE = of("create", "c");
    public static final NaruAction ANY = of("*");

    private final String id;
    private final List<String> aliases;

    private NaruAction(String id, List<String> aliases) {
        this.id = id;
        this.aliases = aliases == null ? Collections.emptyList() : Collections.unmodifiableList(aliases);
    }

    public static NaruAction of(String id) {
        return of(id, new String[0]);
    }

    public static NaruAction of(String id, String... aliases) {
        if (NBlankable.isBlank(id)) {
            throw new NIllegalArgumentException(NMsg.ofC("action id must not be blank"));
        }
        String key = id.trim();
        NaruAction known = KNOWN.get(key.toLowerCase());
        if (known != null) {
            return known;
        }
        NaruAction a = new NaruAction(key, Arrays.asList(aliases));
        synchronized (KNOWN) {
            KNOWN.putIfAbsent(key.toLowerCase(), a);
            for (String alias : aliases) {
                if (!NBlankable.isBlank(alias)) {
                    KNOWN.putIfAbsent(alias.toLowerCase(), a);
                }
            }
        }
        return a;
    }

    /**
     * Parses a token that may be a canonical id or an alias, resolving it to the
     * canonical action when it is one of the well-known verbs.
     */
    public static NaruAction parse(String token) {
        if (NBlankable.isBlank(token)) {
            throw new NIllegalArgumentException(NMsg.ofC("action must not be blank"));
        }
        String key = token.trim().toLowerCase();
        NaruAction known = KNOWN.get(key);
        if (known != null) {
            return known;
        }
        // not seen yet: register it so a later parse of the same token returns the same value
        return of(token.trim());
    }

    public String id() {
        return id;
    }

    public List<String> aliases() {
        return aliases;
    }

    public boolean isAny() {
        return "*".equals(id);
    }

    /** {@code true} when this action covers {@code other}: {@link #ANY}, or equal ids. */
    public boolean matches(NaruAction other) {
        return isAny() || (other != null && id.equals(other.id));
    }

    @Override
    public NElement toElement() {
        return NElement.ofString(id);
    }

    public static NaruAction of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        String s = element.asStringValue().orNull();
        return s == null ? null : parse(s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruAction)) {
            return false;
        }
        return id.equals(((NaruAction) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
