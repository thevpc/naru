package net.thevpc.naru.ext.models.nvidia;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openai.AbstractOpenAICompatProvider;

import java.util.List;

/** NVIDIA NIM hosted inference through its OpenAI-compatible chat API. */
public class NaruNvidiaProvider extends AbstractOpenAICompatProvider {
    private static final List<String> FALLBACK_MODELS = List.of(
            "qwen/qwen2.5-coder-32b-instruct",
            "qwen/qwen3-next-80b-a3b-instruct",
            "deepseek-ai/deepseek-v4-flash",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
    );

    public NaruNvidiaProvider() {
        super("nvidia", new String[]{"NVIDIA_NIM_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://integrate.api.nvidia.com/v1";
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        // a name match is a guess, and for this provider it is only ever a guess about whether
        // the reasoning channel exists at all: unlike Ollama, an OpenAI-compatible endpoint is
        // asked nothing from this flag, so a wrong answer costs a tool's presence in the schema.
        // nemotron and gpt-oss both stream reasoning_content here, and the scratchpad tool
        // consults the conversation too, so a name missing from this list degrades to a
        // duplicate thought rather than a lost one.
        boolean thinking = modelName.contains("thinking") || modelName.contains("qwq")
                || modelName.contains("deepseek-r1") || modelName.contains("nemotron")
                || modelName.contains("gpt-oss");
        long contextLength = modelName.contains("qwen3-next") ? 262144L : 131072L;
        return new NaruModelCapabilitiesImpl(false, true, thinking, false, contextLength,
                NaruCachingMode.AUTOMATIC_PREFIX);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
