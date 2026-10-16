package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.ext.budget.Labels;
import net.thevpc.nuts.util.NCopiable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface NaruBudgetStoreQuery extends NCopiable {
    Labels labels();

    Instant from();

    Instant to();

    NaruBudgetStoreQuery whereModel(NaruModelKey model);

    NaruBudgetStoreQuery whereUser(String user);

    NaruBudgetStoreQuery whereLabels(Labels labels);

    NaruBudgetStoreQuery whereLabel(String key, String value);

    /**
     * Matches transactions whose label {@code key} <em>starts with</em> {@code prefix}.
     * This is what rolls up a task subtree: {@code task-path} is
     * {@code <mainId>/<childId>/...}, so {@code task-path} starting with the parent's own
     * path selects it and every task it spawned.
     */
    NaruBudgetStoreQuery whereLabelStartsWith(String key, String prefix);

    /** The prefix constraints, keyed by label key. Empty when none. */
    Map<String, String> labelPrefixes();

    NaruBudgetStoreQuery wherePeriod(Instant instant, Instant end);

    NaruBudgetStoreQuery copy();

}
