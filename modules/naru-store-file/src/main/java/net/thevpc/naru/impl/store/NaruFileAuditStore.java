package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.store.NaruAuditPruneResult;
import net.thevpc.naru.api.store.NaruAuditRecord;
import net.thevpc.naru.api.store.NaruAuditRetention;
import net.thevpc.naru.api.store.NaruAuditStore;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;
import net.thevpc.nuts.util.NOptional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audit log of one session, on the filesystem.
 *
 * <pre>
 * audit/
 *     records.log          append-only, one compact TSON record per entry
 *     blobs/&lt;aa&gt;/&lt;rest&gt;.obj  request and response bodies, content-addressed
 * </pre>
 *
 * <h2>Why bodies are not in the log</h2>
 * Every record carries the whole conversation as it was sent, so a session that has run a
 * hundred turns stores that hundred-turn conversation a hundred times over. The bodies are
 * therefore written once, under a hash of their content, and the record keeps the name. Two
 * calls with the same body share one copy; and because the bodies are prefix-like, the
 * conversation itself is stored exactly once no matter how many times it is sent.
 *
 * <h2>Why the log is still append-only</h2>
 * A prune rewrites the file, which is the one thing an append-only log is not supposed to
 * do. It does it in one place, with a deliberate order -- rewrite the records, then sweep
 * the blobs the survivors still reference -- because the alternative order leaves records
 * pointing at deleted blobs. That is the failure this design exists to prevent, so the
 * awkward case is handled explicitly instead of being left to whoever writes the next
 * prune.
 */
class NaruFileAuditStore implements NaruAuditStore {

    /**
     * How long an unreferenced blob must be unreferenced before deletion. Same reasoning as
     * the version sweep: a record may be written moments before a sweep runs, and its blob
     * must not vanish in between.
     */
    private static final java.time.Duration SWEEP_GRACE = java.time.Duration.ofMinutes(10);

    private final NPath auditDir;
    private final NaruContentAddressedStore blobs;

    NaruFileAuditStore(NPath sessionDir) {
        this.auditDir = sessionDir.resolve("audit");
        this.blobs = new NaruContentAddressedStore(auditDir.resolve("blobs"));
    }

    private NPath logFile() {
        return auditDir.resolve("records.log");
    }

    @Override
    public void append(NaruAuditRecord record, String requestBody, String responseBody) {
        String requestRef = blobs.putPayload(requestBody);
        String responseRef = blobs.putPayload(responseBody);
        NaruAuditRecord toWrite = record;
        if (requestRef != null || responseRef != null) {
            toWrite = rebuildWithRefs(record, requestRef, responseRef);
        }
        NaruFileIo.mkdirs(auditDir);
        // blank-line separated, one record per line: an append-only reader that knows
        // nothing about the reference scheme can still read one record at a time
        logFile().writeString(NaruContentAddressedStore.compact(toWrite.toElement()) + "\n\n",
                NPathOption.APPEND);
    }

    private static NaruAuditRecord rebuildWithRefs(NaruAuditRecord r, String requestRef, String responseRef) {
        return NaruAuditRecord.builder()
                .id(r.id()).timestamp(r.timestamp()).taskId(r.taskId()).taskName(r.taskName())
                .sessionUuid(r.sessionUuid()).provider(r.provider()).model(r.model())
                .url(r.url()).method(r.method())
                .requestHeaders(r.requestHeaders()).responseHeaders(r.responseHeaders())
                .attempt(r.attempt()).durationMs(r.durationMs())
                .statusCode(r.statusCode()).statusMessage(r.statusMessage())
                .error(r.errorType(), r.errorMessage())
                .requestBodyRef(requestRef)
                .responseBodyRef(responseRef)
                .requestBodyInline(r.requestBodyInline())
                .responseBodyInline(r.responseBodyInline())
                .pruned(r.pruned())
                .build();
    }

    /** Every record element in the log, oldest first. */
    private List<NElement> rawRecords() {
        List<NElement> out = new ArrayList<>();
        if (!NaruFileIo.isFile(logFile())) {
            return out;
        }
        String content;
        try {
            content = NaruFileIo.readString(logFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String part : content.split("\n\\s*\n")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(NaruContentAddressedStore.parse(t));
            } catch (Exception ignored) {
                // a torn final line from a killed process is skipped rather than aborting
                // the whole log; the record it was is not recoverable either way
            }
        }
        return out;
    }

