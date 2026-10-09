package net.thevpc.naru.api.spawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resolved spawn plan: every seed that survived resolution and the source of each
 * item, plus the warnings raised while resolving.
 * <p>
 * This is what the {@code TaskSpawned} event reports and what {@code /start --explain}
 * prints without spawning: {@code provenanceText()} renders the item-7 form, e.g.
 * {@code skills=[code-review] (contract)}, {@code tags=[fs,git] (inherit − revoke)} and
 * {@code context=window(12) (flag)}.
 * <p>
 * Immutable once built by the core.
 */
public final class NaruSpawnResolution {

    private final NaruSpawnStrategy strategy;
    private final int windowTurns;
    private final NaruSpawnSource strategySource;
    private final Map<NaruSpawnInherit, NaruSpawnSource> inherited;
    private final List<NaruSpawnSeed<String>> tags;
    private final List<NaruSpawnSeed<String>> revokedTags;
    private final List<NaruSpawnSeed<String>> exclusions;
    private final List<NaruSpawnSeed<String>> skills;
    private final Map<String, NaruSpawnSeed<Object>> env;
    private final String policyName;
    private final NaruSpawnContract contract;
    private final List<String> warnings;

    public NaruSpawnResolution(
            NaruSpawnStrategy strategy,
            int windowTurns,
            NaruSpawnSource strategySource,
            Map<NaruSpawnInherit, NaruSpawnSource> inherited,
            List<NaruSpawnSeed<String>> tags,
            List<NaruSpawnSeed<String>> revokedTags,
            List<NaruSpawnSeed<String>> exclusions,
            List<NaruSpawnSeed<String>> skills,
            Map<String, NaruSpawnSeed<Object>> env,
            String policyName,
            NaruSpawnContract contract,
            List<String> warnings) {
        this.strategy = strategy == null ? NaruSpawnStrategy.NONE : strategy;
        this.windowTurns = Math.max(0, windowTurns);
        this.strategySource = strategySource == null ? NaruSpawnSource.DEFAULT : strategySource;
        this.inherited = Collections.unmodifiableMap(new LinkedHashMap<>(inherited));
        this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
        this.revokedTags = Collections.unmodifiableList(new ArrayList<>(revokedTags));
        this.exclusions = Collections.unmodifiableList(new ArrayList<>(exclusions));
        this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        this.policyName = policyName;
        this.contract = contract;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /** The resolved context strategy. */
    public NaruSpawnStrategy strategy() {
        return strategy;
    }

    /** The window size when {@link #strategy()} is {@link NaruSpawnStrategy#WINDOW}. */
    public int windowTurns() {
        return windowTurns;
    }

    /** Where the strategy came from: default / flag / policy. */
    public NaruSpawnSource strategySource() {
        return strategySource;
    }

    /** The kinds of parent state inherited as spawn-time snapshots, and their source. */
    public Map<NaruSpawnInherit, NaruSpawnSource> inherited() {
        return inherited;
    }

    /** The resolved tag set, one seed per tag with the source that dominates it. */
    public List<NaruSpawnSeed<String>> tags() {
        return tags;
    }

    /**
     * The tags the resolution revoked (informational; the revocation is already reflected
     * in {@link #tags()}).
     */
    public List<NaruSpawnSeed<String>> revokedTags() {
        return revokedTags;
    }

    /** The resolved tool exclusions, with provenance. */
    public List<NaruSpawnSeed<String>> exclusions() {
        return exclusions;
    }

    /** The resolved skill names, with provenance. */
    public List<NaruSpawnSeed<String>> skills() {
        return skills;
    }

    /**
     * The resolved env seeds, keyed by env-variable name; each seed value is the env value.
     */
    public Map<String, NaruSpawnSeed<Object>> env() {
        return env;
    }

    /** The name of the applied named policy, or null. */
    public String policyName() {
        return policyName;
    }

    /** The target contract, or null. */
    public NaruSpawnContract contract() {
        return contract;
    }

    /** Warnings raised while resolving (revoke-without-hold, degraded summary, ...). */
    public List<String> warnings() {
        return warnings;
    }

    /** Convenience for the resolved tag names, sorted for stable output. */
    public Set<String> tagNames() {
        Set<String> out = new LinkedHashSet<>();
        for (NaruSpawnSeed<String> t : tags) {
            out.add(t.value());
        }
        return out;
    }

    /** Convenience for the resolved skill names, in resolution order. */
    public Set<String> skillNames() {
        Set<String> out = new LinkedHashSet<>();
        for (NaruSpawnSeed<String> s : skills) {
            out.add(s.value());
        }
        return out;
    }

    /**
     * The resolved strategy rendered as {@code /start} context notation:
     * {@code none}, {@code fork}, {@code window(n)} or {@code summary}.
     */
    public String strategyText() {
        return strategy == NaruSpawnStrategy.WINDOW
                ? "window(" + windowTurns + ")"
                : strategy.name().toLowerCase();
    }

    /**
     * The item-7 provenance rendering: one line per resolved set carrying the source
     * annotation of each item, e.g.
     * {@code tags=[fs,git] (inherit − revoke write,exec)}. Lines are plain text, fit for
     * both the {@code TaskSpawned} event payload and {@code /start --explain}.
     */
    public List<String> provenanceText() {
        List<String> out = new ArrayList<>();
        out.add("strategy=" + strategyText() + " (" + strategySource + ")");
        if (!inherited.isEmpty()) {
            StringBuilder sb = new StringBuilder("inherit=");
            boolean first = true;
            for (Map.Entry<NaruSpawnInherit, NaruSpawnSource> e : inherited.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(e.getKey().name().toLowerCase());
                first = false;
            }
            sb.append(" (").append(inherited.values().iterator().next()).append(')');
            out.add(sb.toString());
        }
        out.add(line("tags", tags, revokedTags));
        out.add(line("skills", skills, null));
        out.add(line("exclusions", exclusions, null));
        if (!env.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, NaruSpawnSeed<Object>> e : env.entrySet()) {
                parts.add(e.getKey() + "=" + e.getValue().value() + " (" + e.getValue().source() + ")");
            }
            out.add("env=" + String.join(", ", parts));
        }
        if (policyName != null && !policyName.isEmpty()) {
            out.add("policy=" + policyName);
        }
        if (contract != null) {
            out.add("contract=" + contract);
        }
        for (String w : warnings) {
            out.add("warning: " + w);
        }
        return out;
    }

