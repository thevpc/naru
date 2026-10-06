package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.ext.budget.store.DefaultNaruBudgetStoreQuery;
import net.thevpc.naru.ext.budget.store.InMemoryNaruBudgetStore;
import net.thevpc.naru.ext.budget.store.NaruBudgetStore;
import net.thevpc.naru.ext.budget.store.NaruBudgetStoreQuery;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token accounting for a single session.
 * <p>
 * Deliberately not persisted: spend is a fact about the run that incurred it, and restoring
 * yesterday's totals into today's report would misstate current cost. A reload starts from
 * zero, which is also what makes the number trustworthy.
 */
class NaruBudgetServiceImpl implements NaruBudgetService, NAruUnitBudgetSupplier {

    private final NaruSession session;
    private final NaruBudgetStore store = new InMemoryNaruBudgetStore();
    /**
     * Keyed by sessionId/provider, but this instance only ever sees one session.
     */
    private final Map<String, NaruProviderRateLimitInfo> latestInfoByProvider = new ConcurrentHashMap<>();

    NaruBudgetServiceImpl(NaruSession session) {
        this.session = session;
    }

    @Override
    public void trackTransaction(NaruTokenTransaction t) {
        store.saveTransaction(t);
    }


    @Override
    public NaruModelBudgetStats findModelBudgetStats(NaruModelKey model, String user, Labels labels) {
        HashMap<String, String> map = new HashMap<>();
        if (labels != null) {
            map.putAll(labels.asMap());
        }
        if (model != null) {
            map.put("model", model.model());
            map.put("provider", model.provider());
        }
        if (!NBlankable.isBlank(user)) {
            map.put("user", user);
        }
        Labels labels2 = Labels.of(map);
        return store.aggregate(new DefaultNaruBudgetStoreQuery()
                        .whereLabels(labels2),
                this
        );
    }

    @Override
    public NaruModelBudgetStats findModelBudgetStats(NaruBudgetStoreQuery query) {
        if (query == null) {
            query = new DefaultNaruBudgetStoreQuery();
        }
        return store.aggregate(query,
                this
        );
    }

    @Override
    public List<NaruModelBudgetStats> findByModelBudgetStats(NaruBudgetStoreQuery q) {
        if(q==null){
            q=new DefaultNaruBudgetStoreQuery();
        }
        List<NaruModelBudgetStats> all = new ArrayList<>();
        for (String e : store.findLabelValues("model", null)) {
            NaruModelConfig a = session.findModel(e).orNull();
            if (a != null) {
                all.add(findModelBudgetStats(q.copy().whereModel(a.key())));
            }
        }
        return all;
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