    @Override
    public List<NaruAuditRecord> read(int limit, Instant since) {
        // filtered with no limit first, so that the newest N are the ones resolved: bodies
        // are fetched only for records actually returned, because fetching a hundred blobs
        // to display the newest ten would make the limit a lie
        List<NaruAuditRecord> selected = listRefs(-1, since);
        List<NaruAuditRecord> resolved = new ArrayList<>(selected.size());
        for (NaruAuditRecord r : selected) {
            resolved.add(r.withResolvedBodies(
                    body(r.requestBodyRef()),
                    body(r.responseBodyRef()),
                    r.pruned()));
        }
        return limit < 0 ? resolved : resolved.subList(0, Math.min(limit, resolved.size()));
    }

    @Override
    public List<NaruAuditRecord> listRefs(int limit, Instant since) {
        List<NaruAuditRecord> out = new ArrayList<>();
        for (NElement e : rawRecords()) {
            NaruAuditRecord r = NaruAuditRecord.of(e, isMissing(e));
            if (since != null && (r.timestamp() == null || r.timestamp().isBefore(since))) {
                continue;
            }
            out.add(r);
        }
        out.sort(Comparator.comparing(NaruAuditRecord::timestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return limit < 0 ? out : out.subList(0, Math.min(limit, out.size()));
    }

    /** The blob a reference names, or null when it is gone or the record has none. */
    private NElement body(String ref) {
        return ref == null ? null : resolveBody(ref).orNull();
    }

    /**
     * Whether a record's blobs are gone.
     *
     * <p>Checked per record rather than once, because the log is pruned in place: a record
     * written last year can be the only one pointing at a blob that a later prune removed,
     * while the record next to it still resolves.
     */
    private boolean isMissing(NElement element) {
        if (!element.isAnyObject()) {
            return false;
        }
        for (String key : new String[]{"requestBodyRef", "responseBodyRef"}) {
            String ref = element.asObject().get().getStringValue(key).orNull();
            if (ref != null && !blobs.exists(ref)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public NOptional<NElement> resolveBody(String ref) {
        if (ref == null || !blobs.exists(ref)) {
            return NOptional.ofEmpty();
        }
        return NOptional.ofNullable(blobs.get(ref));
    }

    @Override
    public long payloadBytes() {
        return blobs.totalBytes();
    }

    @Override
    public NaruAuditPruneResult prune(NaruAuditRetention retention) {
        List<NElement> records = rawRecords();
        List<NaruAuditRecord> parsed = new ArrayList<>();
        for (NElement e : records) {
            parsed.add(NaruAuditRecord.of(e, false));
        }
        // newest first, so a count-based limit keeps the most recent
        parsed.sort(Comparator.comparing(NaruAuditRecord::timestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<Long, Integer> keptPerTask = new LinkedHashMap<>();
        Map<Long, Integer> droppedPerTask = new LinkedHashMap<>();
        List<NaruAuditRecord> survivors = new ArrayList<>();
        for (NaruAuditRecord r : parsed) {
            if (!retention.keeps(r.timestamp())) {
                droppedPerTask.merge(r.taskId(), 1, Integer::sum);
                continue;
            }
            int limit = retention.maxRecordsForTask().containsKey(r.taskId())
                    ? retention.maxRecordsForTask().get(r.taskId())
                    : retention.maxRecordsPerTask();
            if (limit >= 0) {
                int kept = keptPerTask.getOrDefault(r.taskId(), 0);
                if (kept >= limit) {
                    droppedPerTask.merge(r.taskId(), 1, Integer::sum);
                    continue;
                }
                keptPerTask.merge(r.taskId(), 1, Integer::sum);
            }
            survivors.add(r);
        }

        int dropped = parsed.size() - survivors.size();
        if (dropped > 0) {
            // rewrite the log first, atomically: the old file stays whole until the new one
            // is complete, so a crash mid-prune loses the prune, not the log
            StringBuilder sb = new StringBuilder();
            for (NaruAuditRecord r : survivors) {
                sb.append(NaruContentAddressedStore.compact(r.toElement())).append("\n\n");
            }
            try {
                NaruFileIo.mkdirs(auditDir);
                NaruFileIo.writeAtomic(logFile(), sb.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // and only then collect blobs. The survivors are the roots, so a blob is removed
        // only when nothing in the log still names it -- never the other way round.
        Set<String> roots = new LinkedHashSet<>();
        for (NaruAuditRecord r : survivors) {
            if (r.requestBodyRef() != null) {
                roots.add(r.requestBodyRef());
            }
            if (r.responseBodyRef() != null) {
                roots.add(r.responseBodyRef());
            }
        }
        NaruContentAddressedStore.SweepResult swept = blobs.sweep(roots, SWEEP_GRACE);
        Map<String, Integer> droppedStrings = new LinkedHashMap<>();
        droppedPerTask.forEach((k, v) -> droppedStrings.put(String.valueOf(k), v));
        return new NaruAuditPruneResult(dropped, swept.deleted(), swept.bytes(), swept.reachable(),
                droppedStrings);
    }
}