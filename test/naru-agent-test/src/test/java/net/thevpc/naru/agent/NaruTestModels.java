package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelFilter;

/** Shared test defaults that keep the suite off paid model providers. */
public final class NaruTestModels {
    private NaruTestModels() {}

    /** True when a test has explicitly opted into remote/paid providers. */
    public static boolean allowRemoteModels() {
        return Boolean.getBoolean("naru.test.allowRemoteModels");
    }

    /**
     * The model filter every test session should use: only local/ollama-style providers,
     * unless -Dnaru.test.allowRemoteModels=true opts into the real provider catalog.
     */
    public static NaruModelFilter defaultFilter() {
        if (allowRemoteModels()) {
            return NaruModelFilter.allowAll();
        }
        // Allow ollama and ollama-style local endpoints (mock-ollama, bench-ollama).
        return NaruModelFilter.of(k -> k.provider() != null
                && k.provider().toLowerCase().contains("ollama"));
    }

    /** Convenience: apply the default filter to a workspace before building sessions. */
    public static NaruWorkspace filtered(NaruWorkspace ws) {
        return ws.modelFilter(defaultFilter());
    }
}
