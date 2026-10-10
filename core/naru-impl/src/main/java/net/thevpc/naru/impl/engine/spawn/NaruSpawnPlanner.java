package net.thevpc.naru.impl.engine.spawn;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.context.NaruCompactors;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruWindowSpec;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.spawn.NaruSpawnSeed;
import net.thevpc.naru.api.spawn.NaruSpawnSource;
import net.thevpc.naru.api.spawn.NaruSpawnStrategy;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves a spawn: strategy, inherit kinds, tags, exclusions, skills and env seeds —
 * from the extension contributions ({@link NaruSpawnContextImpl}), the named policy, the
 * call-site flags, and the target contract — and validates the contract against the
 * resolved tag set.
 * <p>
 * Resolution order and precedence (highest wins, later applied later):
 * extension/spawn-kind default → named policy → call-site flags → contract validation.
 * Equal-precedence additions win over equal-precedence revocations (add-wins-over-revoke).
 */
public final class NaruSpawnPlanner {

    private NaruSpawnPlanner() {
    }

    /**
     * The outcome of a plan: the resolved seeds plus the effective scalar overrides
     * (model, working dir, prompt mode — inherit-or-override only) and the conversation
     * messages to seed the child with per the context strategy.
     */
    public static final class Plan {
        public final NaruSpawnResolution resolution;
        public final String model;
        public final NPath workingDir;
        public final net.thevpc.naru.api.mode.NaruPromptMode promptMode;
        public final List<NaruMessage> contextMessages;

        Plan(NaruSpawnResolution resolution, String model,
             NPath workingDir,
             net.thevpc.naru.api.mode.NaruPromptMode promptMode,
             List<NaruMessage> contextMessages) {
            this.resolution = resolution;
            this.model = model;
            this.workingDir = workingDir;
            this.promptMode = promptMode;
            this.contextMessages = contextMessages;
        }
    }

