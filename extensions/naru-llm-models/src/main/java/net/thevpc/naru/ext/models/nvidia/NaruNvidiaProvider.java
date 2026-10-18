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
        boolean thinking = modelName.contains("thinking") || modelName.contains("qwq")
                || modelName.contains("deepseek-r1");
        long contextLength = modelName.contains("qwen3-next") ? 262144L : 131072L;
        return new NaruModelCapabilitiesImpl(false, true, thinking, false, contextLength,
                NaruCachingMode.AUTOMATIC_PREFIX);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
