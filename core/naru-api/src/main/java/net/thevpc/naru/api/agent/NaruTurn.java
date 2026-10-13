package net.thevpc.naru.api.agent;

import java.util.concurrent.CompletableFuture;

/** A submitted turn and its asynchronous outcome. */
public interface NaruTurn {
    CompletableFuture<NaruTurnResult> result();
    void disconnect();
}
