package net.thevpc.naru.api.agent;

public enum NaruRole {
    assistant, user, system, tool,
    /**
     * A compaction summary standing in for conversation items this one covers.
     *
     * <p>This is a persisted role, not a transient one: a summary item is written into the
     * task's history and must survive a reload, including in a session opened by a build
     * that does not have the compaction extension on the classpath. That is why the
     * summary's metadata lives in {@code naru-api} rather than in the extension, and why
     * the core can render such an item to the model without knowing what produced it.
     *
     * <p>Adding a constant here does not affect reading sessions written before it
     * existed: those files only ever contain the four original roles.
     */
    summary;

    public String id() {
        return name().toLowerCase();
    }
}