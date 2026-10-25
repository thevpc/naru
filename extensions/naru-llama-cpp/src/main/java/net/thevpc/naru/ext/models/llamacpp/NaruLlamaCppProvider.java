package net.thevpc.naru.ext.models.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openai.AbstractOpenAICompatProvider;
import net.thevpc.naru.ext.models.openai.NaruModelProtocolOpenAICompat;
import net.thevpc.naru.ext.tools.llamacpp.LlamaCppPaths;
import net.thevpc.naru.ext.tools.llamacpp.LlamaCppService;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Chat provider for a local {@code llama-server} process.
 *
 * <p>llama.cpp exposes the OpenAI-compatible API under {@code /v1}, so this
 * provider is an {@link AbstractOpenAICompatProvider} whose base URL is the
 * server root ({@code llamacpp.url}, default {@code http://127.0.0.1:8080}) and
 * whose chat path is {@code v1/chat/completions}. The path is chosen to agree
 * with the resolved base URL: a URL that already ends in {@code /v1} keeps the
 * plain {@code chat/completions} path, so both conventions work.
 *
 * <p>There is no API key. A model is any local GGUF: the model ids come from the
 * models directory (and, when the server is up, from its live {@code /v1/models}).
 * Installing a model provisions its GGUF file(s); uninstalling deletes them.
 */
public class NaruLlamaCppProvider extends AbstractOpenAICompatProvider {

    private final java.util.Map<String, net.thevpc.naru.ext.tools.llamacpp.LlamaCppModelInfo> localModels = new java.util.HashMap<>();

    public NaruLlamaCppProvider() {
        super("llamacpp", new String[0]);
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return LlamaCppPaths.url(session);
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        long contextLength = configValue("contextLength", session)
                .flatMap(x -> NLiteral.of(x).asLong()).orElse(32768L);
        // llama.cpp does automatic prefix caching natively
        return new NaruModelCapabilitiesImpl(false, true, false, false, contextLength, NaruCachingMode.AUTOMATIC_PREFIX, true, true, true);
    }

    @Override
    protected NaruModelProtocol createDefaultProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        return new NaruModelProtocolOpenAICompat(this, model, name(),
                resolvedChatPathFor(session), capabilities, resolvedBaseUrl(session, defaultProtocol()),
                isLocal(session), defaultReadTimeout(session));
    }

    /**
     * The OpenAI-compatible chat path, adjusted so it never doubles a {@code /v1}
     * that the configured base URL already carries.
     */
    private String resolvedChatPathFor(NaruSession session) {
        String explicit = configValue("chatPath", session).orNull();
        if (!NBlankable.isBlank(explicit)) {
            return explicit.trim();
        }
        String base = resolvedBaseUrl(session, defaultProtocol());
        if (base != null && base.matches(".*/v\\d+$")) {
            return "chat/completions";
        }
        return "v1/chat/completions";
    }

    @Override
    public boolean isAvailable(NaruSession session) {
        return LlamaCppService.of().isRunning(session);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        LlamaCppService service = LlamaCppService.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>(service.modelIds(session));
        localModels.clear();
        for (var info : service.listModels(session)) localModels.put(info.getId(), info);
        if (service.isRunning(session)) {
            for (NaruModelPsResult r : service.listPs(session)) {
                ids.add(r.getModel().model());
            }
        }
        return new ArrayList<>(ids);
    }

    @Override
    public NaruModelInfo getModelInfo(String model, NaruModelCapabilities capabilities, NaruSession session) {
        var local = localModels.get(model);
        if (local != null) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "(?i)(?:^|[-_.])((?:IQ|Q)[0-9]+_[A-Z0-9_]+|TQ[0-9]+_[A-Z0-9_]+|F16|BF16|F32)(?:[-_.]|$)")
                        .matcher(local.getFileName());
                String quant = matcher.find() ? matcher.group(1).toUpperCase(java.util.Locale.ROOT) : null;
                return new NaruModelInfo(name(), model, capabilities, quant,
                        local.getSizeBytes(), null, "GGUF");
        }
        return new NaruModelInfo(name(), model, capabilities);
    }

    @Override
    public boolean isSupportedInstallModel() {
        return true;
    }

    @Override
    public void installModel(NaruModelKey key, NaruSession session) {
        LlamaCppService.of().pullModel(key.model(), session, null);
    }

    @Override
    public boolean isSupportedUninstallModel() {
        return true;
    }

    @Override
    public void uninstallModel(NaruModelKey key, NaruSession session) {
        LlamaCppService.of().rmModel(key.model(), session, null);
    }

    @Override
    public boolean isSupportedPsModel() {
        return true;
    }

    @Override
    public List<NaruModelPsResult> psModel(NaruSession session) {
        return LlamaCppService.of().listPs(session);
    }
}
