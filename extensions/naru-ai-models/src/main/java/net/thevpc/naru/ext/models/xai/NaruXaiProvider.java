package net.thevpc.naru.ext.models.xai;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpCode;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruXaiProvider extends AbstractOpenAICompatProvider {
    private static final String MANAGEMENT_BASE_URL = "https://management-api.x.ai";
    // Best-effort fallback if the live /models call fails. xAI's lineup moves fast
    // (new flagship roughly monthly in 2026) — treat this as a safety net, not a source of truth.
    private static final List<String> FALLBACK_MODELS = List.of(
            "grok-4.6",
            "grok-4.5",
            "grok-4.3",
            "grok-4.1-fast",
            "grok-build-0.1"
    );
    public NaruXaiProvider() {
        super("xai", new String[]{"XAI_API_KEY", "GROK_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.x.ai/v1";
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = modelName.contains("vision") || modelName.startsWith("grok-4");
        boolean tools = true; // all current Grok chat models support function calling
        boolean thinking = modelName.contains("grok-3-mini")
                || modelName.startsWith("grok-4")
                || modelName.contains("reasoning");
        boolean embedding = false;

        long contextLength = 131072L; // conservative default
        if (modelName.contains("4.1-fast") || modelName.contains("4.20")) {
            contextLength = 2097152L; // 2M for the long-context Fast/4.20 lines
        } else if (modelName.contains("4.3")) {
            contextLength = 1048576L; // 1M
        } else if (modelName.contains("4.5") || modelName.contains("4.6")) {
            contextLength = 524288L; // 500K flagship context
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    /**
     * Checks prepaid credit balance via the xAI Management API. Requires a separate
     * management key with "Management Keys Read" permission (console.x.ai > Settings) —
     * NOT the same as XAI_API_KEY/GROK_API_KEY used for inference.
     *
     * @param managementApiKey the management key
     * @param teamId the team ID (visible in the console URL, e.g. console.x.ai/team/{teamId})
     * @return balance in USD cents (negative = credit remaining, per xAI's sign convention
     *         seen in their docs example: "total": {"val": "-1000"} means $10.00 available),
     *         or null if the check failed
     */
    public Long fetchPrepaidBalanceCents(String managementApiKey, String teamId,NaruSession session) {
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(connectTimeout(session))
                    .baseUri(MANAGEMENT_BASE_URL);

            NHttpRequest request = http.GET("v1/billing/teams/" + teamId + "/prepaid/balance")
                    .timeout(readTimeout(session))
                    .header("Authorization", "Bearer " + managementApiKey);

            NHttpResponse response = request.run();
            if (response.isError()) {
                NLog.of(getClass()).log(NMsg.ofC(
                        "Failed to fetch xAI prepaid balance: HTTP %s %s",
                        response.statusCode(), response.statusMessage()
                ).asWarning());
                return null;
            }

            NElement root = NElementReader.ofJson().read(response.contentAsString());
            return root.asObject()
                    .flatMap(o -> o.get("total"))
                    .flatMap(t -> t.asObject().flatMap(o -> o.get("val")))
                    .flatMap(NElement::asStringValue)
                    .map(Long::parseLong)
                    .orNull();
        } catch (Exception e) {
            NLog.of(getClass()).log(NMsg.ofC(
                    "Error fetching xAI prepaid balance: %s", e.getMessage()
            ).asWarning());
            return null;
        }
    }

    /**
     * Zero-balance / no-funds detection without a separate Management API key: xAI's
     * inference endpoints return a distinguishable 403 with code "permission-denied" and a
     * message mentioning credits/licenses when a team has no funds. Not as precise as the
     * Management API (can't show exact balance), but sufficient to decide whether to
     * surface this provider to the user at all.
     */
    public boolean hasNoCredits(NHttpResponse response) {
        if (response.statusCode() != NHttpCode.FORBIDDEN) {
            return false;
        }
        try {
            NElement root = NElementReader.ofJson().read(response.contentAsString());
            String code = root.asObject().flatMap(o -> o.get("code"))
                    .flatMap(NElement::asStringValue).orElse("");
            return "permission-denied".equals(code);
        } catch (Exception e) {
            return false;
        }
    }

}