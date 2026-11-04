package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruModelTimeouts;
import net.thevpc.naru.api.model.NaruThinkingConfig;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;
import net.thevpc.nuts.elem.NElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code model.*} settings — the vocabulary {@code /settings} documents.
 *
 * <p>The static part is small: the pinned {@code model}, thinking, streaming and
 * the timeouts/retries that every wire protocol honours. The dynamic part is the
 * point of this provider: the timeouts are also readable <b>per model family</b>
 * ({@code NaruModelTimeouts}: {@code model.qwen.readTimeout} beats
 * {@code model.readTimeout} for a {@code qwen3:32b}), and those keys only exist
 * once a model of the family is known to the session. This provider computes the
 * concrete families from what the session already knows, so
 * {@code /settings mod<TAB>} offers exactly the indexed keys that can do
 * something, not an unbounded pattern.
 *
 * <p>Completion runs on Tab and must stay cheap, so the family inventory prefers
 * the session's own records (the pinned model, the last listing, the
 * registrations) and only then the full model catalog, each read guarded.
 */
public class NaruLlmConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        List<NaruConfigKey> out = new ArrayList<>();
        out.add(NaruConfigKey.of("model",
                "the pinned model key for this project (written by /model use-global)"));
        out.add(NaruConfigKey.of(NaruThinkingConfig.THINKING_KEY,
                "switch model reasoning (thinking) on or off; unset lets the model decide")
                .withValues("true", "false"));
        out.add(NaruConfigKey.of("model.noStream",
                "return the full response at once instead of streaming")
                .withValues("true", "false"));
        out.add(NaruConfigKey.of(NaruModelTimeouts.KEY_READ_TIMEOUT,
                "default read timeout of a model call, e.g. 120s"));
        out.add(NaruConfigKey.of(NaruModelTimeouts.KEY_CONNECT_TIMEOUT,
                "default connect timeout of a model call, e.g. 15s"));
        out.add(NaruConfigKey.of(NaruModelTimeouts.KEY_TIMEOUT,
                "default timeout for both phases of a model call, e.g. 120s"));
        out.add(NaruConfigKey.of("model.maxRetries",
                "retries before a model call is reported as failed"));
        out.add(NaruConfigKey.of("model.retryPeriod",
                "delay between retries of a model call, e.g. 2s"));
        // the per-family indexed keys (NaruModelTimeouts.findScoped): only for the
        // families this session actually knows, or completion would offer keys that
        // cannot ever match a model the user can select
        for (String family : modelFamilies(session)) {
            for (String sub : new String[]{"readTimeout", "connectTimeout", "timeout"}) {
                out.add(NaruConfigKey.of("model." + family + "." + sub,
                        "model-family '" + family + "' " + sub + ", overriding model." + sub
                                + " for models of this family"));
            }
        }
        return out;
    }

    /**
     * The model families the session can today address, from the records that
     * exist without a network probe: the pinned model, the last {@code /model}
     * listing, the registrations' own model pins, and finally the full catalog
     * (guarded — an unreachable provider must not break completion).
     */
    private static Set<String> modelFamilies(NaruSession session) {
        LinkedHashSet<String> families = new LinkedHashSet<>();
        if (session == null) {
            return families;
        }
        addFamily(families, pinnedModel(session));
        try {
            List<NaruModelKey> listed = session.listedModels();
            if (listed != null) {
                for (NaruModelKey k : listed) {
                    addFamily(families, k.model());
                }
            }
        } catch (RuntimeException ignored) {
            // completion is best effort; a stopped session must not break Tab
        }
        try {
            Map<String, NaruModelRegistration> regs = session.registrations();
            if (regs != null) {
                for (NaruModelRegistration r : regs.values()) {
                    addFamily(families, r.stringValue("model").orNull());
                    addModelsFamily(families, r.stringValue("models").orNull());
                }
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (session.registry() != null) {
                List<NaruModelKey> catalog = session.registry().modelsKeys(session);
                if (catalog != null) {
                    for (NaruModelKey k : catalog) {
                        addFamily(families, k.model());
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return families;
    }

    private static String pinnedModel(NaruSession session) {
        try {
            NElement model = session.getProjectEnv("model").orNull();
            return model == null ? null : model.asStringValue().orNull();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void addModelsFamily(Set<String> families, String models) {
        if (models == null) {
            return;
        }
        for (String m : models.split("[,\\s]+")) {
            addFamily(families, m);
        }
    }

    private static void addFamily(Set<String> families, String modelId) {
        String family = NaruModelTimeouts.modelFamily(modelId);
        if (family != null) {
            families.add(family);
        }
    }
}