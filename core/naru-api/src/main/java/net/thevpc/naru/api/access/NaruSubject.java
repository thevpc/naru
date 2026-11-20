package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Objects;

/**
 * Who a rule applies to, expressed as a predicate over an {@link NaruActor}.
 *
 * <p>The interesting case is {@link #USER}: it matches a user acting directly
 * <em>and</em> an agent owned by that user. That one rule is the whole "an agent
 * runs on behalf of a user" semantics — granting or denying a user automatically
 * reaches the agents acting for them, and the agent's own identity stays
 * available through {@link NaruSubject#actor(String)} when the two must be told
 * apart.
 *
 * <p>{@link #GROUP} and {@link #ROLE} already see the union of an agent's and its
 * owner's memberships, because that union is a property of {@link NaruActor}.
 * A rule keyed on {@code group devs} therefore reaches Alice's agent without the
 * rule having to mention agents at all.
 */
public final class NaruSubject implements NToElement {

    private final NaruSubjectType type;
    private final String id;

    private NaruSubject(NaruSubjectType type, String id) {
        if (type == null) {
            throw new NIllegalArgumentException(NMsg.ofC("subject type must not be null"));
        }
        if (type != NaruSubjectType.ANY && type != NaruSubjectType.AGENT && type != NaruSubjectType.SYSTEM
                && NBlankable.isBlank(id)) {
            throw new NIllegalArgumentException(NMsg.ofC("subject of type %s requires an id", type));
        }
        this.type = type;
        this.id = id;
    }

    // ── factories ────────────────────────────────────────────────────────────

    public static NaruSubject any() {
        return new NaruSubject(NaruSubjectType.ANY, null);
    }

    public static NaruSubject user(String id) {
        return new NaruSubject(NaruSubjectType.USER, id);
    }

    public static NaruSubject group(String id) {
        return new NaruSubject(NaruSubjectType.GROUP, id);
    }

    public static NaruSubject role(String id) {
        return new NaruSubject(NaruSubjectType.ROLE, id);
    }

    /** Matches the principal with this id, whether it is a user or an agent. */
    public static NaruSubject actor(String principalId) {
        return new NaruSubject(NaruSubjectType.ACTOR, principalId);
    }

    public static NaruSubject agents() {
        return new NaruSubject(NaruSubjectType.AGENT, null);
    }

    public static NaruSubject system() {
        return new NaruSubject(NaruSubjectType.SYSTEM, null);
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public NaruSubjectType type() {
        return type;
    }

    public String id() {
        return id;
    }

    // ── matching ─────────────────────────────────────────────────────────────

    public boolean matches(NaruActor actor) {
        if (actor == null) {
            return type == NaruSubjectType.ANY;
        }
        switch (type) {
            case ANY:
                return true;
            case USER:
                if (actor.isUser() && id.equals(actor.principal().id())) {
                    return true;
                }
                return actor.isAgent() && actor.owner() != null && id.equals(actor.owner().id());
            case GROUP:
                return actor.groups().contains(id);
            case ROLE:
                return actor.roles().contains(id);
            case ACTOR:
                return id.equals(actor.principal().id());
            case AGENT:
                return actor.isAgent();
            case SYSTEM:
                return actor.isSystem();
            default:
                return false;
        }
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("type", type.name());
        if (id != null) {
            o.set("id", id);
        }
        return o.build();
    }

    public static NaruSubject of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (element.isAnyString()) {
            // shorthand: "user:alice", "group:devs", "role:admin", "agent", "*"
            return parse(element.asStringValue().orElse(""));
        }
        if (!element.isAnyObject()) {
            throw new NIllegalArgumentException(NMsg.ofC("subject must be an object or a string, got %s", element));
        }
        NObjectElement o = element.asObject().get();
        String typeName = o.getStringValue("type").orNull();
        String id = o.getStringValue("id").orNull();
        if (NBlankable.isBlank(typeName)) {
            throw new NIllegalArgumentException(NMsg.ofC("subject type is required, got %s", element));
        }
        NaruSubjectType type;
        try {
            type = NaruSubjectType.valueOf(typeName.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new NIllegalArgumentException(NMsg.ofC("unknown subject type '%s'", typeName));
        }
        return new NaruSubject(type, id);
    }

    /** Parses a {@code type:id} shorthand ({@code user:alice}, {@code group:devs}, {@code agent}, {@code *}). */
    public static NaruSubject parse(String token) {
        if (NBlankable.isBlank(token)) {
            throw new NIllegalArgumentException(NMsg.ofC("subject must not be blank"));
        }
        String s = token.trim();
        if ("*".equals(s) || "any".equalsIgnoreCase(s)) {
            return any();
        }
        int idx = s.indexOf(':');
        if (idx < 0) {
            switch (s.toLowerCase()) {
                case "agent":
                    return agents();
                case "system":
                    return system();
                default:
                    return actor(s);
            }
        }
        String prefix = s.substring(0, idx).trim().toLowerCase();
        String id = s.substring(idx + 1).trim();
        switch (prefix) {
            case "user":
                return user(id);
            case "group":
                return group(id);
            case "role":
                return role(id);
            case "actor":
                return actor(id);
            case "agent":
                return agents();
            case "system":
                return system();
            default:
                throw new NIllegalArgumentException(NMsg.ofC("unknown subject type '%s'", prefix));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruSubject)) {
            return false;
        }
        NaruSubject s = (NaruSubject) o;
        return type == s.type && Objects.equals(id, s.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }

    @Override
    public String toString() {
        switch (type) {
            case ANY:
                return "*";
            case AGENT:
                return "agent";
            case SYSTEM:
                return "system";
            case USER:
                return "user:" + id;
            case GROUP:
                return "group:" + id;
            case ROLE:
                return "role:" + id;
            case ACTOR:
            default:
                return "actor:" + id;
        }
    }
}
