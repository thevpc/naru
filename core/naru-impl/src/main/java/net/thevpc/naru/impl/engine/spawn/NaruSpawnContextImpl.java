package net.thevpc.naru.impl.engine.spawn;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnContext;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.spawn.NaruSpawnSeed;
import net.thevpc.naru.api.spawn.NaruSpawnSource;
import net.thevpc.naru.api.spawn.NaruSpawnStrategy;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable {@link NaruSpawnContext} built by {@code NaruSessionImpl} for every spawn,
 * handed to {@code NaruSessionExtension.onSpawn} and (once resolved) to
 * {@code NaruSessionExtension.onSpawned}.
 */
public class NaruSpawnContextImpl implements NaruSpawnContext {

    private final NaruSession session;
    private final NaruTask parent;
    private final NaruTaskSpec spec;
    private final String spawnKind;
    private NaruSpawnStrategy strategy;
    private int windowTurns;
    private NaruSpawnPolicy policy;
    private NaruSpawnContract contract;

    private final Map<String, NaruSpawnSeed<String>> addTagSeeds = new LinkedHashMap<>();
    private final Map<String, NaruSpawnSeed<String>> revokeTagSeeds = new LinkedHashMap<>();
    private final Map<String, NaruSpawnSeed<String>> exclusionSeeds = new LinkedHashMap<>();
    private final Map<String, NaruSpawnSeed<String>> skillSeeds = new LinkedHashMap<>();
    private final Map<String, NaruSpawnSeed<Object>> envSeeds = new LinkedHashMap<>();
    private final Map<NaruSpawnInherit, NaruSpawnSeed<NaruSpawnInherit>> inheritSeeds = new LinkedHashMap<>();
    private NaruSpawnSeed<NaruSpawnStrategy> strategySeed;

    private NaruSpawnResolution resolution = emptyResolution();
    private final List<String> warnings = new ArrayList<>();

    public NaruSpawnContextImpl(NaruSession session, NaruTask parent, NaruTaskSpec spec,
                                String spawnKind, NaruSpawnStrategy strategy, int windowTurns,
                                NaruSpawnPolicy policy, NaruSpawnContract contract) {
        this.session = session;
        this.parent = parent;
        this.spec = spec;
        this.spawnKind = spawnKind;
        this.strategy = strategy == null ? NaruSpawnStrategy.NONE : strategy;
        this.windowTurns = Math.max(0, windowTurns);
        this.policy = policy;
        this.contract = contract;
    }

    @Override
    public NaruSession session() {
        return session;
    }

    @Override
    public NaruTask parent() {
        return parent;
    }

    @Override
    public NaruTaskSpec spec() {
        return spec;
    }

    @Override
    public String spawnKind() {
        return spawnKind;
    }

    @Override
    public NaruSpawnStrategy strategy() {
        return strategy;
    }

    @Override
    public int windowTurns() {
        return windowTurns;
    }

    @Override
    public NaruSpawnPolicy policy() {
        return policy;
    }

    @Override
    public NaruSpawnContract contract() {
        return contract;
    }

    // ── seeding ────────────────────────────────────────────────────────────

    @Override
    public NaruSpawnContext seedTag(String tag, NaruSpawnSource source) {
        addTagSeeds.merge(normalizeTag(tag), NaruSpawnSeed.of(tag, source == null ? NaruSpawnSource.DEFAULT : source),
                (a, b) -> rank(b.source()) >= rank(a.source()) ? b : a);
        return this;
    }

    @Override
    public NaruSpawnContext seedRevokeTag(String tag, NaruSpawnSource source) {
        revokeTagSeeds.merge(normalizeTag(tag), NaruSpawnSeed.of(tag, source == null ? NaruSpawnSource.DEFAULT : source),
                (a, b) -> rank(b.source()) >= rank(a.source()) ? b : a);
        return this;
    }

    @Override
    public NaruSpawnContext seedExclusion(String tool, NaruSpawnSource source) {
        String n = normalize(tool);
        exclusionSeeds.merge(n, NaruSpawnSeed.of(n, source == null ? NaruSpawnSource.DEFAULT : source),
                (a, b) -> rank(b.source()) >= rank(a.source()) ? b : a);
        return this;
    }

