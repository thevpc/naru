package net.thevpc.naru.api.scheduler;

import java.util.Arrays;
import java.util.List;

/**
 * Composite — drop when ALL policies agree.
 */
public class AllRetentionPolicy implements NaruRetentionPolicy {
    private final List<NaruRetentionPolicy> policies;

    public AllRetentionPolicy(NaruRetentionPolicy... policies) {
        this.policies = Arrays.asList(policies);
    }

    public boolean shouldDrop(NaruEvent event) {
        return policies.stream().allMatch(p -> p.shouldDrop(event));
    }

    public long nextCheckMillis(NaruEvent event) {
        return policies.stream()
                .mapToLong(p -> p.nextCheckMillis(event))
                .min()
                .orElse(0);
    }
}
