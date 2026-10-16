package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.ext.budget.NaruAccumulatorBuilder;
import net.thevpc.naru.ext.budget.NaruModelBudgetStats;
import net.thevpc.naru.ext.budget.NaruTokenTransaction;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The default project-level store: an <b>append-only log of transactions</b>, one file
 * per store instance (hence per process), merged on read.
 *
 * <h2>Why one file per process</h2>
 * Several naru processes can share one project directory. A single mutable file that
 * every process rewrites loses writes when they interleave; giving each process its own
 * append-only file means writers never contend, at the cost of a merge on read. A file is
 * named after the process id and the instance's start time, so two agents in one JVM do
 * not share a writer either.
 *
 * <h2>Crash tolerance</h2>
 * Every transaction is appended and flushed as it is tracked, so a cancel or a kill does
 * not lose the spend already recorded. A process killed mid-append can leave a truncated
 * last line; the reader skips any line it cannot parse rather than failing the report.
 *
 * <h2>Retention</h2>
 * The log is never rewritten. {@link #dropTransactionsBefore} exists for the SPI but is a
 * no-op here: compaction of an append-only log shared by other processes is a follow-up,
 * and silently dropping lines another process still needs would be worse than unbounded
 * growth in a project directory that is already gitignored.
 */
public class NaruFileBudgetStore implements NaruBudgetStore {

    private static final String SUFFIX = ".tsonl";

    private final NPath dir;
    private final NPath file;
    private final Object writeLock = new Object();

    public NaruFileBudgetStore(NPath dir) {
        this.dir = dir;
        this.dir.mkdirs();
        String name = "tx-" + ProcessHandle.current().pid() + "-"
                + Long.toHexString(System.nanoTime()) + SUFFIX;
        this.file = dir.resolve(name);
    }

    /** The directory holding every process's transaction log. */
    public NPath directory() {
        return dir;
    }

    @Override
    public void saveTransaction(NaruTokenTransaction transaction) {
        if (transaction == null) {
            return;
        }
        String line = NElementWriter.ofTson().compact(true).formatPlain(transaction.toElement());
        synchronized (writeLock) {
            // append + create, per transaction: opened and closed every time so a kill
            // loses at most the call in flight, not the buffer
            file.mkParentDirs().writeString(line + "\n", NPathOption.APPEND, NPathOption.CREATE);
        }
    }

    @Override
    public List<NaruTokenTransaction> findTransactions(NaruBudgetStoreQuery query) {
        List<NaruTokenTransaction> out = new ArrayList<>();
        visitTransactions(query, out::add);
        return out;
    }

    @Override
    public Set<String> findLabelValues(String label, NaruBudgetStoreQuery query) {
        Set<String> ret = new TreeSet<>();
        visitTransactions(query, t -> {
            String v = t.getLabels().asMap().get(label);
            if (v != null) {
                ret.add(v);
            }
        });
        return ret;
    }

    @Override
    public NaruModelBudgetStats aggregate(NaruBudgetStoreQuery query) {
        NaruAccumulatorBuilder b = new NaruAccumulatorBuilder();
        visitTransactions(query, b::accumulate);
        return b.build();
    }

    @Override
    public long dropTransactionsBefore(Instant instant) {
        // see the class javadoc: append-only, never rewritten
        return 0;
    }

    private void visitTransactions(NaruBudgetStoreQuery query, Consumer<NaruTokenTransaction> consumer) {
        if (!dir.exists()) {
            return;
        }
        List<NPath> files;
        try {
            files = dir.list();
        } catch (Exception ex) {
            return;
        }
        for (NPath p : files) {
            if (p.name() == null || !p.name().endsWith(SUFFIX)) {
                continue;
            }
            List<String> lines;
            try {
                lines = p.lines().toList();
            } catch (Exception ex) {
                continue;
            }
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                NaruTokenTransaction t;
                try {
                    t = NaruTokenTransaction.ofElement(NElementReader.ofTson().read(line));
                } catch (Exception ex) {
                    // a truncated line from a killed process: skip it, keep the rest
                    continue;
                }
                if (t == null || !NaruBudgetStore.matchesTime(t, query) || !NaruBudgetStore.matchesLabels(t, query)) {
                    continue;
                }
                consumer.accept(t);
            }
        }
    }
}
