package net.thevpc.naru.ext.budget;

import java.time.Instant;

public record NaruBudgetDecision(boolean allowed, NaruBudgetReservation reservation,
                          String blockedBy, Instant retryAfter) {
}
