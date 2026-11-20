package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.ext.budget.store.DefaultNaruBudgetStoreQuery;
import net.thevpc.naru.ext.budget.store.NaruBudgetStoreQuery;
import net.thevpc.naru.ext.budget.store.NaruFileBudgetStore;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The project-level store is the half that outlives a session, so its contract is
 * durability, merge-on-read, and surviving a line a killed process left half-written.
 */
class NaruFileBudgetStoreTest {

    private static NaruTokenTransaction tx(String session, String taskPath, long prompt, long completion) {
        NaruModelConfig model = new NaruModelConfig("test", "a");
        return new NaruTokenTransaction(
                Labels.builder()
                        .add("session", session)
                        .add("task-path", taskPath)
                        .add("provider", "test")
                        .add("model", "a")
                        .build(),
                session,
                "u1",
                model,
                prompt,
                completion,
                -1,
                -1,
                Instant.now(),
                NDuration.ofMillis(5));
    }

    @Test
    public void transactionsRoundTripThroughDisk() {
        NPath dir = NPath.ofTempFolder("naru-budget-store-" + System.nanoTime());
        NaruFileBudgetStore store = new NaruFileBudgetStore(dir);

        store.saveTransaction(tx("s1", "1", 100, 10));
        store.saveTransaction(tx("s1", "1", 200, 20));

        // a second store over the same directory is what a second process sees
        NaruFileBudgetStore reopened = new NaruFileBudgetStore(dir);
        List<NaruTokenTransaction> all = reopened.findTransactions(new DefaultNaruBudgetStoreQuery());
        Assertions.assertEquals(2, all.size(), "both appends must be readable by another store");
        Assertions.assertEquals(300, all.stream().mapToLong(NaruTokenTransaction::getPromptTokens).sum());
    }

    @Test
    public void labelPrefixSelectsATaskSubtree() {
        NPath dir = NPath.ofTempFolder("naru-budget-store-" + System.nanoTime());
        NaruFileBudgetStore store = new NaruFileBudgetStore(dir);

        store.saveTransaction(tx("s1", "1", 100, 10));
        store.saveTransaction(tx("s1", "1/2", 200, 20));
        store.saveTransaction(tx("s1", "1/3", 400, 40));
        store.saveTransaction(tx("s1", "9", 800, 80));

        NaruBudgetStoreQuery query = new DefaultNaruBudgetStoreQuery().whereLabelStartsWith("task-path", "1/");
        List<NaruTokenTransaction> subtree = store.findTransactions(query);
        Assertions.assertEquals(2, subtree.size(), "only the descendants of 1/, not 1 itself or 9");
        Assertions.assertEquals(600, subtree.stream().mapToLong(NaruTokenTransaction::getPromptTokens).sum());
    }

    /**
     * A process killed mid-append leaves a partial last line. The reader must skip it and
     * keep the transactions before it, rather than failing the whole report.
     */
    @Test
    public void aCorruptLineIsSkippedNotFatal() {
        NPath dir = NPath.ofTempFolder("naru-budget-store-" + System.nanoTime());
        NaruFileBudgetStore store = new NaruFileBudgetStore(dir);
        store.saveTransaction(tx("s1", "1", 100, 10));

        // simulate the kill: append a truncated object to the store's own log file
        NPath log = dir.list().stream()
                .filter(p -> p.name() != null && p.name().endsWith(".tsonl"))
                .findFirst().orElseThrow();
        log.writeString("{ \"labels\": { \"session\":", NPathOption.APPEND);

        List<NaruTokenTransaction> all = store.findTransactions(new DefaultNaruBudgetStoreQuery());
        Assertions.assertEquals(1, all.size(), "the good line before the corrupt one survives");
    }

    /**
     * Prices are project config, so they must survive a restart and be shared by every
     * session. The table's {@code onChange} hook is what the agent extension uses to save.
     */
    @Test
    public void pricesPersistAndUserEntriesWin() {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        int[] saves = {0};
        table.onChange(() -> saves[0]++);

        table.set("test", NaruUnitPrice.user(new BigDecimal("1.0"), new BigDecimal("2.0"), null, null));

        Assertions.assertEquals(1, saves[0], "a mutation must announce itself so an owner can persist");
        Assertions.assertEquals(new BigDecimal("1.0"), table.priceFor("test", "a").input());

        // a fetched price must not overwrite the user's
        table.setFetched("test", NaruUnitPrice.parse(
                NaruPriceSource.FETCHED, Instant.now(), "unit-test",
                "9.0", "9.0", null, null, null));
        Assertions.assertEquals(new BigDecimal("1.0"), table.priceFor("test", "a").input(),
                "a fetched price must never overwrite a user-set one");

        NaruBudgetPriceTable roundTripped = NaruBudgetPriceTable.ofElement(table.toElement());
        Assertions.assertEquals(new BigDecimal("1.0"), roundTripped.priceFor("test", "a").input());
        Assertions.assertEquals(table.entries(), roundTripped.entries());
    }
}
