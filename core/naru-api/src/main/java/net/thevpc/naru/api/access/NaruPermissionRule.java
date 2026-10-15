package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One line of an access policy: <em>for these resources, these actions, these
 * actors, the effect is this</em>.
 *
 * <p>A rule is the tuple an operator writes and the engine evaluates. Every
 * field except {@link #effect()} is optional, and an omitted field means "any":
 * a rule with no patterns covers every locator of its type, a rule with no
 * subjects covers every actor. That is what makes a broad grant one line and a
 * narrow exception another.
 *
 * <p>{@link #matches} answers only "does this rule's filter match", never "is
 * access granted" — {@link #effect()} and ordering are applied by the policy,
 * because first match wins and a matching rule that is {@code DENY} must be able
 * to shadow a later {@code ALLOW}.
 *
 * <p>Rules are immutable. The fluent setters return a new rule, so a rule handed
 * to a policy can be stored and shared without any caller being able to mutate
 * what the policy is enforcing.
 */
public final class NaruPermissionRule implements NToElement {

    private final String id;
    private final NaruResourceType resourceType;
    private final List<String> patterns;
    private final Set<NaruAction> actions;
    private final List<NaruSubject> subjects;
    private final NaruActorKind actorKind;
    private final NaruAccessEffect effect;
    private final int order;
    private final String source;
    private final boolean enabled;
    private final Instant createdAt;
    private final String description;

    public NaruPermissionRule() {
        this(null, null, null, null, null, null, NaruAccessEffect.ALLOW, Integer.MAX_VALUE, null, true, null, null);
    }

    private NaruPermissionRule(String id, NaruResourceType resourceType, List<String> patterns,
                               Set<NaruAction> actions, List<NaruSubject> subjects, NaruActorKind actorKind,
                               NaruAccessEffect effect, int order, String source, boolean enabled,
                               Instant createdAt, String description) {
        this.id = id;
        this.resourceType = resourceType;
        this.patterns = patterns == null ? Collections.emptyList() : Collections.unmodifiableList(patterns);
        this.actions = actions == null ? Collections.emptySet() : Collections.unmodifiableSet(actions);
        this.subjects = subjects == null ? Collections.emptyList() : Collections.unmodifiableList(subjects);
        this.actorKind = actorKind;
        this.effect = effect == null ? NaruAccessEffect.ALLOW : effect;
        this.order = order;
        this.source = source;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.description = description;
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    /** The type this rule applies to, or {@code null} for any type. */
    public NaruResourceType resourceType() {
        return resourceType;
    }

    public List<String> patterns() {
        return patterns;
    }

    public Set<NaruAction> actions() {
        return actions;
    }

    public List<NaruSubject> subjects() {
        return subjects;
    }

    /** The role context this rule applies to, or {@code null} for any. */
    public NaruActorKind actorKind() {
        return actorKind;
    }

    public NaruAccessEffect effect() {
        return effect;
    }

    public int order() {
        return order;
    }

    public String source() {
        return source;
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String description() {
        return description;
    }

    // ── fluent setters (return a copy) ───────────────────────────────────────

    public NaruPermissionRule id(String v) {
        return new NaruPermissionRule(v, resourceType, patterns, actions, subjects, actorKind, effect, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule resourceType(NaruResourceType v) {
        return new NaruPermissionRule(id, v, patterns, actions, subjects, actorKind, effect, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule patterns(String... globs) {
        return patterns(globs == null ? null : Arrays.asList(globs));
    }

    public NaruPermissionRule patterns(List<String> globs) {
        return new NaruPermissionRule(id, resourceType, globs == null ? null : new ArrayList<>(globs), actions, subjects, actorKind, effect, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule actions(NaruAction... a) {
        return actions(a == null ? null : new LinkedHashSet<>(Arrays.asList(a)));
    }

    public NaruPermissionRule actions(Collection<NaruAction> a) {
        return new NaruPermissionRule(id, resourceType, patterns, a == null ? null : new LinkedHashSet<>(a), subjects, actorKind, effect, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule subjects(NaruSubject... s) {
        return subjects(s == null ? null : Arrays.asList(s));
    }

    public NaruPermissionRule subjects(List<NaruSubject> s) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, s == null ? null : new ArrayList<>(s), actorKind, effect, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule actorKind(NaruActorKind v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, v, effect, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule effect(NaruAccessEffect v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, actorKind, v, order, source, enabled, createdAt, description);
    }

    public NaruPermissionRule order(int v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, actorKind, effect, v, source, enabled, createdAt, description);
    }

    public NaruPermissionRule source(String v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, actorKind, effect, order, v, enabled, createdAt, description);
    }

    public NaruPermissionRule enabled(boolean v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, actorKind, effect, order, source, v, createdAt, description);
    }

    public NaruPermissionRule createdAt(Instant v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, actorKind, effect, order, source, enabled, v, description);
    }

    public NaruPermissionRule description(String v) {
        return new NaruPermissionRule(id, resourceType, patterns, actions, subjects, actorKind, effect, order, source, enabled, createdAt, v);
    }

    // ── matching ─────────────────────────────────────────────────────────────

    /**
     * Whether this rule's filter matches the request. {@link #enabled()} and
     * {@link #order()} are not considered here; the policy applies them.
     */
    public boolean matches(NaruResource resource, NaruAction action, NaruActor actor) {
        return matches(resource, action, actor, patterns);
    }

    /**
     * The same filter, but matching against {@code effectivePatterns} instead of
     * {@link #patterns()}. The policy uses this to apply its pattern-base
     * resolution (project-relative globs, {@code ~} expansion) without cloning
     * the rule or passing a resolver down into every matcher.
     */
    public boolean matches(NaruResource resource, NaruAction action, NaruActor actor,
                           List<String> effectivePatterns) {
        if (!enabled) {
            return false;
        }
        if (resource == null || action == null) {
            return false;
        }
        if (resourceType != null && !resourceType.isAny() && !resourceType.equals(resource.type())) {
            return false;
        }
        if (effectivePatterns != null && !effectivePatterns.isEmpty()
                && !NaruGlob.matchesAny(effectivePatterns, resource.locator())) {
            return false;
        }
        if (!actions.isEmpty()) {
            boolean any = false;
            for (NaruAction a : actions) {
                if (a.matches(action)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        if (actorKind != null && actor != null && actorKind != actor.kind()) {
            return false;
        }
        if (actorKind != null && actor == null) {
            return false;
        }
        if (!subjects.isEmpty()) {
            boolean any = false;
            for (NaruSubject s : subjects) {
                if (s.matches(actor)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        if (id != null) {
            o.set("id", id);
        }
        if (resourceType != null) {
            o.set("resourceType", resourceType.id());
        }
        if (!patterns.isEmpty()) {
            o.set("patterns", NElement.ofStringArray(patterns.toArray(new String[0])));
        }
        if (!actions.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (NaruAction a : actions) {
                ids.add(a.id());
            }
            o.set("actions", NElement.ofStringArray(ids.toArray(new String[0])));
        }
        if (!subjects.isEmpty()) {
            List<NElement> ss = new ArrayList<>();
            for (NaruSubject s : subjects) {
                ss.add(s.toElement());
            }
            o.set("subjects", NElement.ofArrayBuilder().addAll(ss).build());
        }
        if (actorKind != null) {
            o.set("actorKind", actorKind.name());
        }
        o.set("effect", effect.name());
        o.set("order", order);
        if (source != null) {
            o.set("source", source);
        }
        if (!enabled) {
            o.set("enabled", false);
        }
        if (createdAt != null) {
            o.set("createdAt", NElement.ofInstant(createdAt));
        }
        if (description != null) {
            o.set("description", description);
        }
        return o.build();
    }

    public static NaruPermissionRule of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (!element.isAnyObject()) {
            throw new NIllegalArgumentException(NMsg.ofC("permission rule must be an object, got %s", element));
        }
        NObjectElement o = element.asObject().get();
        NaruPermissionRule r = new NaruPermissionRule();
        r = r.id(o.getStringValue("id").orNull());
        r = r.resourceType(NaruResourceType.of(o.getStringValue("resourceType").orNull()));
        NElement pe = o.get("patterns").orNull();
        if (pe != null && pe.isAnyArray()) {
            List<String> ps = new ArrayList<>();
            for (NElement c : pe.asArray().get().children()) {
                String s = c.asStringValue().orNull();
                if (s != null) {
                    ps.add(s);
                }
            }
            r = r.patterns(ps);
        } else {
            String single = o.getStringValue("pattern").orNull();
            if (single != null) {
                r = r.patterns(single);
            }
        }
        NElement ae = o.get("actions").orNull();
        if (ae != null && ae.isAnyArray()) {
            Set<NaruAction> as = new LinkedHashSet<>();
            for (NElement c : ae.asArray().get().children()) {
                String s = c.asStringValue().orNull();
                if (s != null) {
                    as.add(NaruAction.parse(s));
                }
            }
            r = r.actions(as);
        }
        NElement se = o.get("subjects").orNull();
        if (se != null && se.isAnyArray()) {
            List<NaruSubject> ss = new ArrayList<>();
            for (NElement c : se.asArray().get().children()) {
                NaruSubject s = NaruSubject.of(c);
                if (s != null) {
                    ss.add(s);
                }
            }
            r = r.subjects(ss);
        }
        String ak = o.getStringValue("actorKind").orNull();
        if (ak != null) {
            r = r.actorKind(NaruActorKind.valueOf(ak.trim().toUpperCase()));
        }
        String eff = o.getStringValue("effect").orNull();
        if (eff != null) {
            r = r.effect(NaruAccessEffect.valueOf(eff.trim().toUpperCase()));
        }
        r = r.order(o.getIntValue("order").orElse(Integer.MAX_VALUE));
        r = r.source(o.getStringValue("source").orNull());
        r = r.enabled(o.getBooleanValue("enabled").orElse(true));
        Instant created = o.getInstantValue("createdAt").orNull();
        if (created != null) {
            r = r.createdAt(created);
        }
        r = r.description(o.getStringValue("description").orNull());
        return r;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruPermissionRule)) {
            return false;
        }
        NaruPermissionRule r = (NaruPermissionRule) o;
        return order == r.order
                && enabled == r.enabled
                && Objects.equals(id, r.id)
                && Objects.equals(resourceType, r.resourceType)
                && Objects.equals(patterns, r.patterns)
                && Objects.equals(actions, r.actions)
                && Objects.equals(subjects, r.subjects)
                && actorKind == r.actorKind
                && effect == r.effect
                && Objects.equals(source, r.source)
                && Objects.equals(description, r.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, resourceType, patterns, actions, subjects, actorKind, effect, order, source, enabled, description);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(effect).append(' ');
        sb.append(resourceType == null ? "*" : resourceType.id());
        if (!patterns.isEmpty()) {
            sb.append(' ').append(String.join(",", patterns));
        }
        if (!actions.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (NaruAction a : actions) {
                ids.add(a.id());
            }
            sb.append(" [").append(String.join(",", ids)).append(']');
        }
        if (actorKind != null) {
            sb.append(" kind=").append(actorKind);
        }
        if (!subjects.isEmpty()) {
            sb.append(" to=").append(subjects);
        }
        if (id != null) {
            sb.append(" (").append(id).append(')');
        }
        return sb.toString();
    }
}
