package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rules a registration has to satisfy before it is written, and the form it is
 * stored in.
 *
 * <p>These checks are not a courtesy: a registration <em>is</em> a provider instance, so
 * a broken one either silently registers nothing or rewrites an existing endpoint's
 * identity. {@code --provider=custom} is internal to the store, {@code --provider=anthropic}
 * names a wire shape rather than a type, a generic endpoint with no url or no model pin
 * has nothing to call, and an id that collides with a built-in would be refused later at
 * materialization anyway — so it is refused here, where the reader still sees which knob
 * to turn.
 *
 * <p>This lives in the api rather than beside the {@code /model} directive because a
 * registration is written from more than one host: the directive parses a command line and
 * the desktop's settings form fills a table, and both must reach the same verdict. Nuts
 * loads extensions in their own classloaders and the desktop deliberately does not compile
 * against them, so a shared validator cannot live in the extension that ships the directive.
 *
 * <p>Normalization is part of the contract, not a side effect: the canonical protocol id is
 * stored rather than the typed one, and naming a built-in type's <em>own</em> default wire is
 * dropped as a no-op so the file keeps reading as a delta over the type's defaults.
 */
public final class NaruModelRegistrationRules {

    /**
     * The outcome of one write attempt.
     *
     * @param registration the registration to store, normalized; null when {@link #problems()}
     *                     is non-empty
     * @param protocol     the canonical wire id resolved from {@code protocol}, or null when the
     *                     type's own default applies — the host shows this in its success line
     * @param created      false when an existing registration was merged into
     * @param generic      true for a generic endpoint (no provider type behind it)
     * @param problems     why the write was refused, first one first; empty when it may proceed
     */
    public record Outcome(NaruModelRegistration registration, String protocol, boolean created,
                          boolean generic, List<String> problems) {

        public boolean isOk() {
            return problems.isEmpty();
        }

        /** The problem to report, or null when the write may proceed. */
        public String problem() {
            return problems.isEmpty() ? null : problems.get(0);
        }
    }

    private NaruModelRegistrationRules() {
    }

    /**
     * Merge {@code edits} into the stored registration of the same id (an update is a merge,
     * never a replace) and validate the result.
     *
     * <p>An edit whose value is null clears that parameter. The identity params are exempt:
     * an update cannot strip {@code provider} or {@code protocol} and leave a shell that
     * materializes as an unintended generic endpoint.
     *
     * @param session  the session whose registry supplies the provider types; may be null,
     *                 which only weakens the type and protocol checks
     * @param typedId  the id as its author typed it — matched case-insensitively against the
     *                 store so a different casing never forks the entry
     * @param edits    this write's parameters, in the author's order; a null value clears
     * @param update   true to require an existing registration
     */
    public static Outcome apply(NaruSession session, String typedId, Map<String, NElement> edits, boolean update) {
        return apply(session, typedId, edits, update, NaruRegistrationScope.PROJECT);
    }

