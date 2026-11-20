package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
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
 * Who is acting, right now: a {@link NaruPrincipal} plus the role
 * {@link NaruActorKind kind} it acts in, and — when it acts as an agent — the
 * owning user principal.
 *
 * <p>This is the unit every access check is evaluated against, and the unit a
 * statement carries as its creator. That is the whole point of the type: an
 * instruction is only as trustworthy as the actor that produced it, so the actor
 * is threaded from the moment a statement is parsed or injected rather than
 * re-guessed at enforcement time.
 *
 * <p>For an agent, {@link #groups()} and {@link #roles()} return the union of the
 * agent's own and its owner's, which is what makes "an agent inherits its owner's
 * memberships" a property of the value rather than a rule every caller must
 * remember.
 *
 * <p>{@link #taskId()} is provenance only: {@code -1} when the actor is not
 * bound to a task. It lets spawn-scoped rules be attributed and cleaned up, and
 * never affects a decision by itself.
 */
public final class NaruActor implements NToElement {

    private final NaruPrincipal principal;
    private final NaruActorKind kind;
    private final NaruPrincipal owner;
    private final long taskId;

    private NaruActor(NaruPrincipal principal, NaruActorKind kind, NaruPrincipal owner, long taskId) {
        if (principal == null) {
            throw new NIllegalArgumentException(NMsg.ofC("actor principal must not be null"));
        }
        this.principal = principal;
        this.kind = kind == null ? defaultKind(principal) : kind;
        this.owner = owner;
        this.taskId = taskId;
    }

    private static NaruActorKind defaultKind(NaruPrincipal p) {
        if (p.isAgent()) {
            return NaruActorKind.AGENT;
        }
        if (p.isSystem()) {
            return NaruActorKind.SYSTEM;
        }
        return NaruActorKind.USER;
    }

    // ── factories ────────────────────────────────────────────────────────────

    public static NaruActor user(NaruPrincipal user) {
        return new NaruActor(user, NaruActorKind.USER, null, -1L);
    }

    public static NaruActor user(String userId) {
        return user(NaruPrincipal.user(userId));
    }

    public static NaruActor agent(NaruPrincipal agent, NaruPrincipal owner) {
        return new NaruActor(agent, NaruActorKind.AGENT, owner, -1L);
    }

    public static NaruActor agent(String agentId, NaruPrincipal owner) {
        return agent(NaruPrincipal.agent(agentId, owner == null ? null : owner.id()), owner);
    }

    public static NaruActor system() {
        return new NaruActor(NaruPrincipal.system(), NaruActorKind.SYSTEM, null, -1L);
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public NaruPrincipal principal() {
        return principal;
    }

    public NaruActorKind kind() {
        return kind;
    }

    /** The resolved owning user principal for an agent actor; {@code null} for other kinds. */
    public NaruPrincipal owner() {
        return owner;
    }

    public String id() {
        return principal.id();
    }

    public long taskId() {
        return taskId;
    }

    public boolean isUser() {
        return kind == NaruActorKind.USER;
    }

    public boolean isAgent() {
        return kind == NaruActorKind.AGENT;
    }

    public boolean isSystem() {
        return kind == NaruActorKind.SYSTEM;
    }

    /** Principal groups, plus the owner's when this is an agent. */
    public Set<String> groups() {
        if (owner == null) {
            return principal.groups();
        }
        Set<String> all = new LinkedHashSet<>(principal.groups());
        all.addAll(owner.groups());
        return Collections.unmodifiableSet(all);
    }

    /** Principal roles, plus the owner's when this is an agent. */
    public Set<String> roles() {
        if (owner == null) {
            return principal.roles();
        }
        Set<String> all = new LinkedHashSet<>(principal.roles());
        all.addAll(owner.roles());
        return Collections.unmodifiableSet(all);
    }

    public NOptional<String> ownerId() {
        return owner == null ? NOptional.ofEmpty() : NOptional.of(owner.id());
    }

    public NaruActor withTaskId(long taskId) {
        if (this.taskId == taskId) {
            return this;
        }
        return new NaruActor(principal, kind, owner, taskId);
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("principal", principal.toElement());
        o.set("kind", kind.name());
        if (owner != null) {
            o.set("owner", owner.toElement());
        }
        if (taskId >= 0) {
            o.set("taskId", taskId);
        }
        return o.build();
    }

    public static NaruActor of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (!element.isAnyObject()) {
            throw new NIllegalArgumentException(NMsg.ofC("actor must be an object, got %s", element));
        }
        NObjectElement o = element.asObject().get();
        NaruPrincipal principal = NaruPrincipal.of(o.get("principal").orNull());
        if (principal == null) {
            throw new NIllegalArgumentException(NMsg.ofC("actor principal is required, got %s", element));
        }
        NaruActorKind kind = o.getStringValue("kind")
                .map(String::toUpperCase)
                .map(s -> {
                    try {
                        return NaruActorKind.valueOf(s);
                    } catch (IllegalArgumentException ex) {
                        throw new NIllegalArgumentException(NMsg.ofC("unknown actor kind '%s'", s));
                    }
                })
                .orNull();
        NaruPrincipal owner = NaruPrincipal.of(o.get("owner").orNull());
        long taskId = o.getLongValue("taskId").orElse(-1L);
        return new NaruActor(principal, kind, owner, taskId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruActor)) {
            return false;
        }
        NaruActor a = (NaruActor) o;
        return taskId == a.taskId
                && kind == a.kind
                && Objects.equals(principal, a.principal)
                && Objects.equals(owner, a.owner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principal, kind, owner, taskId);
    }

    @Override
    public String toString() {
        if (isAgent() && owner != null) {
            return principal + "(for " + owner.id() + ")";
        }
        return principal.toString();
    }
}
