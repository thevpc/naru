package net.thevpc.naru.ext.budget;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Guards the price vocabulary: parsing and validating a user-entered price,
 * resolving a key to a price, and surviving a tson round-trip.
 */
public class NaruUnitPriceTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    // ── parsing / validation ─────────────────────────────────────────────────

    @Test
    public void parsesAWellFormedPrice() {
        List<String> warnings = new ArrayList<>();
        NaruUnitPrice p = NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                "3", "15", "0.3", "3.75", warnings);
        Assertions.assertEquals(bd("3"), p.input());
        Assertions.assertEquals(bd("15"), p.output());
        Assertions.assertEquals(bd("0.3"), p.cacheRead());
        Assertions.assertEquals(bd("3.75"), p.cacheWrite());
        Assertions.assertEquals(NaruPriceSource.USER, p.source());
        Assertions.assertTrue(warnings.isEmpty());
    }

    @Test
    public void cacheFieldsAreOptionalAndDefaultToInput() {
        NaruUnitPrice p = NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                "3", "15", null, null, new ArrayList<>());
        Assertions.assertNull(p.cacheRead());
        Assertions.assertNull(p.cacheWrite());
        Assertions.assertEquals(bd("3"), p.cacheReadPrice());
        Assertions.assertEquals(bd("3"), p.cacheWritePrice());
        Assertions.assertFalse(p.hasExplicitCacheRead());
    }

    @Test
    public void rejectsAnImplausiblyLargePrice() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                        "5000", "15", null, null, new ArrayList<>()));
        Assertions.assertTrue(ex.getMessage().contains("implausibly large"), ex.getMessage());
    }

    @Test
    public void rejectsNonNumericAndNegativePrices() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                        "abc", "15", null, null, new ArrayList<>()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                        "-1", "15", null, null, new ArrayList<>()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                        "3", null, null, null, new ArrayList<>()));
    }

    @Test
    public void warnsOnASuspiciouslySmallNonzeroPrice() {
        List<String> warnings = new ArrayList<>();
        NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                "3", "0.0001", null, null, warnings);
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertTrue(warnings.get(0).contains("suspiciously small"), warnings.toString());
    }

    // ── resolution ───────────────────────────────────────────────────────────

    @Test
    public void exactModelKeyBeatsTheProviderKey() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.set("anthropic", NaruUnitPrice.user(bd("3"), bd("15"), null, null));
        table.set("anthropic/claude-sonnet-4", NaruUnitPrice.user(bd("2"), bd("10"), null, null));

        Assertions.assertEquals(bd("2"),
                table.priceFor("anthropic", "claude-sonnet-4").input());
        Assertions.assertEquals(bd("3"),
                table.priceFor("anthropic", "claude-haiku").input(), "falls back to the provider price");
    }

    @Test
    public void aSlashInTheModelDoesNotSplitTheProvider() {
        // openrouter/anthropic/claude-x is provider openrouter, model anthropic/claude-x
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.set("openrouter/anthropic/claude-x", NaruUnitPrice.user(bd("1"), bd("2"), null, null));
        Assertions.assertEquals(bd("1"),
                table.priceFor("openrouter", "anthropic/claude-x").input());
    }

    @Test
    public void noMatchMeansUnpricedNotZero() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        Assertions.assertNull(table.priceFor("openai", "gpt-4o"));
        Assertions.assertNull(table.priceFor(null, "gpt-4o"));
    }

    @Test
    public void refreshNeverOverwritesAUserEntry() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.set("openai", NaruUnitPrice.user(bd("2.5"), bd("10"), null, null));
        table.setFetched("openai", NaruUnitPrice.parse(NaruPriceSource.FETCHED, Instant.now(),
                "https://example.test", "99", "99", null, null, new ArrayList<>()));
        Assertions.assertEquals(bd("2.5"), table.get("openai").input(),
                "a fetched price must not overwrite a deliberate user price");
        Assertions.assertEquals(NaruPriceSource.USER, table.get("openai").source());
    }

    @Test
    public void refreshFillsAKeyTheUserNeverSet() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.setFetched("openai", NaruUnitPrice.parse(NaruPriceSource.FETCHED, Instant.now(),
                "https://example.test", "2.5", "10", null, null, new ArrayList<>()));
        Assertions.assertEquals(bd("2.5"), table.get("openai").input());
        Assertions.assertEquals(NaruPriceSource.FETCHED, table.get("openai").source());
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Test
    public void priceTableSurvivesATsonRoundTrip() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.set("anthropic", NaruUnitPrice.user(bd("3"), bd("15"), bd("0.3"), bd("3.75")));
        table.set("openai/gpt-4o", NaruUnitPrice.parse(NaruPriceSource.FETCHED,
                Instant.parse("2026-01-02T03:04:05Z"), "https://example.test",
                "2.5", "10", null, null, new ArrayList<>()));

        NaruBudgetPriceTable back = NaruBudgetPriceTable.ofElement(table.toElement());

        Assertions.assertEquals(bd("3"), back.get("anthropic").input());
        Assertions.assertEquals(bd("0.3"), back.get("anthropic").cacheRead());
        Assertions.assertEquals(bd("3.75"), back.get("anthropic").cacheWrite());
        Assertions.assertEquals(NaruPriceSource.USER, back.get("anthropic").source());
        // exact string round-trip: money must not pass through double
        Assertions.assertEquals(new BigDecimal("2.5"), back.get("openai/gpt-4o").input());
        Assertions.assertEquals(NaruPriceSource.FETCHED, back.get("openai/gpt-4o").source());
        Assertions.assertEquals(Instant.parse("2026-01-02T03:04:05Z"), back.get("openai/gpt-4o").fetchedAt());
        Assertions.assertEquals("https://example.test", back.get("openai/gpt-4o").origin());
    }

    @Test
    public void unsetRemovesTheEntry() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        table.set("anthropic", NaruUnitPrice.user(bd("3"), bd("15"), null, null));
        Assertions.assertNotNull(table.unset("anthropic"));
        Assertions.assertNull(table.get("anthropic"));
        Assertions.assertNull(table.unset("anthropic"));
    }
}
