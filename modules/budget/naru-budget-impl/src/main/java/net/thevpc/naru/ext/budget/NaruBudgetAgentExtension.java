package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruAgentExtension;
import net.thevpc.naru.ext.budget.store.NaruFileBudgetStore;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;

/**
 * The project-level half of budget: one transaction store and one price table, shared by
 * every session the agent hosts.
 *
 * <p>Spend is persisted here and only here. A session owns no transactions — it adds
 * labels to them — so a reloaded session sees the project's spend rather than starting
 * from zero, which is the point: the number in {@code /budget} is what the project has
 * cost, not what this JVM run cost.
 *
 * <p>Owned by the {@link NaruWorkspace} and created lazily once per agent (see
 * {@link NaruAgentExtension}). Both the store and the price table are created against the
 * agent's project directory; if that directory changes, {@link #store()} and
 * {@link #pricesFile()} re-resolve against the new one.
 */
public class NaruBudgetAgentExtension implements NaruAgentExtension {

    public static final String NAME = "budget";

    /** Prices live with the project config: shareable, one per project. */
    static final String PRICES_PATH = ".naru/config/budget-prices.tson";
    /** Transactions live under local: private, gitignorable, machine-local. */
    static final String STORE_PATH = ".naru/local/budget";

    private NaruWorkspace agent;
    private NPath openedProjectDir;
    private NaruFileBudgetStore store;
    private final NaruBudgetPriceTable priceTable = new NaruBudgetPriceTable();
    private boolean pricesLoaded;
    private boolean loadingPrices;

    public NaruBudgetAgentExtension() {
        // every mutation of the shared table is persisted, so `price set` survives a
        // restart without the directive having to remember to save
        priceTable.onChange(this::savePrices);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void open(NaruWorkspace agent) {
        this.agent = agent;
    }

    /** The shared, project-level price table. */
    public NaruBudgetPriceTable priceTable() {
        ensurePricesLoaded();
        return priceTable;
    }

    /** The shared, project-level transaction store. */
    public synchronized NaruFileBudgetStore store() {
        NPath dir = projectDir();
        if (store == null || !dir.equals(openedProjectDir)) {
            openedProjectDir = dir;
            store = new NaruFileBudgetStore(dir.resolve(STORE_PATH));
        }
        return store;
    }

    // ── price persistence ────────────────────────────────────────────────────

    private synchronized void ensurePricesLoaded() {
        if (pricesLoaded) {
            return;
        }
        pricesLoaded = true;
        NPath file = pricesFile();
        if (!file.exists()) {
            return;
        }
        loadingPrices = true;
        try {
            NElement e = NElementReader.ofTson().read(file.readString());
            NaruBudgetPriceTable loaded = NaruBudgetPriceTable.ofElement(e);
            for (var entry : loaded.entries().entrySet()) {
                priceTable.set(entry.getKey(), entry.getValue());
            }
        } catch (Exception ex) {
            // a corrupt price file must not take the agent down; it simply prices nothing
            // until the user sets a price again
        } finally {
            loadingPrices = false;
        }
    }

    synchronized void savePrices() {
        if (agent == null || loadingPrices) {
            // no project to write to yet, or the write would echo a load back to disk
            return;
        }
        NPath file = pricesFile();
        try {
            file.mkParentDirs().writeString(
                    NElementWriter.ofTson().formatPlain(priceTable.toElement()) + "\n",
                    NPathOption.CREATE);
        } catch (Exception ex) {
            // a failed save leaves the in-memory table correct for this run
        }
    }

    // ── paths ────────────────────────────────────────────────────────────────

    private NPath projectDir() {
        NPath pd = agent == null ? null : agent.projectDirectory();
        return pd == null ? NPath.ofTempFolder("naru-budget-agent") : pd;
    }

    NPath pricesFile() {
        return projectDir().resolve(PRICES_PATH);
    }

    /**
     * Resolves the agent's budget extension, or null when {@code naru-budget} is not
     * installed. Usable before any session exists, so prices can be configured and the
     * store reached for a non-interactive run.
     */
    public static NaruBudgetAgentExtension of(NaruWorkspace agent) {
        if (agent == null) {
            return null;
        }
        return agent.extension(NAME, NaruBudgetAgentExtension.class).orNull();
    }
}
