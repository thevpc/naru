package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code model_list} — discover the models {@code delegate_to_model} can target.
 * <p>
 * This is the listing companion {@code delegate_to_model} deliberately is not: the
 * delegate tool's description used to inline the whole catalog, which conflated
 * "call a model" with "browse models" and bloated every system prompt. The listing
 * lives here instead, with filters for capability (vision, tools, thinking,
 * embedding, text-only, streaming, caching), cost (free/paid), provider and keyword.
 * <p>
 * By default only <em>registered</em> models are listed: the ones a
 * {@code /model add} registration exposes, plus any model a provider explicitly
 * pins. The auto-enumerated catalog of a built-in provider (hundreds of
 * OpenRouter models, for instance) is hidden unless {@code all=true} is passed, so
 * the common answer stays small and readable.
 */
public class ModelListTool extends DefaultNaruTool {

    public ModelListTool() {
        super("model_list", new String[]{NaruToolTags.AI});
    }

    @Override
    public String name() {
        return "model_list";
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Lists the AI models available for delegation, so the right target can be picked "
                + "(for example a vision model for an image task). By default only registered models "
                + "are listed; pass all=true to include every model the built-in providers enumerate. "
                + "Combine filters: capability, free, provider, query.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("capability",
                        "Optional capability filter, comma-separated; every named capability must be supported. "
                                + "One of: vision, tools, thinking, embedding, text-only, streaming, caching.",
                        false).build(),
                NaruToolParameter.bool("free",
                        "Optional cost filter: true lists only free models, false only paid ones, omitted lists both.",
                        false).build(),
                NaruToolParameter.string("provider",
                        "Optional provider instance id or type to keep (e.g. ollama, openrouter, personal).",
                        false).build(),
                NaruToolParameter.string("query",
                        "Optional keyword matched against the model id and the provider.",
                        false).build(),
                NaruToolParameter.bool("all",
                        "When true, list the full catalog including built-in auto-enumerated models; "
                                + "default false lists only registered models.",
                        false, false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return listModels(context.task(),
                context.stringArg("capability").orNull(),
                context.booleanArg("free").orNull(),
                context.stringArg("provider").orNull(),
                context.stringArg("query").orNull(),
                context.booleanArg("all").orNull());
    }

