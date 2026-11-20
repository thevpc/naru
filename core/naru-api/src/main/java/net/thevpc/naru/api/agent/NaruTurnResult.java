package net.thevpc.naru.api.agent;

import java.util.List;

/** Outcome of one submit-to-quiescence cycle. */
public record NaruTurnResult(NaruTurnStatus status, List<NaruPendingInput> pendingInputs,
                             boolean lagged, Throwable failure) {
    public NaruTurnResult {
        pendingInputs = pendingInputs == null ? List.of() : List.copyOf(pendingInputs);
    }
    public static NaruTurnResult hibernated(List<NaruPendingInput> pending, boolean lagged) {
        return new NaruTurnResult(NaruTurnStatus.HIBERNATED, pending, lagged, null);
    }
    public static NaruTurnResult completed(boolean lagged) {
        return new NaruTurnResult(NaruTurnStatus.COMPLETED, List.of(), lagged, null);
    }
    public static NaruTurnResult failed(Throwable error, boolean lagged) {
        return new NaruTurnResult(NaruTurnStatus.FAILED, List.of(), lagged, error);
    }
}
