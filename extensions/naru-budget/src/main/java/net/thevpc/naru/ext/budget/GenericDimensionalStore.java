package net.thevpc.naru.ext.budget;

import java.math.BigDecimal;

/**
 * Storage-agnostic API for multi-dimensional budget aggregation.
 */
public interface DimensionalBudgetStore {

    /**
     * Records a budget value against a specific set of dimensions.
     * Implementations should accumulate this value for the exact dimension set.
     *
     * @param labels the dimensions of the transaction (e.g., app, module, user)
     * @param value  the value to add
     * @throws IllegalArgumentException if labels or value are null
     */
    void record(Labels labels, BigDecimal value);

    /**
     * Aggregates (sums) all recorded values that match the provided query labels.
     * A match occurs when the recorded labels contain ALL key-value pairs present
     * in the query labels (i.e., the query is a subset of the recorded labels).
     *
     * @param queryLabels the dimensions to filter by. Empty labels return the grand total.
     * @return the aggregated sum, or BigDecimal.ZERO if no matches are found.
     */
    BigDecimal aggregate(Labels queryLabels);

    /**
     * Flushes any pending writes to the underlying persistent store.
     * No-op for pure in-memory implementations.
     */
    default void flush() {
        // No-op by default
    }
}
