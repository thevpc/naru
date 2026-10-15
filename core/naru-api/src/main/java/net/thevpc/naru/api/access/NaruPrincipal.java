package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A durable identity in the access model: a user, a group, an agent, or the
 * system.
 *
 * <p>A principal answers "who exists". It is deliberately separate from
 * {@link NaruActor}, which answers "who is acting right now, in what role". The
 * same user principal can appear as the direct actor of user-authored statements
 * and as the owner of the agent actor that runs them.
 *
 * <p>Identity is by {@link #id()} and {@link #type()}: two principals with the
 * same id and type are equal regardless of display name or memberships, which
 * means a rule store can compare subjects against freshly built principals
 * without re-resolving anything.
 *
 * <p>Instances are immutable. {@link #withGroup(String)} and
 * {@link #withRole(String)} return copies, so a principal handed to a rule is
 * never mutated underneath it.
 */
public final class NaruPrincipal implements NToElement {

    private final String id;
    private final NaruPrincipalType type;
    private final String displayName;
    private final String ownerId;
    private final Set<String> groups;
    private final Set<String> roles;

    private NaruPrincipal(String id, NaruPrincipalType type, String displayName,
                          String ownerId, Set<String> groups, Set<String> roles) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.ownerId = ownerId;
        this.groups = groups == null ? Collections.emptySet() : Collections.unmodifiableSet(groups);
        this.roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(roles);
    }

    // ── factories ────────────────────────────────────────────────────────────

    public static NaruPrincipal user(String id) {
        return user(id, id);
    }

    public static NaruPrincipal user(String id, String displayName) {
        return new NaruPrincipal(requireId(id, "user"), NaruPrincipalType.USER, displayName, null, null, null);
    }

    /** An agent identity owned by {@code ownerId}; the owner is what grants it reach. */
    public static NaruPrincipal agent(String id, String ownerId) {
        return new NaruPrincipal(requireId(id, "agent"), NaruPrincipalType.AGENT, id, ownerId, null, null);
    }

    public static NaruPrincipal group(String id) {
        return new NaruPrincipal(requireId(id, "group"), NaruPrincipalType.GROUP, id, null, null, null);
    }

    public static NaruPrincipal system() {
        return new NaruPrincipal("system", NaruPrincipalType.SYSTEM, "system", null, null, null);
    }

    private static String requireId(String id, String what) {
        if (NBlankable.isBlank(id)) {
            throw new NIllegalArgumentException(NMsg.ofC("%s principal id must not be blank", what));
        }
        return id;
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public NaruPrincipalType type() {
        return type;
    }

    public String displayName() {
        return displayName == null ? id : displayName;
    }

    /** For an {@link NaruPrincipalType#AGENT}, the id of the owning user; empty otherwise. */
    public NOptional<String> ownerId() {
        return NOptional.ofNullable(ownerId);
    }

    public Set<String> groups() {
        return groups;
    }

    public Set<String> roles() {
        return roles;
    }

    public boolean isUser() {
        return type == NaruPrincipalType.USER;
    }

    public boolean isAgent() {
        return type == NaruPrincipalType.AGENT;
    }

    public boolean isGroup() {
        return type == NaruPrincipalType.GROUP;
    }

    public boolean isSystem() {
        return type == NaruPrincipalType.SYSTEM;
    }

    // ── copies ───────────────────────────────────────────────────────────────

    public NaruPrincipal withGroup(String group) {
        if (NBlankable.isBlank(group) || groups.contains(group)) {
            return this;
        }
        Set<String> g = new LinkedHashSet<>(groups);
        g.add(group);
        return new NaruPrincipal(id, type, displayName, ownerId, g, roles);
    }

    public NaruPrincipal withRole(String role) {
        if (NBlankable.isBlank(role) || roles.contains(role)) {
            return this;
        }
        Set<String> r = new LinkedHashSet<>(roles);
        r.add(role);
        return new NaruPrincipal(id, type, displayName, ownerId, groups, r);
    }

    public NaruPrincipal withDisplayName(String name) {
        return new NaruPrincipal(id, type, name, ownerId, groups, roles);
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("id", id);
        o.set("type", type.name());
        if (displayName != null && !displayName.equals(id)) {
            o.set("displayName", displayName);
        }
        if (ownerId != null) {
            o.set("ownerId", ownerId);
        }
        if (!groups.isEmpty()) {
            o.set("groups", NElement.ofStringArray(groups.toArray(new String[0])));
        }
        if (!roles.isEmpty()) {
            o.set("roles", NElement.ofStringArray(roles.toArray(new String[0])));
        }
        return o.build();
    }

    public static NaruPrincipal of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (!element.isAnyObject()) {
            throw new NIllegalArgumentException(NMsg.ofC("principal must be an object, got %s", element));
        }
        net.thevpc.nuts.elem.NObjectElement o = element.asObject().get();
        String id = o.getStringValue("id").orNull();
        if (NBlankable.isBlank(id)) {
            throw new NIllegalArgumentException(NMsg.ofC("principal id is required, got %s", element));
        }
        NaruPrincipalType type = o.getStringValue("type")
                .map(String::toUpperCase)
                .map(NaruPrincipal::parseType)
                .orElse(NaruPrincipalType.USER);
        String displayName = o.getStringValue("displayName").orNull();
        String ownerId = o.getStringValue("ownerId").orNull();
        NaruPrincipal p = new NaruPrincipal(id, type, displayName, ownerId, null, null);
        for (String g : readStrings(o, "groups")) {
            p = p.withGroup(g);
        }
        for (String r : readStrings(o, "roles")) {
            p = p.withRole(r);
        }
        return p;
    }

    private static NaruPrincipalType parseType(String s) {
        try {
            return NaruPrincipalType.valueOf(s);
        } catch (IllegalArgumentException ex) {
            throw new NIllegalArgumentException(NMsg.ofC("unknown principal type '%s'", s));
        }
    }

    private static java.util.List<String> readStrings(net.thevpc.nuts.elem.NObjectElement o, String key) {
        NElement e = o.get(key).orNull();
        java.util.List<String> all = new java.util.ArrayList<>();
        if (e != null && e.isAnyArray()) {
            for (NElement c : e.asArray().get().children()) {
                String s = c.asStringValue().orNull();
                if (!NBlankable.isBlank(s)) {
                    all.add(s);
                }
            }
        }
        return all;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruPrincipal)) {
            return false;
        }
        NaruPrincipal that = (NaruPrincipal) o;
        return Objects.equals(id, that.id) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    @Override
    public String toString() {
        String prefix;
        switch (type) {
            case AGENT:
                prefix = "agent:";
                break;
            case GROUP:
                prefix = "group:";
                break;
            case SYSTEM:
                return "system";
            case USER:
            default:
                prefix = "user:";
                break;
        }
        return prefix + id;
    }
}
