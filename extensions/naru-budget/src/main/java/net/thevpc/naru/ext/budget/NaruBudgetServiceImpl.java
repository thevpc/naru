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
 * Token accounting for a single session's view of the shared, project-level store.
 * <p>
 * The store and the price table are owned by the agent ({@link NaruBudgetAgentExtension})
 * and shared by every session; this service is the per-session facade the directive and
 * the usage listener talk to. When no agent extension is available (unit tests, a bare
 * session without SPI discovery), it falls back to a private in-memory store and price
 * table so accounting still works, just without persistence or sharing.
 */
class NaruBudgetServiceImpl implements NaruBudgetService {

    private final NaruSession session;
    private final NaruBudgetStore store;
    private final NaruBudgetPriceTable priceTable;
    /**
     * Keyed by sessionId/provider, but this instance only ever sees one session.
     */
    private final Map<String, NaruProviderRateLimitInfo> latestInfoByProvider = new ConcurrentHashMap<>();

    NaruBudgetServiceImpl(NaruSession session, NaruBudgetAgentExtension agentExtension) {
        this.session = session;
        if (agentExtension != null) {
            this.store = agentExtension.store();
            this.priceTable = agentExtension.priceTable();
        } else {
            this.store = new InMemoryNaruBudgetStore();
            this.priceTable = new NaruBudgetPriceTable();
        }
    }

    @Override
    public void trackTransaction(NaruTokenTransaction t) {
        if (t.getPrice() == null) {
            // Snapshot the price the call was made under. From here on the cost of
            // this call is fixed, whatever `price set` does later.
            NaruUnitPrice price = priceFor(t.getLabels());
            if (price != null) {
                t = t.withPrice(price);
            }
        }
        store.saveTransaction(t);
    }

    @Override
    public NaruUnitPrice priceFor(Labels labels) {
        return priceTable.priceFor(labels);
    }

    @Override
    public NaruBudgetPriceTable priceTable() {
        return priceTable;
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
                        .whereLabels(labels2)
        );
    }

    @Override
    public NaruModelBudgetStats findModelBudgetStats(NaruBudgetStoreQuery query) {
        if (query == null) {
            query = new DefaultNaruBudgetStoreQuery();
        }
        return store.aggregate(query);
    }

    @Override
    public List<NaruModelBudgetStats> findByModelBudgetStats(NaruBudgetStoreQuery q) {
        if(q==null){
            q=new DefaultNaruBudgetStoreQuery();
        }
        List<NaruModelBudgetStats> all = new ArrayList<>();
        // Discover the tracked models from the store itself rather than resolving each
        // through the session: a model the budget has seen is not necessarily one the
        // model registry still knows, and skipping those silently dropped spend from
        // the listing.
        for (String e : store.findLabelValues("model", q)) {
            all.add(findModelBudgetStats(q.copy().whereLabel("model", e)));
        }
        return all;
    }

    @Override
    public List<NaruTokenTransaction> findTransactions(NaruBudgetStoreQuery query) {
        if (query == null) {
            query = new DefaultNaruBudgetStoreQuery();
        }
        return store.findTransactions(query);
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
