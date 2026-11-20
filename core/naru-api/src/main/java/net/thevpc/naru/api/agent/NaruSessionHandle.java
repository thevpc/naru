package net.thevpc.naru.api.agent;

/** Lazy, backend-facing access to a persisted conversation. */
public interface NaruSessionHandle extends AutoCloseable {
    String id();
    NaruTurn submit(NaruInput input);
    @Override default void close() { }
}
