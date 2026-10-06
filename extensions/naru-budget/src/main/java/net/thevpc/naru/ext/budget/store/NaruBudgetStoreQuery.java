package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.ext.budget.Labels;
import java.time.Instant;

public interface NaruBudgetStoreQuery {
    Labels labels();

    Instant from();

    Instant to();

    NaruBudgetStoreQuery whereModel(NaruModelKey model);

    NaruBudgetStoreQuery whereUser(String user);

    NaruBudgetStoreQuery whereLabels(Labels labels);

    NaruBudgetStoreQuery whereLabel(String key, String value);

    NaruBudgetStoreQuery wherePeriod(Instant instant, Instant end);

    NaruBudgetStoreQuery copy();

}
