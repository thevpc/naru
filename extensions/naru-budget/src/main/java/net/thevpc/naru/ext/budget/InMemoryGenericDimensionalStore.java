package net.thevpc.naru.ext.budget;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDimensionalBudgetStore implements DimensionalBudgetStore {

    // Stores the aggregated value for a specific, exact combination of labels
    private final ConcurrentHashMap<Labels, BigDecimal> exactTotals = new ConcurrentHashMap<>();

    // Inverted index: Maps a single key-value pair (e.g., "app=a") to the exact Label sets that contain it
    private final ConcurrentHashMap<String, Set<Labels>> invertedIndex = new ConcurrentHashMap<>();

    @Override
    public void record(Labels labels, BigDecimal value) {
        if (labels == null || value == null || value.signum() == 0) return;

        // 1. Update the exact total for this specific combination
        exactTotals.compute(labels, (k, v) -> (v == null) ? value : v.add(value));

        // 2. Update the inverted index for fast querying
        for (Map.Entry<String, String> entry : labels.asMap().entrySet()) {
            String indexKey = entry.getKey() + "=" + entry.getValue();
            invertedIndex.computeIfAbsent(indexKey, k -> ConcurrentHashMap.newKeySet())
                    .add(labels);
        }
    }

    @Override
    public BigDecimal aggregate(Labels queryLabels) {
        if (queryLabels == null || queryLabels.asMap().isEmpty()) {
            return exactTotals.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        // Optimization: Find the smallest posting list to minimize intersection work
        String smallestIndexKey = null;
        int minSize = Integer.MAX_VALUE;

        for (Map.Entry<String, String> entry : queryLabels.asMap().entrySet()) {
            String indexKey = entry.getKey() + "=" + entry.getValue();
            Set<Labels> postingList = invertedIndex.get(indexKey);

            if (postingList == null || postingList.isEmpty()) {
                return BigDecimal.ZERO; // Early exit: this specific key-value pair was never recorded
            }
            if (postingList.size() < minSize) {
                minSize = postingList.size();
                smallestIndexKey = indexKey;
            }
        }

        // Start intersection with the smallest list
        Set<Labels> currentIntersection = new HashSet<>(invertedIndex.get(smallestIndexKey));

        // Intersect with the posting lists of the remaining query keys
        for (Map.Entry<String, String> entry : queryLabels.asMap().entrySet()) {
            String indexKey = entry.getKey() + "=" + entry.getValue();
            if (indexKey.equals(smallestIndexKey)) continue;

            currentIntersection.retainAll(invertedIndex.get(indexKey));
            if (currentIntersection.isEmpty()) {
                return BigDecimal.ZERO; // Early exit: no combinations match all queried keys
            }
        }

        // Sum the values for the intersecting exact combinations
        BigDecimal sum = BigDecimal.ZERO;
        for (Labels exactCombo : currentIntersection) {
            sum = sum.add(exactTotals.get(exactCombo));
        }

        return sum;
    }
}
