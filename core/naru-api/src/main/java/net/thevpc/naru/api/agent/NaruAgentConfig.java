package net.thevpc.naru.api.agent;

/**
 * Configuration for a single agent run.
 */
public class NaruAgentConfig {

    /** Model name passed to the provider (e.g. "qwen2.5-coder:7b"). */
    private String model = "qwen3-coder:30b";

    /** Vision model used by the inspect_image tool. */
    private String visionModel = "qwen2.5vl:latest";

    /** Provider name: "ollama" (others to be added). */
    private String provider = "ollama";

    /** Ollama (or other provider) base URL. */
    private String providerUrl = "http://localhost:11434";

    /** Max agent loop iterations before giving up. */
    private int maxSteps = 20;

    /** Whether to print verbose step-by-step output. */
    private boolean verbose = true;

    public NaruAgentConfig() {}

    // ── Getters / setters ─────────────────────────────────────────────────────

    public String getModel() { return model; }
    public NaruAgentConfig model(String model) { this.model = model; return this; }

    public String getVisionModel() { return visionModel; }
    public NaruAgentConfig visionModel(String visionModel) { this.visionModel = visionModel; return this; }

    public String getProvider() { return provider; }
    public NaruAgentConfig provider(String provider) { this.provider = provider; return this; }

    public String getProviderUrl() { return providerUrl; }
    public NaruAgentConfig providerUrl(String providerUrl) { this.providerUrl = providerUrl; return this; }

    public int getMaxSteps() { return maxSteps; }
    public NaruAgentConfig maxSteps(int maxSteps) { this.maxSteps = maxSteps; return this; }

    public boolean isVerbose() { return verbose; }
    public NaruAgentConfig verbose(boolean verbose) { this.verbose = verbose; return this; }
}
