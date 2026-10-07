package net.thevpc.naru.ext.models.openai;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;

import java.util.List;

/**
 * OpenAI provider — the built-in {@code openai} type pointing at OpenAI's own
 * API ({@code https://api.openai.com/v1}) with the {@code OPENAI_API_KEY} key
 * lookup.
 *
 * <p>What OpenAI itself speaks is the {@code openai} wire, so this provider's
 * default protocol is already its own wire shape: {@code --protocol=openai}
 * names the default and is a no-op. Like every single-wire type, it does not
 * declare other shapes — {@code --provider=openai --protocol=anthropic} is a
 * hard error at the {@code /model add} level, never a silent fallback. The one
 * override that matters is the endpoint: {@code --url=...} re-points the type
 * at a proxy (e.g. {@code --provider=openai --url=http://myproxy:4000/v1})
 * for exactly the same scheme, key and listing.
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://api.openai.com/v1
 */
public class NaruOpenAiProvider extends AbstractOpenAICompatProvider {
    // Fallback only — the live GET /models listing (cached per type|protocol|url|key)
    // is authoritative whenever an OPENAI_API_KEY resolves; this list only shows
    // when a key is set but the endpoint cannot be reached.
    private static final List<String> FALLBACK_MODELS = List.of(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "o3-mini",
            "o4-mini",
            "gpt-5",
            "gpt-5-mini",
            "gpt-5-nano"
    );

    public NaruOpenAiProvider() {
        super("openai", new String[]{"OPENAI_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.openai.com/v1";
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = modelName.contains("gpt-4") || modelName.contains("gpt-5");
        boolean tools = true;
        boolean thinking = modelName.contains("o1") || modelName.contains("o3")
                || modelName.contains("o4") || modelName.contains("gpt-5");
        boolean embedding = false;
        long contextLength = 131072L; // 128K standard for the current GPT/O lines
        if (modelName.contains("gpt-4.1")) {
            contextLength = 1048576L; // the 4.1 line has 1M context
        }
        if (modelName.contains("o3") || modelName.contains("o4")) {
            contextLength = 262144L; // 200K for the O3/O4 reasoning lines
        }
        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength,
                NaruCachingMode.AUTOMATIC_PREFIX);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}