package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token accounting for a single session.
 * <p>
 * Deliberately not persisted: spend is a fact about the run that incurred it, and restoring
 * yesterday's totals into today's report would misstate current cost. A reload starts from
 * zero, which is also what makes the number trustworthy.
 */
class NaruMeteringServiceImpl implements NaruMeteringService {

    private final NaruSession session;
    private final Map<ModelAndUser, NaruModelStatsAccumulator> statsByAndUser = new ConcurrentHashMap<>();
    /** Keyed by sessionId/provider, but this instance only ever sees one session. */
    private final Map<String, NaruProviderRateLimitInfo> latestInfoByProvider = new ConcurrentHashMap<>();

    NaruMeteringServiceImpl(NaruSession session) {
        this.session = session;
    }

    private static class ModelAndUser {
        final NaruModelKey model;
        final String user;

        ModelAndUser(NaruModelKey model, String user) {
            this.model = model;
            this.user = user;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ModelAndUser that = (ModelAndUser) o;
            return Objects.equals(model, that.model) && Objects.equals(user, that.user);
        }

        @Override
        public int hashCode() {
            return Objects.hash(model, user);
        }
    }

    @Override
    public void trackTransaction(NaruTokenTransaction t) {
        NaruModelStatsAccumulator a = statsFor(t.getModel().key(), t.getUserId());
        accumulate(t, a);
        if (!NBlankable.isBlank(t.getUserId())) {
            // a per-user call also counts towards the model as a whole
            accumulate(t, statsFor(t.getModel().key(), null));
        }
    }

    private NaruModelStatsAccumulator statsFor(NaruModelKey m, String userId) {
        ModelAndUser k = new ModelAndUser(m, NStringUtils.stripToNull(userId));
        NaruModelStatsAccumulator o = statsByAndUser.get(k);
        if (o == null) {
            o = fillDefaults(new NaruModelStatsAccumulator().setModel(m).setUserId(userId));
            NaruModelStatsAccumulator prev = statsByAndUser.putIfAbsent(k, o);
            if (prev != null) {
                o = prev;
            }
        }
        return o;
    }

    /**
     * Seeds the context size, which the accumulator cannot know on its own. A per-user row
     * borrows the model-wide row's value; the model-wide row asks the protocol.
     */
    private NaruModelStatsAccumulator fillDefaults(NaruModelStatsAccumulator a) {
        if (a.getContextSize() > 0) {
            return a;
        }
        if (a.getUserId() != null) {
            NaruModelStatsAccumulator overall = statsByAndUser.get(new ModelAndUser(a.getModel(), null));
            if (overall != null && overall.getContextSize() > 0) {
                a.setContextSize(overall.getContextSize());
            }
            return a;
        }
        NaruModelProtocol p = session.registry().protocol(new NaruModelConfig(a.getModel()), session).orNull();
        if (p != null) {
            a.setContextSize(p.getCapabilities().contextLength());
        }
        return a;
    }

    private void accumulate(NaruTokenTransaction part, NaruModelStatsAccumulator into) {
        // These are running totals across every call made against this
        // model/user pair, so they must add. Assigning here would silently
        // report only the most recent call's prompt and completion sizes,
        // making a busy session look like a single-request one and understating
        // spend for budget purposes.
        into.setPromptTokens(into.getPromptTokens() + part.getPromptTokens());
        into.setCompletionTokens(into.getCompletionTokens() + part.getCompletionTokens());

        // A provider that does not report cache accounting uses -1. Treat that
        // as zero so it cannot drag a running total backwards.
        if (part.getCacheWriteTokens() > 0) {
            into.setCacheWriteTokens(into.getCacheWriteTokens() + part.getCacheWriteTokens());
        }
        if (part.getCacheReadTokens() > 0) {
            into.setCacheReadTokens(into.getCacheReadTokens() + part.getCacheReadTokens());
        }

        into.setContextUsage(into.getCompletionTokens() + part.getPromptTokens());
        into.setTotalTokens(into.getTotalTokens() + into.getContextUsage());
        long old = into.getPeakContextUsage();
        into.setPeakContextUsage(Math.max(old, into.getContextUsage()));
        into.setCalls(into.getCalls() + 1);
        into.setAccumulatedDuration(into.getAccumulatedDuration() + part.getDuration().toMillis());
        if (into.getMinDuration() == 0) {
            into.setMinDuration(part.getDuration().toMillis());
        } else {
            into.setMinDuration(Math.min(into.getMinDuration(), part.getDuration().toMillis()));
        }
        into.setMaxDuration(Math.max(into.getMaxDuration(), part.getDuration().toMillis()));
    }

    @Override
    public NaruModelStats findModelStats(NaruModelKey model, String user) {
        NaruModelStatsAccumulator m = statsFor(model, user);
        BigDecimal ub = m.getUnitBudget();
        if (ub == null) {
            ub = BigDecimal.ZERO;
        }
        BigDecimal all = ub.multiply(BigDecimal.valueOf(m.getTotalTokens()));
        long accumulatedDuration = m.getAccumulatedDuration();
        long calls = m.getCalls();
        return new NaruModelStats(
                m.getModel(),
                m.getUserId(),
                m.getPromptTokens(),
                m.getCompletionTokens(),
                m.getContextUsage(),
                m.getPeakContextUsage(),
                m.getContextSize(),
                m.getTotalTokens(),
                m.getCacheWriteTokens(),
                m.getCacheReadTokens(),
                ub,
                all,
                calls,
                calls == 0 ? NDuration.ZERO : NDuration.ofMillis(m.getMinDuration()).normalize(),
                calls == 0 ? NDuration.ZERO : NDuration.ofMillis(accumulatedDuration / calls).normalize(),
                calls == 0 ? NDuration.ZERO : NDuration.ofMillis(m.getMaxDuration()).normalize()
        );
    }

    @Override
    public List<NaruModelStats> findModelStats() {
        List<NaruModelStats> all = new ArrayList<>();
        for (Map.Entry<ModelAndUser, NaruModelStatsAccumulator> e : statsByAndUser.entrySet()) {
            // only the model-wide rows; a per-user row would double-count
            if (e.getValue().getUserId() == null) {
                all.add(findModelStats(e.getValue().getModel(), null));
            }
        }
        return all;
    }

    @Override
    public BigDecimal getUnitPrice(NaruModelKey model) {
        BigDecimal ub = statsFor(model, null).getUnitBudget();
        return ub == null ? BigDecimal.ZERO : ub;
    }

    @Override
    public void setUnitPrice(NaruModelKey model, BigDecimal value) {
        statsFor(model, null).setUnitBudget(value == null ? BigDecimal.ZERO : value);
    }

    @Override
    public void trackProviderStats(NaruProviderRateLimitInfo stats) {
        if (stats != null) {
            latestInfoByProvider.put(stats.sessionId() + "/" + stats.providerName(), stats);
        }
    }

    @Override
    public List<NaruProviderRateLimitInfo> findProviderRateLimitInfos() {
        return latestInfoByProvider.values().stream()
                .filter(x -> x.sessionId().equals(session.uuid()))
                .map(x -> x)
                .toList();
    }
}
