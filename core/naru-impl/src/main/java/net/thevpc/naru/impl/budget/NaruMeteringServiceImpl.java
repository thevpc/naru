package net.thevpc.naru.impl.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.budget.NaruModelStats;
import net.thevpc.naru.api.budget.NaruTokenTransaction;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NaruMeteringServiceImpl implements NaruMeteringService {
    Map<NaruModelKeyAndUser, NaruModelStatsAccumulator> statsByAndUser = new ConcurrentHashMap<>();

    private static class NaruModelKeyAndUser {
        NaruModelKey model;
        String user;

        public NaruModelKeyAndUser(NaruModelKey model, String user) {
            this.model = model;
            this.user = user;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            NaruModelKeyAndUser that = (NaruModelKeyAndUser) o;
            return Objects.equals(model, that.model) && Objects.equals(user, that.user);
        }

        @Override
        public int hashCode() {
            return Objects.hash(model, user);
        }
    }

    @Override
    public void trackTransaction(NaruTokenTransaction t, NaruSession session) {
        NaruModelStatsAccumulator a = statsFor(t.getModel().key(), t.getUserId(), session);
        accumulate(t, a);
        if (!NBlankable.isBlank(t.getUserId())) {
            NaruModelStatsAccumulator b = statsFor(t.getModel().key(), null, session);
            accumulate(t, b);
        }
    }

    private NaruModelStatsAccumulator statsFor(NaruModelKey m, String userId, NaruSession session) {
        NaruModelKeyAndUser k = new NaruModelKeyAndUser(m, NStringUtils.trimToNull(userId));
        NaruModelStatsAccumulator o = statsByAndUser.get(k);
        if (o == null) {
            statsByAndUser.put(k, o = fillDefaults(new NaruModelStatsAccumulator().setModel(m).setUserId(userId), session));
        }
        return o;
    }


    private NaruModelStatsAccumulator fillDefaults(NaruModelStatsAccumulator a, NaruSession session) {
        if (a.getContextSize() <= 0) {
            if (a.getUserId() != null) {
                a.setContextSize(statsByAndUser.remove(new NaruModelKeyAndUser(a.getModel(), null)).getContextSize());
            } else {
                NaruModelProtocol p = session.registry().protocol(new NaruModelConfig(a.getModel()), session).orNull();
                if (p != null) {
                    a.setContextSize(p.getCapabilities().contextLength());
                }
            }
        }
        return a;
    }

    private void accumulate(NaruTokenTransaction part, NaruModelStatsAccumulator into) {
        into.setPromptTokens(part.getPromptTokens());
        into.setCompletionTokens(part.getCompletionTokens());
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
    public NaruModelStats findModelStats(NaruModelKey model, String user, NaruSession session) {
        NaruModelStatsAccumulator m = statsFor(model, user, session);
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
                ub,
                all,
                calls,
                calls == 0 ? NDuration.ZERO : NDuration.ofMillis(m.getMinDuration()).normalize(),
                calls == 0 ? NDuration.ZERO : NDuration.ofMillis(accumulatedDuration / calls).normalize(),
                calls == 0 ? NDuration.ZERO : NDuration.ofMillis(m.getMaxDuration()).normalize()
        );
    }

    @Override
    public List<NaruModelStats> findModelStats(NaruSession session) {
        List<NaruModelStats> all = new ArrayList<>();
        for (Map.Entry<NaruModelKeyAndUser, NaruModelStatsAccumulator> e : statsByAndUser.entrySet()) {
            if (e.getValue().getUserId() == null) {
                all.add(findModelStats(e.getValue().getModel(), null, session));
            }
        }
        return all;
    }

    @Override
    public BigDecimal getUnitPrice(NaruModelKey model, NaruSession session) {
        NaruModelStatsAccumulator m = statsFor(model, null, session);
        BigDecimal ub = m.getUnitBudget();
        if (ub == null) {
            ub = BigDecimal.ZERO;
        }
        return ub;
    }

    @Override
    public void setUnitPrice(NaruModelKey model, BigDecimal value, NaruSession session) {
        NaruModelStatsAccumulator m = statsFor(model, null, session);
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        m.setUnitBudget(value);
    }
}
