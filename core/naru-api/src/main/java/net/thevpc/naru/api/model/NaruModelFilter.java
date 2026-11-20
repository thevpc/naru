package net.thevpc.naru.api.model;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NBlankable;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides whether a model may be used, at the level of the whole workspace or of a
 * single session.
 *
 * <p>naru discovers and registers every {@link NaruModelProvider} on the classpath, so a
 * catalog can span free local servers and paid cloud APIs at once. A workspace or session
 * can narrow that catalog with a filter: a denied model disappears from
 * {@link net.thevpc.naru.api.registry.NaruRegistry#modelsKeys(NaruSession)} (and therefore
 * from listings, positional indexes and the "best fit" auto-selection), and a model call
 * is refused before any transport is built. It is the seam a test uses to stay offline
 * ("only local ollama"), and the seam a host uses to pin a workload to an approved set of
 * providers.
 *
 * <p>The decision receives the {@link NaruModelKey} being considered, and the
 * {@link NaruTask} in whose context the decision is made. The task is {@code null} when
 * the catalog is enumerated outside any task (a listing, a resolution), so an
 * implementation that depends on the task must be null-safe. A filter that only inspects
 * the key (the common case, see {@link #providers(String...)}) does not care.
 *
 * <p>A {@code null} filter means "allow everything", so the default is unchanged.
 */
@FunctionalInterface
public interface NaruModelFilter {

    /**
     * @param model the candidate model (provider id + model id); never null
     * @param task  the task in whose context the model is considered, or null when the
     *              decision is made outside any task (catalog listing/resolution)
     * @return true to allow the model, false to deny it
     */
    boolean accept(NaruModelKey model, NaruTask task);

    /**
     * Both filters must allow the model.
     */
    default NaruModelFilter and(NaruModelFilter other) {
        return (model, task) -> accept(model, task) && other.accept(model, task);
    }

    /**
     * Either filter may allow the model.
     */
    default NaruModelFilter or(NaruModelFilter other) {
        return (model, task) -> accept(model, task) || other.accept(model, task);
    }

    /**
     * Inverts the filter: a model is allowed exactly when this filter denies it.
     */
    default NaruModelFilter negate() {
        return (model, task) -> !accept(model, task);
    }

    /**
     * Adapts a key-only {@link Predicate} (the task is ignored).
     */
    static NaruModelFilter of(Predicate<NaruModelKey> predicate) {
        return (model, task) -> predicate.test(model);
    }

    /**
     * Allows every model. Equivalent to no filter at all.
     */
    static NaruModelFilter allowAll() {
        return (model, task) -> true;
    }

    /**
     * Denies every model. A convenient base for {@link #or(NaruModelFilter)} chains.
     */
    static NaruModelFilter denyAll() {
        return (model, task) -> false;
    }

    /**
     * Allows only models served by the named provider instances (compared to
     * {@link NaruModelKey#provider()}, case-insensitively). A bare provider such as
     * {@code "ollama"} is the canonical way to keep a run on a local server.
     */
    static NaruModelFilter providers(String... providerIds) {
        Set<String> names = normalized(providerIds);
        return (model, task) -> model != null && model.provider() != null
                && names.contains(model.provider().toLowerCase(Locale.ROOT));
    }

    /**
     * The complement of {@link #providers(String...)}: everything but the named providers.
     */
    static NaruModelFilter denyProviders(String... providerIds) {
        return providers(providerIds).negate();
    }

    /**
     * Allows only models on a local endpoint (an Ollama, llama.cpp or Colibri server, or a
     * custom registration whose URL is loopback).
     *
     * <p>Locality is a property of the provider instance and its session
     * ({@link NaruModelProvider#isLocal(net.thevpc.naru.api.agent.NaruSession)}), so it can
     * only be established with a task in context. Outside a task — a catalog listing — the
     * check falls back to the conventional local provider ids, so the listing still
     * reflects what a task could use.
     */
    static NaruModelFilter localOnly() {
        return (model, task) -> {
            if (model == null || model.provider() == null) {
                return false;
            }
            if (task != null) {
                NaruModelProvider p = task.session().registry().provider(model.provider()).orNull();
                if (p != null) {
                    return p.isLocal(task.session());
                }
            }
            return isConventionallyLocal(model.provider());
        };
    }

    /**
     * The ids of the providers that are local by construction. Used only when no task is in
     * context to ask the provider itself.
     */
    static boolean isConventionallyLocal(String providerId) {
        if (providerId == null) {
            return false;
        }
        String id = providerId.toLowerCase(Locale.ROOT);
        return id.contains("ollama") || id.equals("llamacpp") || id.equals("llama-cpp")
                || id.equals("colibri");
    }

    private static Set<String> normalized(String... providerIds) {
        Set<String> set = new LinkedHashSet<>();
        if (providerIds != null) {
            for (String id : providerIds) {
                if (!NBlankable.isBlank(id)) {
                    set.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return set;
    }
}
