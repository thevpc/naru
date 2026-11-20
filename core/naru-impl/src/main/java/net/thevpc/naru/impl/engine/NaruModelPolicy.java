package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelFilter;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The policy axis of model visibility: which providers and models a project is
 * <em>allowed</em> to use, declared as configuration keys rather than chosen
 * per view.
 *
 * <p>{@link NaruModelFilter} is the mechanism and {@code modelFilter(...)} on the
 * workspace is how a host sets it in code; this class is how the same decision is
 * written down in {@code .naru/config/env.tson} so it applies to every session the
 * project starts, including ones nobody controls from outside. The keys are read in
 * the layered order the rest of the env uses — agent env, then the project's two
 * config files, then the JVM system property as the weakest layer — and combined with
 * AND: an allow-list, a deny-list and {@code localOnly} all have to pass.
 *
 * <p>Deliberately missing: a "models with tool support only" filter. {@link
 * NaruModelFilter#accept(net.thevpc.naru.api.model.NaruModelKey,
 * net.thevpc.naru.api.task.NaruTask)} is handed a key, not a capability set, and
 * capabilities are only known by building the protocol and probing it — which would
 * mean one network round trip per listed model, per keystroke, for a listing. The
 * registration's own {@code tools} and {@code enabled} params are the supported way
 * to say it about a provider you declare; the view-side filter in the desktop is the
 * way to say it about a provider you do not.
 *
 * <p>A bad value is ignored and logged rather than thrown: a workspace that refuses to
 * start because of a typo in a policy key is a worse failure than one the operator can
 * see and fix, and the log line says exactly which key was ignored.
 */
public final class NaruModelPolicy {

    /** Comma-separated provider ids that are the only ones allowed; empty or absent allows all. */
    public static final String ALLOW_PROVIDERS = "model.filter.allowProviders";
    /** Comma-separated provider ids that are refused even when the allow-list would keep them. */
    public static final String DENY_PROVIDERS = "model.filter.denyProviders";
    /** Comma-separated {@code provider/model} keys that are refused individually. */
    public static final String DENY_MODELS = "model.filter.denyModels";
    /** Allows only models on a local endpoint. */
    public static final String LOCAL_ONLY = "model.filter.localOnly";

    private static final List<String> KEYS = List.of(ALLOW_PROVIDERS, DENY_PROVIDERS, DENY_MODELS, LOCAL_ONLY);

    private NaruModelPolicy() {
    }

    /** The policy keys, for the code that lists or writes them. */
    public static List<String> keys() {
        return KEYS;
    }

    /**
     * The filter the configured policy asks for, or null when it says nothing.
     *
     * <p>null is the registry's own "no filter" sentinel, so a project that sets none of
     * these keys must keep the behaviour it had before the keys existed.
     */
    public static NaruModelFilter filterFor(NaruWorkspace workspace) {
        if (workspace == null) {
            return null;
        }
        // agent env first: like every other env lookup, the narrower layer wins
        List<NaruEnv> layers = Arrays.asList(workspace.agentEnv(), workspace.env());
        List<String> warns = new ArrayList<>();

        NaruModelFilter filter = null;
        List<String> allowed = csv(layers, ALLOW_PROVIDERS, warns);
        if (!allowed.isEmpty()) {
            filter = and(filter, NaruModelFilter.providers(allowed.toArray(new String[0])));
        }
        List<String> denied = csv(layers, DENY_PROVIDERS, warns);
        if (!denied.isEmpty()) {
            filter = and(filter, NaruModelFilter.denyProviders(denied.toArray(new String[0])));
        }
        List<String> deniedModels = deniedModelKeys(layers, warns);
        if (!deniedModels.isEmpty()) {
            filter = and(filter, denyModelKeys(deniedModels));
        }
        NOptional<Boolean> local = literal(layers, LOCAL_ONLY, warns);
        // "localOnly=false" is not a filter at all; only an affirmative restriction adds one
        if (local.orNull() != null && local.get()) {
            filter = and(filter, NaruModelFilter.localOnly());
        }
        for (String warn : warns) {
            workspace.log(NaruLogMode.SCRIPT, NMsg.ofC("model policy: %s", warn));
        }
        return filter;
    }

    private static NaruModelFilter and(NaruModelFilter a, NaruModelFilter b) {
        return a == null ? b : a.and(b);
    }

    // ── value reading ─────────────────────────────────────────────────────────

    /**
     * The first layer that names the key, as text. A TSON boolean is accepted as well as a
     * quoted string, because {@code model.filter.localOnly: true} is the natural thing to
     * write by hand and dropping it silently would read as the policy being ignored.
     */
    private static String raw(NaruEnv env, String key, List<String> warns) {
        if (env == null) {
            return null;
        }
        NOptional<NElement> value = env.get(key);
        NElement e = value.orNull();
        if (e == null) {
            return null;
        }
        String s = e.asStringValue().orNull();
        if (s != null) {
            return s;
        }
        NOptional<Boolean> b = e.asBooleanValue();
        Boolean bb = b.orNull();
        if (bb != null) {
            return String.valueOf(bb);
        }
        warns.add(NMsg.ofC("'%s': expected a string or a boolean, ignored %s", key, e).toString());
        return null;
    }

    /** The first layer that names the key. */
    private static String first(List<NaruEnv> layers, String key, List<String> warns) {
        for (NaruEnv layer : layers) {
            String v = raw(layer, key, warns);
            if (!NBlankable.isBlank(v)) {
                return v;
            }
        }
        // the documented last layer of the env chain (NaruTaskConfig): the weakest one, so
        // a checked-in policy beats a launch flag rather than the other way round
        String property = System.getProperty(key);
        return NBlankable.isBlank(property) ? null : property;
    }

    /** A comma-separated list; blanks and the whole {@code ", ,"} shape collapse to an empty list. */
    private static List<String> csv(List<NaruEnv> layers, String key, List<String> warns) {
        String v = first(layers, key, warns);
        if (v == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : v.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The {@code provider/model} entries, kept only if they spell a full key. A bare name is
     * refused with a warning rather than guessed at: {@link net.thevpc.naru.api.model.NaruModelKey#parse}
     * defaults it to {@code ollama/...}, so a typo in a deny-list would hide a model on a
     * server nobody mentioned.
     */
    private static List<String> deniedModelKeys(List<NaruEnv> layers, List<String> warns) {
        String v = first(layers, DENY_MODELS, warns);
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            if (t.indexOf('/') <= 0 || t.indexOf('/') == t.length() - 1) {
                warns.add(NMsg.ofC("'%s': '%s' is not a provider/model key, ignored — list a whole "
                        + "provider under " + DENY_PROVIDERS, DENY_MODELS, part.trim()).toString());
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static NOptional<Boolean> literal(List<NaruEnv> layers, String key, List<String> warns) {
        String v = first(layers, key, warns);
        if (v == null) {
            return NOptional.ofNamedEmpty(key);
        }
        NOptional<Boolean> b = NLiteral.of(v).asBoolean();
        if (!b.isPresent()) {
            warns.add(NMsg.ofC("'%s': '%s' is not true or false, ignored", key, v).toString());
        }
        return b;
    }

    private static NaruModelFilter denyModelKeys(List<String> lowercasedKeys) {
        Set<String> denied = new LinkedHashSet<>(lowercasedKeys);
        return NaruModelFilter.of(model -> {
            if (model == null || model.provider() == null || model.model() == null) {
                return true;
            }
            return !denied.contains((model.provider() + "/" + model.model()).toLowerCase(Locale.ROOT));
        });
    }
}