    /**
     * The same, writing one specific scope. The scope decides which registration is merged into:
     * an update of {@code USER} never copies this project's fields into the personal file, and a
     * {@code PROJECT} override of a personal registration never copies its key the other way.
     *
     * @param scope the file this write goes to; null means "merge the stored registration as a
     *              whole", for a host whose store has no scope axis
     */
    public static Outcome apply(NaruSession session, String typedId, Map<String, NElement> edits,
                                boolean update, NaruRegistrationScope scope) {
        List<String> problems = new ArrayList<>();
        if (NBlankable.isBlank(typedId)) {
            return fail(problems, update
                    ? "missing registration id (usage: /model update <id> --<param>=<value>)"
                    : "missing registration id (usage: /model add <id> --provider=<type> [--protocol=<wire>], "
                    + "or /model add <id> --protocol=<wire> --url=\u2026 --models=a,b)");
        }
        if (typedId.indexOf('/') >= 0) {
            return fail(problems, String.format(
                    "invalid registration id '%s': '/' separates the instance from the model in a key like '%s'.",
                    typedId, typedId + "/<model>"));
        }

        Map<String, NaruModelRegistration> regs = registrations(session);
        String storedId = typedId;
        NaruModelRegistration existing = null;
        if (regs != null) {
            existing = regs.get(typedId);
            if (existing == null) {
                String key = findRegistrationKey(regs, typedId);
                if (key != null) {
                    storedId = key;
                    existing = regs.get(key);
                }
            }
        }
        if (update && existing == null) {
            return fail(problems, String.format(
                    "no registration '%s' \u2014 see '/model registered' (create it with '/model add').", typedId));
        }

        // An update merges into what its own scope already declares, never into the merged view:
        // seed that base with the existing registration and take the other scope's fields back out.
        NaruModelRegistration.Builder builder = existing == null
                ? NaruModelRegistration.builder(storedId)
                : ownedBase(session, storedId, existing, scope);
        Map<String, NElement> input = edits == null ? Map.of() : edits;
        for (Map.Entry<String, NElement> e : input.entrySet()) {
            String k = e.getKey();
            if (k == null) {
                continue;
            }
            k = k.trim();
            if (k.isEmpty()) {
                continue;
            }
            builder.put(k, e.getValue());
        }

        String providerValue = builder.providerValue().orNull();
        String protocolValue = builder.protocolValue().orNull();
        if (existing != null && providerValue == null && !existing.isGeneric()) {
            return fail(problems, String.format(
                    "cannot clear '%s's provider with '--provider=' (an update only merges) \u2014 to turn it "
                            + "into a generic endpoint, remove it and add it again with "
                            + "'--protocol=<wire> --url=\u2026 --models=a,b'.", typedId));
        }
        if (providerValue == null && protocolValue == null) {
            return fail(problems, String.format(
                    "missing identity for '%s' \u2014 use '--provider=<type>' for a built-in provider, "
                            + "or '--protocol=<wire> --url=\u2026 --models=a,b' for a generic endpoint.", typedId));
        }
        if (providerValue != null) {
            String lower = providerValue.trim().toLowerCase();
            if (lower.equals("openapi")) {
                return fail(problems, "'--provider=openapi' is gone \u2014 'openai' is now both a wire id and a "
                        + "built-in provider type: use '--provider=openai' for OpenAI's API itself, or "
                        + "'--protocol=openai --url=\u2026 --models=a,b' for a generic endpoint.");
            }
            if (lower.equals("wire")) {
                return fail(problems, "'--provider=wire' was the old name of the internal endpoint type (now "
                        + "'custom') \u2014 a generic endpoint spells its wire shape with '--protocol=<wire> "
                        + "--url=\u2026 --models=a,b', no --provider.");
            }
            if (lower.equals("custom")) {
                return fail(problems, "'--provider=custom' is internal to registrations \u2014 a generic endpoint "
                        + "spells its wire shape with '--protocol=<wire> --url=\u2026 --models=a,b', no --provider.");
            }
            if (lower.equals("anthropic")) {
                return fail(problems, String.format(
                        "'--provider=%s' is a wire protocol id, not a provider type \u2014 a generic endpoint "
                                + "spells it as '--protocol=%s --url=\u2026 --models=a,b'.", providerValue, lower));
            }
        }

        NaruModelRegistration reg;
        try {
            reg = builder.build();
        } catch (RuntimeException e) {
            return fail(problems, String.valueOf(e.getMessage()));
        }

        // a free id: a built-in (or any other non-registration) instance is never overwritten —
        // the session reload refuses that too, but a write must not discover it afterwards
        if (existing == null) {
            NaruModelProvider collides = findProvider(session, storedId);
            if (collides != null) {
                return fail(problems, String.format(
                        "id '%s' is already used by provider '%s' (type %s) \u2014 choose another id.",
                        storedId, collides.name(), collides.type()));
            }
        }

        // the type that serves this endpoint: the declared provider type, or the internal custom
        // class for a generic one — which is why the lookup asks endpointType(), not provider()
        NaruModelProvider typeProvider = findProviderType(session, reg.endpointType());
        if (typeProvider == null) {
            return fail(problems, String.format(
                    "unknown provider type '%s'. Known types: %s.",
                    reg.endpointType(), knownProviderTypes(session)));
        }

        // --protocol must name a wire shape this type speaks: naming the type's own default wire
        // id is a no-op accepted idempotently; anything else is rejected rather than silently
        // ignored, because an unknown id is never guessed at
        String protocol = null;
        NOptional<String> proto = reg.protocol();
        if (proto.isPresent()) {
            String wanted = proto.get();
            String match = null;
            Set<String> supported = typeProvider.supportedProtocols();
            if (supported != null) {
                for (String sp : supported) {
                    if (sp.equalsIgnoreCase(wanted)) {
                        match = sp;
                        break;
                    }
                }
            }
            if (match == null && typeProvider.defaultProtocol() != null
                    && typeProvider.defaultProtocol().equalsIgnoreCase(wanted)) {
                match = typeProvider.defaultProtocol();
            }
            if (match == null) {
                return fail(problems, supported == null || supported.isEmpty()
                        ? String.format("provider type '%s' does not support --protocol: its wire shape is fixed.",
                        reg.endpointType())
                        : String.format("unknown protocol '%s' for provider type '%s' (supported: %s).",
                        wanted, reg.endpointType(), String.join(", ", new TreeSet<>(supported))));
            }
            if (!reg.isGeneric()
                    && typeProvider.defaultProtocol() != null
                    && match.equalsIgnoreCase(typeProvider.defaultProtocol())) {
                // naming a built-in type's own default wire is a no-op: nothing is stored and the
                // wire falls back to the default. A generic endpoint's protocol is its identity,
                // so it is always stored.
                reg = reg.with(NaruRegistrationFields.PROTOCOL, null);
                protocol = null;
            } else {
                if (!match.equals(wanted)) {
                    reg = reg.with(NaruRegistrationFields.PROTOCOL, match);
                }
                protocol = match;
            }
        }

        // a generic endpoint has no provider class to enumerate or address — it needs a base url
        // and the models it serves, or there is nothing to call
        boolean generic = reg.isGeneric();
        if (generic) {
            if (NBlankable.isBlank(reg.url().orNull())) {
                return fail(problems, String.format(
                        "a generic endpoint needs a base url \u2014 e.g. '/model add %s --protocol=%s "
                                + "--url=\u2026 --models=a,b'.",
                        typedId, protocol == null ? typeProvider.defaultProtocol() : protocol));
            }
            if (!reg.model().isPresent() && reg.models().isEmpty()) {
                return fail(problems, String.format(
                        "a generic endpoint needs --model=<id> or --models=a,b \u2014 no provider class exists "
                                + "to enumerate its models (e.g. '/model add %s --protocol=%s --url=\u2026 "
                                + "--models=a,b').",
                        typedId, protocol == null ? typeProvider.defaultProtocol() : protocol));
            }
        }

        return new Outcome(reg, protocol, existing == null, generic, List.of());
    }