    public static Plan plan(NaruSession session, NaruTaskSpec spec, NaruSpawnContextImpl ctx) {
        NaruTask parent = ctx.parent();
        NaruSpawnPolicy policy = ctx.policy();
        List<String> warnings = new ArrayList<>(ctx.warnings());

        // ── context strategy ────────────────────────────────────────────────
        StrategyChoice strategyChoice = resolveStrategy(ctx, policy, spec);
        ctx.setStrategy(strategyChoice.strategy);
        if (!(parent == null && strategyChoice.strategy == NaruSpawnStrategy.NONE)
                && strategyChoice.strategy != NaruSpawnStrategy.NONE && parent == null) {
            warnings.add("context strategy " + strategyChoice.strategy.name().toLowerCase()
                    + " has no parent to inherit conversation from; the child starts empty");
        }

        // ── inherit kinds ───────────────────────────────────────────────────
        Map<NaruSpawnInherit, NaruSpawnSource> inherited = new LinkedHashMap<>();
        for (Map.Entry<NaruSpawnInherit, NaruSpawnSeed<NaruSpawnInherit>> e : ctx.inheritSeeds().entrySet()) {
            mergeSource(inherited, e.getKey(), e.getValue().source());
        }
        if (policy != null) {
            for (NaruSpawnInherit k : policy.inherit()) {
                mergeSource(inherited, k, NaruSpawnSource.POLICY);
            }
        }
        for (NaruSpawnInherit k : spec.inherit()) {
            mergeSource(inherited, k, NaruSpawnSource.FLAG);
        }
        // strategy implication (the /start fork default): only when the enumeration did
        // not already cover tags. Enumerating env must not silently cancel the fork's
        // implied tag snapshot, and there is no way to say "no tags" at a fork — a caller
        // who wants fewer tags revokes them.
        if (!inherited.containsKey(NaruSpawnInherit.TAGS)
                && strategyChoice.strategy.impliesTagsInherit()) {
            inherited.put(NaruSpawnInherit.TAGS, NaruSpawnSource.DEFAULT);
        }

        // ── tags ────────────────────────────────────────────────────────────
        Map<String, NaruSpawnSeed<String>> adds = new LinkedHashMap<>();
        for (Map.Entry<String, NaruSpawnSeed<String>> e : ctx.addTagSeeds().entrySet()) {
            mergeSeed(adds, e.getKey(), e.getValue());
        }
        if (policy != null) {
            for (String t : policy.addTags()) {
                mergeSeed(adds, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.POLICY));
            }
        }
        for (String t : spec.addTags()) {
            mergeSeed(adds, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.FLAG));
        }
        // the legacy direct grant list behaves like an add at the call-site level
        for (String t : spec.toolTags()) {
            mergeSeed(adds, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.FLAG));
        }

        Map<String, NaruSpawnSeed<String>> revokes = new LinkedHashMap<>();
        for (Map.Entry<String, NaruSpawnSeed<String>> e : ctx.revokeTagSeeds().entrySet()) {
            mergeSeed(revokes, e.getKey(), e.getValue());
        }
        if (policy != null) {
            for (String t : policy.revokeTags()) {
                mergeSeed(revokes, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.POLICY));
            }
        }
        for (String t : spec.revokeTags()) {
            mergeSeed(revokes, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.FLAG));
        }

        boolean inheritTags = inherited.containsKey(NaruSpawnInherit.TAGS);
        Set<String> base = new LinkedHashSet<>();
        if (inheritTags && parent != null) {
            base.addAll(parent.findToolTagNames());
        }

        NaruSpawnSource tagsSource = inherited.getOrDefault(NaruSpawnInherit.TAGS, NaruSpawnSource.DEFAULT);
        Map<String, NaruSpawnSeed<String>> resolved = new TreeMap<>();
        for (String name : base) {
            resolved.put(name, NaruSpawnSeed.of(name, tagsSource));
        }
        for (Map.Entry<String, NaruSpawnSeed<String>> e : adds.entrySet()) {
            resolved.put(e.getKey(), e.getValue());
        }
        List<NaruSpawnSeed<String>> revoked = new ArrayList<>();
        for (Map.Entry<String, NaruSpawnSeed<String>> e : revokes.entrySet()) {
            String name = e.getKey();
            NaruSpawnSeed<String> add = adds.get(name);
            if (add != null && rank(add.source()) >= rank(e.getValue().source())) {
                continue; // add-wins-over-revoke at equal or higher precedence
            }
            if (resolved.remove(name) != null) {
                revoked.add(e.getValue());
            } else {
                warnings.add("revoke-without-hold: --revoke-tags=" + name
                        + " does not name a tag the resolution holds (nothing inherited or added it)");
            }
        }
        List<NaruSpawnSeed<String>> tags = new ArrayList<>(resolved.values());

        // ── exclusions ──────────────────────────────────────────────────────
        Map<String, NaruSpawnSeed<String>> exclMap = new TreeMap<>();
        for (Map.Entry<String, NaruSpawnSeed<String>> e : ctx.exclusionSeeds().entrySet()) {
            mergeSeed(exclMap, e.getKey(), e.getValue());
        }
        if (policy != null) {
            for (String t : policy.excludeTools()) {
                mergeSeed(exclMap, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.POLICY));
            }
        }
        for (String t : spec.excludeTools()) {
            mergeSeed(exclMap, norm(t), NaruSpawnSeed.of(t, NaruSpawnSource.FLAG));
        }
        List<NaruSpawnSeed<String>> exclusions = new ArrayList<>(exclMap.values());

        // ── skills ──────────────────────────────────────────────────────────
        Map<String, NaruSpawnSeed<String>> skillMap = new TreeMap<>();
        for (Map.Entry<String, NaruSpawnSeed<String>> e : ctx.skillSeeds().entrySet()) {
            mergeSeed(skillMap, e.getKey(), e.getValue());
        }
        if (policy != null) {
            for (String s : policy.addSkills()) {
                mergeSeed(skillMap, norm(s), NaruSpawnSeed.of(s, NaruSpawnSource.POLICY));
            }
        }
        for (String s : spec.addSkills()) {
            mergeSeed(skillMap, norm(s), NaruSpawnSeed.of(s, NaruSpawnSource.FLAG));
        }
        if (ctx.contract() != null) {
            for (String s : ctx.contract().skills()) {
                mergeSeed(skillMap, norm(s), NaruSpawnSeed.of(s, NaruSpawnSource.CONTRACT));
            }
        }
        List<NaruSpawnSeed<String>> skills = new ArrayList<>(skillMap.values());

        // ── env ─────────────────────────────────────────────────────────────
        Map<String, NaruSpawnSeed<Object>> envMap = new TreeMap<>();
        for (Map.Entry<String, NaruSpawnSeed<Object>> e : ctx.envSeeds().entrySet()) {
            mergeEnvSeed(envMap, e.getKey(), e.getValue());
        }
        if (inherited.containsKey(NaruSpawnInherit.ENV) && parent != null) {
            NaruSpawnSource envSource = inherited.get(NaruSpawnInherit.ENV);
            for (Map.Entry<String, Object> e : parent.getTaskEnv().entrySet()) {
                mergeEnvSeed(envMap, e.getKey(), NaruSpawnSeed.of(e.getValue(), envSource));
            }
        }

        // ── contract validation ─────────────────────────────────────────────
        NaruSpawnContract contract = ctx.contract();
        if (contract != null) {
            Set<String> tagNames = new LinkedHashSet<>();
            for (NaruSpawnSeed<String> t : tags) {
                tagNames.add(t.value());
            }
            List<String> violations = contract.violations(tagNames);
            if (!violations.isEmpty()) {
                List<String> hints = new ArrayList<>();
                for (String v : violations) {
                    if (v.startsWith("+")) {
                        hints.add("grant the tag with --add-tags=" + v.substring(1) + " or --inherit=tags");
                    } else {
                        hints.add("revoke it with --revoke-tags=" + v.substring(1));
                    }
                }
                throw new IllegalArgumentException(
                        "spawn contract " + contract + " is not satisfied by the resolved tags "
                                + tagNames + " (violations: " + String.join(", ", violations) + "); "
                                + String.join("; ", hints));
            }
            // A contract cannot grant a tool -- tags do that -- but it can name the tools the
            // target needs and refuse to spawn if the resolution excludes one by name.
            if (!contract.tools().isEmpty()) {
                Set<String> excludedNames = new LinkedHashSet<>();
                for (NaruSpawnSeed<String> e : exclusions) {
                    excludedNames.add(e.value());
                    excludedNames.add(norm(e.value()));
                }
                List<String> missing = new ArrayList<>();
                for (String t : contract.tools()) {
                    if (excludedNames.contains(t) || excludedNames.contains(norm(t))) {
                        missing.add(t);
                    }
                }
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException(
                            "spawn contract " + contract + " requires tools the resolution excludes: "
                                    + String.join(", ", missing) + "; drop the exclusion ("
                                    + "--exclude-tools=... at the call site or the policy) or remove the tool from the contract");
                }
            }
        }

        // ── conversation context seeding ─────────────────────────────────────
        List<NaruMessage> contextMessages = seedContext(parent, strategyChoice, ctx, warnings);

        // ── scalar overrides (inherit-or-override only) ─────────────────────
        String model = spec.model() != null
                ? spec.model()
                : (policy != null && policy.model() != null ? policy.model() : null);
        NPath workingDir = spec.workingDirectory() != null
                ? spec.workingDirectory()
                : (policy != null ? policy.workingDir() : null);
        net.thevpc.naru.api.mode.NaruPromptMode promptMode = spec.promptMode() != null
                ? spec.promptMode()
                : (policy != null ? policy.promptMode() : null);

        NaruSpawnResolution resolution = new NaruSpawnResolution(
                strategyChoice.strategy,
                strategyChoice.windowTurns,
                strategyChoice.source,
                inherited,
                tags,
                revoked,
                exclusions,
                skills,
                envMap,
                policy == null ? null : policy.name(),
                contract,
                warnings);
        ctx.setResolution(resolution);
        return new Plan(resolution, model, workingDir, promptMode, contextMessages);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static final class StrategyChoice {
        final NaruSpawnStrategy strategy;
        final int windowTurns;
        final NaruSpawnSource source;

        StrategyChoice(NaruSpawnStrategy strategy, int windowTurns, NaruSpawnSource source) {
            this.strategy = strategy;
            this.windowTurns = windowTurns;
            this.source = source;
        }
    }

    private static StrategyChoice resolveStrategy(NaruSpawnContextImpl ctx, NaruSpawnPolicy policy,
                                                  NaruTaskSpec spec) {
        NaruSpawnStrategy strategy = NaruSpawnStrategy.NONE;
        int window = 0;
        NaruSpawnSource source = NaruSpawnSource.DEFAULT;
        if (policy != null && policy.strategy() != null) {
            strategy = policy.strategy();
            window = policy.windowTurns();
            source = NaruSpawnSource.POLICY;
        }
        if (spec.strategy() != null && rank(NaruSpawnSource.FLAG) >= rank(source)) {
            strategy = spec.strategy();
            window = spec.windowTurns();
            source = NaruSpawnSource.FLAG;
        }
        NaruSpawnSeed<NaruSpawnStrategy> seed = ctx.strategySeed();
        if (seed != null && rank(seed.source()) >= rank(source)) {
            strategy = seed.value();
            window = seed.value() == NaruSpawnStrategy.WINDOW ? ctx.windowTurns() : 0;
            source = seed.source();
        }
        if (strategy == NaruSpawnStrategy.WINDOW && window <= 0) {
            throw new IllegalArgumentException(
                    "window spawn strategy needs a positive window, e.g. --window=6turns");
        }
        return new StrategyChoice(strategy, window, source);
    }

    private static List<NaruMessage> seedContext(NaruTask parent, StrategyChoice choice,
                                                 NaruSpawnContextImpl ctx, List<String> warnings) {
        if (parent == null || choice.strategy == NaruSpawnStrategy.NONE) {
            return null;
        }
        List<NaruMessage> parentView = new ArrayList<>();
        for (NaruMessage m : parent.contextView()) {
            if (m.getRole() == NaruRole.system) {
                continue; // system material rebuilds per task
            }
            parentView.add(m.copy());
        }
        switch (choice.strategy) {
            case FORK:
                return parentView;
            case WINDOW: {
                return lastTurns(parentView, choice.windowTurns);
            }
            case SUMMARY: {
                int keep = Math.max(1, choice.windowTurns);
                if (parentView.isEmpty()) {
                    return null;
                }
                if (NaruCompactors.isInstalled(ctx.session())) {
                    try {
                        NaruCompactionResult r = NaruCompactors.preview(
                                parent,
                                parentView,
                                NaruContextSpec.of(0, NaruWindowSpec.lastTurns(keep)));
                        if (r.isSuccess() && r.summaryItem() != null) {
                            List<NaruMessage> out = new ArrayList<>();
                            out.add(r.summaryItem().copy());
                            return out;
                        }
                        warnings.add("summary context strategy could not summarize the parent context ("
                                + (r.message() == null ? r.outcome().name() : r.message())
                                + "); the child starts from the last turn instead");
                    } catch (Exception ex) {
                        warnings.add("summary context strategy failed to summarize the parent context ("
                                + ex.getMessage() + "); the child starts from the last turn instead");
                    }
                } else {
                    warnings.add("summary context strategy needs a context compactor (e.g. naru-tools-compact) "
                            + "to summarize the parent conversation; the child starts from the last turn instead");
                }
                return lastTurns(parentView, 1);
            }
            default:
                return null;
        }
    }

    private static List<NaruMessage> lastTurns(List<NaruMessage> messages, int turns) {
        if (messages.isEmpty() || turns <= 0) {
            return messages;
        }
        List<List<NaruMessage>> groups = new ArrayList<>();
        for (NaruMessage m : messages) {
            if (groups.isEmpty() || m.isTurnBoundary()) {
                groups.add(new ArrayList<>());
            }
            groups.get(groups.size() - 1).add(m);
        }
        int from = Math.max(0, groups.size() - turns);
        List<NaruMessage> out = new ArrayList<>();
        for (int i = from; i < groups.size(); i++) {
            out.addAll(groups.get(i));
        }
        return out;
    }

    private static String norm(String s) {
        String n = s == null ? "" : NNameFormat.LOWER_KEBAB_CASE.format(s.trim());
        return n.isEmpty() ? (s == null ? "" : s) : n;
    }

    private static int rank(NaruSpawnSource source) {
        switch (source == null ? NaruSpawnSource.DEFAULT : source) {
            case CONTRACT:
                return 3;
            case FLAG:
                return 2;
            case POLICY:
                return 1;
            default:
                return 0;
        }
    }

    private static <K> void mergeSource(Map<K, NaruSpawnSource> map, K key, NaruSpawnSource source) {
        NaruSpawnSource old = map.get(key);
        if (old == null || rank(source) >= rank(old)) {
            map.put(key, source);
        }
    }

    private static void mergeSeed(Map<String, NaruSpawnSeed<String>> map, String key,
                                  NaruSpawnSeed<String> seed) {
        NaruSpawnSeed<String> old = map.get(key);
        if (old == null || rank(seed.source()) >= rank(old.source())) {
            map.put(key, seed);
        }
    }

    private static void mergeEnvSeed(Map<String, NaruSpawnSeed<Object>> map, String key,
                                     NaruSpawnSeed<Object> seed) {
        NaruSpawnSeed<Object> old = map.get(key);
        if (old == null || rank(seed.source()) >= rank(old.source())) {
            map.put(key, seed);
        }
    }
}