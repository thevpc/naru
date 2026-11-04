package net.thevpc.naru.ext.budget;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.time.NDuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit, opt-in fetching of unit prices from two public, machine-readable sources.
 *
 * <p>Nothing here runs in the background: {@code /budget price refresh} is a network
 * call the caller asked for, and it is gated like every other accounting write.
 *
 * <p>The sources publish <em>per-token</em> prices; NARU stores <em>per-1M</em>, so
 * every value is scaled by {@link BigDecimal#movePointRight(int) movePointRight(6)} —
 * exact, because the value is a decimal literal and not a double.
 *
 * <p>Two caveats are part of the contract, not bugs:
 * <ul>
 *   <li>Model-id matching is by the published id keyed under the source's provider
 *       name (e.g. {@code openrouter/anthropic/claude-3.5-sonnet}). A model whose id
 *       the source does not list stays unpriced, and refresh says so rather than
 *       guessing.</li>
 *   <li>Flat per-model prices ignore long-context tiers, batch discounts and the
 *       difference between 5-minute and 1-hour cache-write rates, so a fetched cost is
 *       approximate. Fetched entries are marked as such and never overwrite a price the
 *       user set.</li>
 * </ul>
 */
public final class NaruPriceRefresher {

    /** OpenRouter's public models endpoint; prices are per-token decimal strings. */
    public static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    /** LiteLLM's published price map; costs are per-token numbers. */
    public static final String LITELLM_PRICES_URL =
            "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json";

    private NaruPriceRefresher() {
    }

    /**
     * Fetches both sources, applies every id it can, and returns a human-readable
     * report. A source that is unreachable is reported and skipped: a partial refresh
     * is more useful than a failed one, and the table is written incrementally.
     */
    public static String refresh(NaruBudgetPriceTable table) {
        List<String> lines = new ArrayList<>();
        Instant now = Instant.now();
        int openrouter = 0;
        int litellm = 0;
        try {
            NElement root = fetchJson(OPENROUTER_MODELS_URL);
            openrouter = applyOpenRouter(table, root, now);
            lines.add("openrouter: applied " + openrouter + " price(s) from " + OPENROUTER_MODELS_URL);
        } catch (RuntimeException ex) {
            lines.add("openrouter: skipped (" + ex.getMessage() + ")");
        }
        try {
            NElement root = fetchJson(LITELLM_PRICES_URL);
            litellm = applyLiteLlm(table, root, now);
            lines.add("litellm: applied " + litellm + " price(s) from " + LITELLM_PRICES_URL);
        } catch (RuntimeException ex) {
            lines.add("litellm: skipped (" + ex.getMessage() + ")");
        }
        lines.add("note: fetched prices are flat per-model and ignore long-context tiers, "
                + "batch discounts and cache-write rate variation; a price you set by hand is never overwritten");
        lines.add("note: models neither source lists stay unpriced — the report's 'unpriced:' lines name them");
        return String.join("\n", lines);
    }

    private static NElement fetchJson(String url) {
        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(20))
                .baseUri(url);
        NHttpRequest request = http.GET("")
                .timeout(NDuration.ofSeconds(30))
                .header("User-Agent", "naru-budget");
        NHttpResponse response = request.run().failFast();
        return NElementReader.ofJson().read(response.contentAsString());
    }

    /**
     * OpenRouter: {@code {"data":[{"id":"anthropic/claude-x","pricing":{"prompt":"..","completion":".."}}]}}.
     * The id already contains the origin provider, so the key is {@code openrouter/<id>}.
     *
     * @return how many distinct keys were applied
     */
    static int applyOpenRouter(NaruBudgetPriceTable table, NElement root, Instant fetchedAt) {
        NObjectElement o = root == null ? null : root.asObject().orNull();
        if (o == null) {
            return 0;
        }
        NElement data = o.get("data").orNull();
        if (data == null || !data.isAnyArray()) {
            return 0;
        }
        int applied = 0;
        Map<String, NaruUnitPrice> collected = new LinkedHashMap<>();
        for (NElement child : data.asArray().get().children()) {
            NObjectElement model = child.asObject().orNull();
            if (model == null) {
                continue;
            }
            String id = model.getStringValue("id").orNull();
            NObjectElement pricing = model.get("pricing").flatMap(x -> x.asObject()).orNull();
            if (id == null || pricing == null) {
                continue;
            }
            String input = perTokenToPerMillion(pricing.getStringValue("prompt").orNull());
            String output = perTokenToPerMillion(pricing.getStringValue("completion").orNull());
            String cacheRead = perTokenToPerMillion(firstString(pricing, "input_cache_read", "cache_read"));
            String cacheWrite = perTokenToPerMillion(firstString(pricing, "input_cache_write", "cache_write"));
            NaruUnitPrice price = parseQuietly(fetchedAt, "openrouter", input, output, cacheRead, cacheWrite);
            if (price != null) {
                collected.put("openrouter/" + id, price);
            }
        }
        for (Map.Entry<String, NaruUnitPrice> e : collected.entrySet()) {
            table.setFetched(e.getKey(), e.getValue());
            applied++;
        }
        return applied;
    }

    /**
     * LiteLLM: a top-level object keyed by model id, each with
     * {@code input_cost_per_token}, {@code output_cost_per_token},
     * {@code cache_read_input_token_cost} and {@code litellm_provider}.
     *
     * @return how many distinct keys were applied
     */
    static int applyLiteLlm(NaruBudgetPriceTable table, NElement root, Instant fetchedAt) {
        NObjectElement o = root == null ? null : root.asObject().orNull();
        if (o == null) {
            return 0;
        }
        Map<String, NaruUnitPrice> collected = new LinkedHashMap<>();
        for (var pair : o.namedPairs()) {
            String modelId = pair.key().asStringValue().orNull();
            NObjectElement model = pair.value().asObject().orNull();
            if (modelId == null || model == null) {
                continue;
            }
            String provider = model.getStringValue("litellm_provider").orNull();
            String input = perMillionField(model, "input_cost_per_token");
            String output = perMillionField(model, "output_cost_per_token");
            String cacheRead = perMillionField(model, "cache_read_input_token_cost");
            String cacheWrite = perMillionField(model, "cache_creation_input_token_cost");
            NaruUnitPrice price = parseQuietly(fetchedAt, "litellm", input, output, cacheRead, cacheWrite);
            if (price != null && provider != null && !provider.isBlank()) {
                // key by the published provider so it matches how NARU names providers;
                // an unknown provider is left to LiteLLM's own bare id under "litellm"
                collected.put(provider + "/" + modelId, price);
            } else if (price != null) {
                collected.put("litellm/" + modelId, price);
            }
        }
        for (Map.Entry<String, NaruUnitPrice> e : collected.entrySet()) {
            table.setFetched(e.getKey(), e.getValue());
        }
        return collected.size();
    }

    private static String firstString(NObjectElement o, String... keys) {
        for (String k : keys) {
            String v = o.getStringValue(k).orNull();
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Reads a numeric field that a source may publish as a JSON number rather than a
     * string, and scales per-token to per-1M. The value is taken as its literal text so
     * it never passes through a double.
     */
    private static String perMillionField(NObjectElement o, String key) {
        NElement v = o.get(key).orNull();
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asStringValue().orNull();
        if (s == null || s.isBlank()) {
            s = v.toString();
        }
        return perTokenToPerMillion(s);
    }

    /**
     * Scales a per-token literal to a per-1M one. Returns null for blank input or an
     * unreadable literal, so a source that adds a non-numeric field is skipped rather
     * than failing the whole refresh.
     */
    static String perTokenToPerMillion(String perToken) {
        if (perToken == null || perToken.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(perToken.trim()).movePointRight(6).toPlainString();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static NaruUnitPrice parseQuietly(Instant fetchedAt, String origin,
                                              String input, String output, String cacheRead, String cacheWrite) {
        if (input == null || output == null) {
            return null;
        }
        try {
            return NaruUnitPrice.parse(NaruPriceSource.FETCHED, fetchedAt, origin,
                    input, output, cacheRead, cacheWrite, null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