    /**
     * The provider ids this session's registry can build a registration from: the declared
     * <b>types</b>, never instance ids. A settings form offers these as its provider menu.
     */
    public static List<String> providerTypes(NaruSession session) {
        Map<String, NaruModelProvider> all = session == null || session.registry() == null
                ? null : session.registry().modelProviders();
        if (all == null) {
            return List.of();
        }
        TreeSet<String> types = new TreeSet<>();
        for (NaruModelProvider v : all.values()) {
            if (v != null && !NBlankable.isBlank(v.type())) {
                types.add(v.type());
            }
        }
        return new ArrayList<>(types);
    }

    /**
     * The wire shapes {@code providerType} honours — its own default included. Empty for an
     * unknown type, so a form offers nothing rather than a guess.
     */
    public static Set<String> supportedProtocols(NaruSession session, String providerType) {
        NaruModelProvider p = findProviderType(session, providerType);
        if (p == null) {
            return Set.of();
        }
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (p.supportedProtocols() != null) {
            out.addAll(p.supportedProtocols());
        }
        if (p.defaultProtocol() != null) {
            out.add(p.defaultProtocol());
        }
        return out;
    }

    private static Outcome fail(List<String> problems, String problem) {
        problems.add(problem);
        return new Outcome(null, null, false, false, problems);
    }