    private static String line(String name, List<NaruSpawnSeed<String>> items,
                               List<NaruSpawnSeed<String>> revoked) {
        StringBuilder sb = new StringBuilder(name).append('=');
        if (items.isEmpty()) {
            sb.append("[]");
            return sb.toString();
        }
        List<String> names = new ArrayList<>();
        for (NaruSpawnSeed<String> i : items) {
            names.add(i.value());
        }
        sb.append('[').append(String.join(",", names)).append(']');
        Map<NaruSpawnSource, List<String>> bySource = new LinkedHashMap<>();
        for (NaruSpawnSeed<String> i : items) {
            bySource.computeIfAbsent(i.source(), x -> new ArrayList<>()).add(i.value());
        }
        List<String> notes = new ArrayList<>();
        if (bySource.containsKey(NaruSpawnSource.DEFAULT)) {
            notes.add("default");
        }
        if (bySource.containsKey(NaruSpawnSource.POLICY)) {
            notes.add("policy");
        }
        if (bySource.containsKey(NaruSpawnSource.FLAG)) {
            notes.add("flag");
        }
        if (bySource.containsKey(NaruSpawnSource.CONTRACT)) {
            notes.add("contract");
        }
        if (revoked != null && !revoked.isEmpty() && name.equals("tags")) {
            List<String> rn = new ArrayList<>();
            for (NaruSpawnSeed<String> r : revoked) {
                rn.add(r.value());
            }
            notes.add("inherit \u2212 revoke " + String.join(",", rn));
        }
        if (!notes.isEmpty()) {
            sb.append(" (").append(String.join("; ", notes)).append(')');
        }
        return sb.toString();
    }
}