    /**
     * The listing itself, static so tests can drive it without the tool plumbing.
     *
     * @param capability comma/space separated capability names (AND semantics), or null
     * @param free       true free only, false paid only, null both
     * @param provider   provider instance id or type, or null
     * @param query      substring matched on model id and provider, or null
     * @param all        true to include the built-in auto-enumerated catalog
     */
    public static String listModels(NaruTask task, String capability, Boolean free,
                                    String provider, String query, Boolean all) {
        List<NaruModelInfo> catalog = task.session().registry().modelsInfos(task.session());
        if (catalog == null) {
            catalog = new ArrayList<>();
        }
        boolean includeAll = Boolean.TRUE.equals(all);

        List<String> capabilities = parseList(capability);
        List<String> unknown = new ArrayList<>();
        for (String c : capabilities) {
            if (capabilityKey(c) == null) {
                unknown.add(c);
            }
        }
        if (!unknown.isEmpty()) {
            return "Error: unknown capability '" + String.join(", ", unknown)
                    + "'. Valid capabilities: vision, tools, thinking, embedding, text-only, streaming, caching.";
        }

        Set<String> registered = includeAll ? null : registeredProviderIds(task);

        List<NaruModelInfo> models = new ArrayList<>();
        for (NaruModelInfo m : catalog) {
            if (registered != null && !registered.contains(m.provider().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String type = providerType(task, m.provider());
            boolean isFree = isFree(m, type);
            if (free != null && free != isFree) {
                continue;
            }
            if (!NBlankable.isBlank(provider)
                    && !m.provider().equalsIgnoreCase(provider)
                    && !type.equalsIgnoreCase(provider)) {
                continue;
            }
            if (!NBlankable.isBlank(query)) {
                String q = query.toLowerCase(Locale.ROOT);
                if (!m.model().toLowerCase(Locale.ROOT).contains(q)
                        && !m.provider().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
            }
            if (!capabilities.isEmpty() && !matchesCapabilities(m.capabilities(), capabilities)) {
                continue;
            }
            models.add(m);
        }

        if (models.isEmpty()) {
            if (catalog.isEmpty()) {
                return "No available models found. Check that a provider is reachable or configured "
                        + "(for example an Ollama server or an API key).";
            }
            if (!includeAll && registered.isEmpty()) {
                return "No registered models. Create one with '/model add <id> --provider=<type>' "
                        + "or call model_list with all=true to list all " + catalog.size()
                        + " available models.";
            }
            boolean filtered = !capabilities.isEmpty() || free != null
                    || !NBlankable.isBlank(provider) || !NBlankable.isBlank(query);
            return filtered
                    ? "No model matches the given filters (" + catalog.size() + " available in total)."
                    : "No registered models found (pass all=true to list all "
                    + catalog.size() + " available models).";
        }

        boolean filtered = !capabilities.isEmpty() || free != null
                || !NBlankable.isBlank(provider) || !NBlankable.isBlank(query);
        StringBuilder sb = new StringBuilder();
        sb.append(includeAll ? "Available models" : "Registered models")
                .append(filtered ? " (filtered)" : "")
                .append(": ").append(models.size());
        if (!includeAll) {
            sb.append(" of ").append(catalog.size());
        }
        sb.append('\n');
        for (NaruModelInfo m : models) {
            sb.append("  ").append(m.provider()).append('/').append(m.model());
            String type = providerType(task, m.provider());
            if (!type.equalsIgnoreCase(m.provider())) {
                sb.append(" (").append(type).append(')');
            }
            NaruModelCapabilities c = m.capabilities();
            if (c != null) {
                Set<String> keys = c.keys();
                if (keys != null && !keys.isEmpty()) {
                    sb.append(" [").append(String.join(",", keys)).append(']');
                }
                long cl = c.contextLength();
                if (cl > 0) {
                    sb.append(" {").append(NaruUtils.formattedTokensSize(cl)).append('}');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * The provider instance ids that count as "registered": every {@code /model add}
     * registration, plus any provider that explicitly pins a {@code model}/{@code models}
     * subset. Auto-enumerated built-in providers are deliberately absent.
     */
    private static Set<String> registeredProviderIds(NaruTask task) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, NaruModelRegistration> regs = task.session().registrations();
        if (regs != null) {
            for (String id : regs.keySet()) {
                if (!NBlankable.isBlank(id)) {
                    ids.add(id.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, NaruModelProvider> providers = task.session().registry().modelProviders();
        if (providers != null) {
            for (NaruModelProvider p : providers.values()) {
                if (p != null && declaresModels(p)) {
                    ids.add(p.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        return ids;
    }

    private static boolean declaresModels(NaruModelProvider p) {
        return !NBlankable.isBlank(p.rawParam("model").orNull())
                || !NBlankable.isBlank(p.rawParam("models").orNull());
    }

    /** The provider type behind an instance id (the id itself when it is a built-in). */
    private static String providerType(NaruTask task, String id) {
        NaruModelProvider p = task.session().registry().provider(id).orNull();
        return p == null || NBlankable.isBlank(p.type()) ? id : p.type();
    }

    /**
     * The same free heuristic the {@code /model list --free} filter uses:
     * OpenRouter's {@code :free} variants, and anything served by an Ollama type
     * (local models cost nothing), including Ollama registrations.
     */
    private static boolean isFree(NaruModelInfo m, String providerType) {
        return m.model().toLowerCase(Locale.ROOT).endsWith(":free")
                || "ollama".equalsIgnoreCase(providerType);
    }

    private static boolean matchesCapabilities(NaruModelCapabilities c, List<String> requested) {
        if (c == null) {
            return false;
        }
        for (String r : requested) {
            String key = capabilityKey(r);
            if (key == null) {
                return false;
            }
            switch (key) {
                case "vision":
                    if (!c.isVision()) return false;
                    break;
                case "tools":
                    if (!c.isTools()) return false;
                    break;
                case "thinking":
                    if (!c.isThinking()) return false;
                    break;
                case "embedding":
                    if (!c.isEmbedding()) return false;
                    break;
                case "text-only":
                    if (!c.isTextOnly()) return false;
                    break;
                case "streaming":
                    if (!c.isStreaming()) return false;
                    break;
                case "caching":
                    if (c.cachingMode() == null || c.cachingMode() == NaruCachingMode.NONE) return false;
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    /** Normalizes a capability token, or null when it is not one. */
    private static String capabilityKey(String token) {
        if (token == null) {
            return null;
        }
        switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "vision":
            case "image":
            case "images":
                return "vision";
            case "tools":
            case "tool":
            case "function":
            case "functions":
                return "tools";
            case "thinking":
            case "reason":
            case "reasoning":
                return "thinking";
            case "embedding":
            case "embed":
                return "embedding";
            case "text-only":
            case "text":
                return "text-only";
            case "streaming":
            case "stream":
                return "streaming";
            case "caching":
            case "cache":
                return "caching";
            default:
                return null;
        }
    }

    private static List<String> parseList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split("[,;\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
