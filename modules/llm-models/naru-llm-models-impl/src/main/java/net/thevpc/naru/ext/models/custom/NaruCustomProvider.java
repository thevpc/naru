package net.thevpc.naru.ext.models.custom;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.anthropic.NaruModelProtocolAnthropicCompat;
import net.thevpc.naru.ext.models.gemini.NaruModelProtocolGeminiNative;
import net.thevpc.naru.ext.models.openai.AbstractOpenAICompatProvider;
import net.thevpc.naru.ext.models.openai.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generic endpoint provider: a class-less endpoint (OpenAI-compatible,
 * Anthropic Messages, native Gemini, ...) addressed through a registration
 * instead of code. This is the implementation type behind a {@code /model add}
 * that passes {@code --protocol} with no {@code --provider} (design doc §8).
 *
 * <p>All configuration comes from the instance's own params (design §7), i.e.
 * from the registration, with the agent env {@code <instance id>.<key>} as the
 * §6 fallback:
 *
 * <pre>
 * url=https://my-server          (required: without it there is nothing to call)
 * models=model-a,model-b         (or model=single: the models this endpoint serves)
 * protocol=openai|anthropic|gemini  (wire shape, default openai)
 * apiKey=sk-... | apiKey=$MY_VAR (optional: no key simply means no Authorization)
 * chatPath=v1/chat/completions   (optional)
 * contextLength=32768            (optional)
 * tools=true                     (optional, default true)
 * probe=true                     (optional, default true)
 * cachingMode=AUTOMATIC_PREFIX   (optional, defaults from the wire shape)
 * </pre>
 *
 * <p>Unlike the cloud types, a custom endpoint registration exposes <b>only
 * the models it declares</b> — the endpoint's own listing is never queried,
 * because the user already said what it serves. The endpoint is probed
 * (short-timeout GET with a TTL cache) before its models show up;
 * {@code probe=false} opts out.
 */
public class NaruCustomProvider extends AbstractOpenAICompatProvider {

    public NaruCustomProvider() {
        super("custom", new String[0]);
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return configValue("url", session).orNull();
    }

    @Override
    public Set<String> supportedProtocols() {
        // a generic endpoint can speak any wire shape
        return Set.of(
                NaruModelProtocolOpenAICompat.PROTOCOL_ID,
                NaruModelProtocolAnthropicCompat.PROTOCOL_ID,
                NaruModelProtocolGeminiNative.PROTOCOL_ID
        );
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String model, NaruSession session) {
        long contextLength = configValue("contextLength", session)
                .flatMap(x -> NLiteral.of(x).asLong()).orElse(-1L);
        boolean tools = configValue("tools", session)
                .flatMap(x -> NLiteral.of(x).asBoolean()).orElse(true);
        // tool-call emulation kicks in automatically when tools=false
        return new NaruModelCapabilitiesImpl(false, tools, false, false, contextLength,
                resolveCachingMode(session));
    }

    @Override
    public boolean isAvailable(NaruSession session) {
        String url = baseUrl(session);
        if (NBlankable.isBlank(url)) {
            // no url: the registration declares an endpoint nothing can talk to
            return false;
        }
        boolean probe = configValue("probe", session)
                .flatMap(x -> NLiteral.of(x).asBoolean()).orElse(true);
        return !probe || isReachable(url);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        if (NBlankable.isBlank(baseUrl(session))) {
            return Collections.emptyList();
        }
        return declaredModels(session);
    }

    /**
     * The models this endpoint serves, from its {@code model}/{@code models}
     * params. No declaration means nothing to list: a custom endpoint
     * registration's catalogue is what its owner wrote down, not what the server
     * happens to answer on {@code GET /models}.
     */
    private List<String> declaredModels(NaruSession session) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String key : new String[]{"model", "models"}) {
            String v = configValue(key, session).orNull();
            if (!NBlankable.isBlank(v)) {
                for (String s : v.split("[,\\s]+")) {
                    if (!s.isBlank()) {
                        out.add(s.trim());
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Cache support for a wire endpoint: an explicit {@code cachingMode} wins;
     * otherwise the mode is derived from the wire shape, because the same
     * protocol can be pointed at servers with different behaviour — an
     * OpenAI-shaped endpoint has no cache control at all (the server may still
     * do automatic prefix caching), while the Anthropic wire format has real,
     * client-controlled breakpoints.
     *
     * <p>An unrecognised value is never guessed at: silently falling back to a
     * mode the user did not ask for could send a request shape their server
     * rejects.
     */
    private NaruCachingMode resolveCachingMode(NaruSession session) {
        NOptional<String> explicit = configValue("cachingMode", session);
        if (explicit.isPresent()) {
            String raw = explicit.get();
            for (NaruCachingMode m : NaruCachingMode.values()) {
                if (m.name().equalsIgnoreCase(raw.trim())) {
                    return m;
                }
            }
            return NaruCachingMode.NONE;
        }
        String protocolId = resolvedProtocolId(session);
        if (protocolId != null && protocolId.equalsIgnoreCase(NaruModelProtocolAnthropicCompat.PROTOCOL_ID)) {
            return NaruCachingMode.EXPLICIT_INLINE;
        }
        return NaruCachingMode.AUTOMATIC_PREFIX;
    }
}