    @Override
    public NaruSpawnContext seedSkill(String skill, NaruSpawnSource source) {
        String n = normalize(skill);
        skillSeeds.merge(n, NaruSpawnSeed.of(n, source == null ? NaruSpawnSource.DEFAULT : source),
                (a, b) -> rank(b.source()) >= rank(a.source()) ? b : a);
        return this;
    }

    @Override
    public NaruSpawnContext seedEnv(String key, Object value, NaruSpawnSource source) {
        if (key == null || key.isBlank()) {
            return this;
        }
        envSeeds.merge(key, NaruSpawnSeed.of(value, source == null ? NaruSpawnSource.DEFAULT : source),
                (a, b) -> rank(b.source()) >= rank(a.source()) ? b : a);
        return this;
    }

    @Override
    public NaruSpawnContext seedInherit(NaruSpawnInherit kind, NaruSpawnSource source) {
        if (kind != null) {
            inheritSeeds.merge(kind, NaruSpawnSeed.of(kind, source == null ? NaruSpawnSource.DEFAULT : source),
                    (a, b) -> rank(b.source()) >= rank(a.source()) ? b : a);
        }
        return this;
    }

    @Override
    public NaruSpawnContext seedStrategy(NaruSpawnStrategy strategy, int windowTurns, NaruSpawnSource source) {
        NaruSpawnSource s = source == null ? NaruSpawnSource.DEFAULT : source;
        if (strategySeed == null || rank(s) >= rank(strategySeed.source())) {
            strategySeed = NaruSpawnSeed.of(strategy == null ? NaruSpawnStrategy.NONE : strategy, s);
            this.windowTurns = Math.max(0, windowTurns);
        }
        return this;
    }

    // ── resolution ─────────────────────────────────────────────────────────

    @Override
    public NaruSpawnResolution resolution() {
        return resolution;
    }

    @Override
    public List<String> resolvedTagNames() {
        List<String> out = new ArrayList<>();
        for (NaruSpawnSeed<String> t : resolution.tags()) {
            out.add(t.value());
        }
        return out;
    }

    void setResolution(NaruSpawnResolution resolution) {
        this.resolution = resolution == null ? emptyResolution() : resolution;
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }

    void setStrategy(NaruSpawnStrategy strategy) {
        this.strategy = strategy == null ? NaruSpawnStrategy.NONE : strategy;
    }

    List<String> warnings() {
        return warnings;
    }

    Map<String, NaruSpawnSeed<String>> addTagSeeds() {
        return Collections.unmodifiableMap(addTagSeeds);
    }

    Map<String, NaruSpawnSeed<String>> revokeTagSeeds() {
        return Collections.unmodifiableMap(revokeTagSeeds);
    }

    Map<String, NaruSpawnSeed<String>> exclusionSeeds() {
        return Collections.unmodifiableMap(exclusionSeeds);
    }

    Map<String, NaruSpawnSeed<String>> skillSeeds() {
        return Collections.unmodifiableMap(skillSeeds);
    }

    Map<String, NaruSpawnSeed<Object>> envSeeds() {
        return Collections.unmodifiableMap(envSeeds);
    }

    Map<NaruSpawnInherit, NaruSpawnSeed<NaruSpawnInherit>> inheritSeeds() {
        return Collections.unmodifiableMap(inheritSeeds);
    }

    NaruSpawnSeed<NaruSpawnStrategy> strategySeed() {
        return strategySeed;
    }

    private static String normalizeTag(String tag) {
        String n = normalize(tag);
        return n.isEmpty() ? tag : n;
    }

    private static String normalize(String s) {
        return s == null ? "" : NNameFormat.LOWER_KEBAB_CASE.format(s.trim());
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

    private static NaruSpawnResolution emptyResolution() {
        return new NaruSpawnResolution(
                NaruSpawnStrategy.NONE, 0, NaruSpawnSource.DEFAULT,
                Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                null, null, List.of());
    }
}