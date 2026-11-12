package net.thevpc.naru.ext.assess;

import java.util.List;

/** A unit of work handed to a model, with the criteria that score the outcome. */
public record AssessTask(String id, AssessTier tier, String title, String prompt, List<AssessCheck> checks) {
    public int totalPoints() {
        return checks.stream().mapToInt(AssessCheck::points).sum();
    }
}