    /**
     * The base an update merges into: the existing registration with everything the written scope
     * does <b>not</b> own taken back out.
     *
     * <p>Merging the stored <b>view</b> instead would copy another scope's fields into the file
     * being written: saving a personal registration would carry this project's literal key into
     * {@code ~/.naru}, and the two files would then disagree about which one owns the value. The
     * identity fields travel anyway. A file that names no provider is a different endpoint, and
     * the store would replace the entry wholesale rather than complete it.
     */
    private static NaruModelRegistration.Builder ownedBase(NaruSession session, String id,
                                                           NaruModelRegistration existing,
                                                           NaruRegistrationScope scope) {
        NaruModelRegistration.Builder b = existing.toBuilder();
        if (scope == null) {
            // merge the stored registration as a whole: a host whose store has no scope axis
            return b;
        }
        NOptional<NaruRegistrationOrigin> o = session == null ? null : session.registrationOrigin(id);
        NaruRegistrationOrigin origin = o == null ? null : o.orNull();
        if (origin == null) {
            // a host that answers nothing about origins has one layer, so its stored registration
            // is already the scope being written
            return b;
        }
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            if (!f.isIdentity() && origin.scopeOf(f.key()) != scope) {
                f.set(b, null);
            }
        }
        for (String key : existing.extras().keySet()) {
            if (origin.scopeOf(key) != scope) {
                b.extras(key, null);
            }
        }
        return b;
    }

    private static Map<String, NaruModelRegistration> registrations(NaruSession session) {
        if (session == null) {
            return Map.of();
        }
        try {
            Map<String, NaruModelRegistration> regs = session.registrations();
            return regs == null ? Map.of() : regs;
        } catch (RuntimeException e) {
            // a session that cannot read its store still has to answer the syntax checks,
            // but it must not be handed a write it cannot verify — the caller's registry and
            // protocol checks below are what refuse it
            return Map.of();
        }
    }

    private static String findRegistrationKey(Map<String, NaruModelRegistration> regs, String id) {
        if (regs == null || id == null) {
            return null;
        }
        if (regs.containsKey(id)) {
            return id;
        }
        for (String k : regs.keySet()) {
            if (k.equalsIgnoreCase(id)) {
                return k;
            }
        }
        return null;
    }

    private static NaruModelProvider findProvider(NaruSession session, String id) {
        if (id == null || session == null || session.registry() == null) {
            return null;
        }
        NOptional<NaruModelProvider> p = session.registry().provider(id);
        return p == null ? null : p.orNull();
    }

    /**
     * The provider implementing a <b>type</b> (never an instance id): materializing a
     * registration and validating a provider both need the type's class, and a built-in always
     * exists under its own type name.
     */
    private static NaruModelProvider findProviderType(NaruSession session, String type) {
        if (NBlankable.isBlank(type) || session == null) {
            return null;
        }
        NaruModelProvider p = findProvider(session, type);
        if (p != null && type.equalsIgnoreCase(p.type())) {
            return p;
        }
        Map<String, NaruModelProvider> all = session.registry() == null
                ? null : session.registry().modelProviders();
        if (all != null) {
            for (NaruModelProvider v : all.values()) {
                if (v != null && type.equalsIgnoreCase(v.type())) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String knownProviderTypes(NaruSession session) {
        return String.join(", ", providerTypes(session));
    }
}
