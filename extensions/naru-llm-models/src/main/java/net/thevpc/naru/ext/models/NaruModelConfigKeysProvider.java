package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The per-instance wire keys of the model providers this extension ships.
 *
 * <p>Each configured instance reads its own {@code <id>.*} keys through
 * {@link NaruModelProtocolBase} (design doc §6: the instance's own value
 * first, then the layered env under {@code <instance id>.<key>}, then the
 * type's defaults). The keys only exist once the instance exists, so this
 * provider computes them from {@code registry().modelProviders()} — the
 * built-in types and every registered instance — and stays safe to run on
 * completion: a plain map read, no probe, no network. The {@code audit.dir}
 * keys are the extension's own audit-store settings.
 */
public class NaruModelConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "llm-models";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        List<NaruConfigKey> out = new ArrayList<>();
        Map<String, NaruModelProvider> providers = providers(session);
        if (providers != null) {
            try {
                for (NaruModelProvider p : providers.values()) {
                    if (p == null) {
                        continue;
                    }
                    String id = p.name();
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    out.add(NaruConfigKey.of(id + ".apiKey",
                            "API key of the '" + id + "' provider instance (also 'apikey'/'key')"));
                    out.add(NaruConfigKey.of(id + ".url",
                            "base url of the '" + id + "' endpoint"));
                    out.add(NaruConfigKey.of(id + ".readTimeout",
                            "read timeout of '" + id + "' calls, e.g. 120s"));
                    out.add(NaruConfigKey.of(id + ".connectTimeout",
                            "connect timeout of '" + id + "' calls, e.g. 15s"));
                    out.add(NaruConfigKey.of(id + ".timeout",
                            "timeout for both phases of a '" + id + "' call, e.g. 120s"));
                    out.add(NaruConfigKey.of(id + ".maxRetries",
                            "retries before a '" + id + "' call is reported as failed"));
                    out.add(NaruConfigKey.of(id + ".retryPeriod",
                            "delay between retries of a '" + id + "' call, e.g. 2s"));
                    out.add(NaruConfigKey.of(id + ".local",
                            "whether the '" + id + "' endpoint is local").withValues("true", "false"));
                }
            } catch (RuntimeException ignored) {
                // completion is best effort; a misbehaving provider map must not break Tab
            }
        }
        out.add(NaruConfigKey.of("audit.dir",
                "directory where request/response audits are written (overrides llm.audit.dir)"));
        out.add(NaruConfigKey.of("llm.audit.dir",
                "legacy audit directory key, read when audit.dir is unset"));
        return out;
    }

    private static Map<String, NaruModelProvider> providers(NaruSession session) {
        if (session == null || session.registry() == null) {
            return null;
        }
        try {
            return session.registry().modelProviders();
        } catch (RuntimeException e) {
            return null;
        }
    }
}