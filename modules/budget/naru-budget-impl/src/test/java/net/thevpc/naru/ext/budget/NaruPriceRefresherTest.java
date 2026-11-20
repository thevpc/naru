package net.thevpc.naru.ext.budget;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The two pure halves of {@code price refresh}: turning the sources' published shapes
 * into table entries. The network call itself is not exercised here — these pin the
 * parsing and the "never overwrite a user price" rule, which are where a refresh can
 * silently do the wrong thing.
 */
public class NaruPriceRefresherTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static void assertPrice(NaruUnitPrice p, String input, String output) {
        Assertions.assertNotNull(p);
        Assertions.assertEquals(0, new BigDecimal(input).compareTo(p.input()),
                "input price: expected " + input + " but was " + p.input());
        Assertions.assertEquals(0, new BigDecimal(output).compareTo(p.output()),
                "output price: expected " + output + " but was " + p.output());
    }

    @Test
    public void openRouterPerTokenPricesBecomePerMillion() {
        NElement root = NElementReader.ofJson().read("""
                {"data":[
                  {"id":"anthropic/claude-x","pricing":{"prompt":"0.000003","completion":"0.000015"}},
                  {"id":"openai/gpt-y","pricing":{"prompt":"0.000001","completion":"0.000002",
                                                 "input_cache_read":"0.0000001"}}
                ]}
                """);
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();

        int applied = NaruPriceRefresher.applyOpenRouter(table, root, Instant.now());

        Assertions.assertEquals(2, applied);
        assertPrice(table.get("openrouter/anthropic/claude-x"), "3", "15");
        NaruUnitPrice cached = table.get("openrouter/openai/gpt-y");
        assertPrice(cached, "1", "2");
        Assertions.assertEquals(0, new BigDecimal("0.1").compareTo(cached.cacheReadPrice()),
                "the cache-read price is scaled the same way");
        Assertions.assertEquals(NaruPriceSource.FETCHED, cached.source());
    }

    @Test
    public void aFetchedPriceNeverOverwritesAUserPrice() {
        NElement root = NElementReader.ofJson().read(
                "{\"data\":[{\"id\":\"anthropic/claude-x\",\"pricing\":{\"prompt\":\"0.000003\",\"completion\":\"0.000015\"}}]}");
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.set("openrouter/anthropic/claude-x",
                NaruUnitPrice.user(new BigDecimal("9"), new BigDecimal("9"), null, null));

        NaruPriceRefresher.applyOpenRouter(table, root, Instant.now());

        NaruUnitPrice p = table.get("openrouter/anthropic/claude-x");
        Assertions.assertEquals(NaruPriceSource.USER, p.source(), "the user's price wins");
        assertPrice(p, "9", "9");
    }

    @Test
    public void liteLlmKeysByThePublishedProvider() {
        // cache_creation_input_token_cost and a numeric (not string) price are the
        // shapes LiteLLM actually publishes
        NElement root = NElementReader.ofJson().read("""
                {"gpt-4o":{"input_cost_per_token":0.000005,"output_cost_per_token":0.000015,
                           "litellm_provider":"openai"},
                 "claude-3-5-sonnet":{"input_cost_per_token":0.000003,"output_cost_per_token":0.000015,
                           "cache_read_input_token_cost":0.0000003,"cache_creation_input_token_cost":0.00000375,
                           "litellm_provider":"anthropic"}}
                """);
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();

        int applied = NaruPriceRefresher.applyLiteLlm(table, root, Instant.now());

        Assertions.assertEquals(2, applied);
        assertPrice(table.get("openai/gpt-4o"), "5", "15");
        NaruUnitPrice claude = table.get("anthropic/claude-3-5-sonnet");
        assertPrice(claude, "3", "15");
        Assertions.assertEquals(0, new BigDecimal("0.3").compareTo(claude.cacheReadPrice()));
        Assertions.assertEquals(0, new BigDecimal("3.75").compareTo(claude.cacheWritePrice()));
    }

    @Test
    public void anUnreadableValueIsSkippedNotGuessed() {
        NElement root = NElementReader.ofJson().read(
                "{\"data\":[{\"id\":\"x\",\"pricing\":{\"prompt\":\"not-a-number\",\"completion\":\"0.000015\"}}]}");
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();

        // no exception, and the model is simply left unpriced
        Assertions.assertEquals(0, NaruPriceRefresher.applyOpenRouter(table, root, Instant.now()));
        Assertions.assertNull(table.get("openrouter/x"));
    }
}
