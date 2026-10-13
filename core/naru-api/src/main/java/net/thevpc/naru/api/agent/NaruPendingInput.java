package net.thevpc.naru.api.agent;

/** Public description of one pending task input. */
public record NaruPendingInput(long taskId, String requestId, String prompt) {
}
