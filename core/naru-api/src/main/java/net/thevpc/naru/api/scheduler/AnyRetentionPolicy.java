package net.thevpc.naru.api.scheduler;

import java.util.Arrays;
import java.util.List;

/**
 * Composite — drop when ANY policy says so.
 */
public class AnyRetentionPolicy implements NaruRetentionPolicy {
    private final List<NaruRetentionPolicy> policies;

    public AnyRetentionPolicy(NaruRetentionPolicy... policies) {
        this.policies = Arrays.asList(policies);
    }

    public boolean shouldDrop(NaruEvent event) {
        return policies.stream().anyMatch(p -> p.shouldDrop(event));
    }

    public long nextCheckMillis(NaruEvent event) {
        return policies.stream()
                .mapToLong(p -> p.nextCheckMillis(event))
                .min()
                .orElse(0);
    }
}
