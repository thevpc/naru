package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.util.NaruLongHashSet;
import net.thevpc.nuts.elem.*;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class NaruEvent implements NToElement {

    public static final String TASK_TERMINATED = "task-terminated";
    /**
     * Fired when a task is spawned: a child task was created and its resolved spawn sets
     * recorded. The payload carries the resolved sets and the source of each item
     * (default / flag / policy / contract) as provenance text; the event itself grants
     * nothing — observing it never confers a tag, exclusion or skill.
     */
    public static final String TASK_SPAWNED = "task-spawn";
    /**
     * Fired once per session, when the session starts serving. This is the trigger for the
     * workspace-level {@code init.naru}; it is deliberately not fired by {@code /cd}, which
     * is pure navigation (WP7/O2).
     */
    public static final String SESSION_START = "session-start";
    /**
     * Fired when the session directory changes through {@code /sessiondir}. The
     * payload carries the old and new directories. Task selections and tag sets are kept;
     * availability is re-resolved by each feature, and a loaded skill that disappeared is
     * reported by {@code /skill doctor} rather than silently dropped.
     */
    public static final String SESSION_DIR_CHANGE = "sessiondir-change";
    /**
     * Fired when a session's name changes: an automatic title landed, a person renamed it,
     * or {@code /session title} regenerated it. The payload carries {@code uuid},
     * {@code oldName}, {@code newName} and {@code source} (the {@link
     * net.thevpc.naru.api.agent.NaruSessionNameSource} that now owns the name).
     *
     * <p>A name is display state, so a host that renders the session list can settle the
     * changed row from this event instead of reloading the whole tree -- which is the only
     * reason it exists. The placeholder minted when a session is first named is not
     * announced: it is not a change of label, it is the label appearing for the first time,
     * and a listener that treated it as a rename would flash a row into place for a session
     * the user has not looked at yet.
     */
    public static final String SESSION_RENAMED = "session-renamed";
    private final long seq;
    private final String name;
    private final Map<String, NElement> payload;
    private final long sourceTid;
    private final long sourcePid;
    private final Instant firedAt;
    private final NaruEventTarget target;
    private final NaruRetentionPolicy retentionPolicy;

    // stats — mutable, thread-safe via synchronized accessors
    private final NaruLongHashSet visitedTids = new NaruLongHashSet(4);
    private final NaruLongHashSet consumedTids = new NaruLongHashSet(4);

    @NCreator
    public NaruEvent(
            @NProperty("seq") long seq,
            @NProperty("name") String name,
            @NProperty("payload") Map<String, NElement> payload,
            @NProperty("sourceTid") long sourceTid,
            @NProperty("sourcePid") long sourcePid,
            @NProperty("firedAt") Instant firedAt,
            @NProperty("target") NaruEventTarget target,
            @NProperty("retentionPolicy") NaruRetentionPolicy retentionPolicy,
            @NProperty("visitedTids") long[] visitedTids,
            @NProperty("consumedTids") long[] consumedTids
    ) {
        this.seq = seq;
        this.name = name;
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        this.sourceTid = sourceTid;
        this.sourcePid = sourcePid;
        this.firedAt = firedAt;
        this.target = target;
        this.retentionPolicy = retentionPolicy;
        for (long v : visitedTids) {
            this.visitedTids.add(v);
        }
        for (long v : consumedTids) {
            this.consumedTids.add(v);
        }
    }

    @Override
    public NElement toElement() {
        return NElement.defaultToNamedElement(this);
    }

    public NaruEvent(
            String name,
            Map<String, NElement> payload,
            long sourceTid,
            long sourcePid,
            Instant firedAt,
            NaruEventTarget target,
            NaruRetentionPolicy retentionPolicy) {
        this.seq = -1; //will be set later
        this.name = name;
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        this.sourceTid = sourceTid;
        this.sourcePid = sourcePid;
        this.firedAt = firedAt;
        this.target = target;
        this.retentionPolicy = retentionPolicy;
    }

    public NaruEvent withSeq(long newSeq) {
        return new NaruEvent(newSeq, name, payload, sourceTid, sourcePid, firedAt, target, retentionPolicy, visitedTids(), consumedTids());
    }

    // identity
    public long seq() {
        return seq;
    }

    public String name() {
        return name;
    }

    /**
     * Whether the name is the spawn event, under either spelling (the current
     * {@value #TASK_SPAWNED}.
     */
    public static boolean isTaskSpawned(String name) {
        return TASK_SPAWNED.equals(name);
    }

    public Map<String, NElement> payload() {
        return payload;
    }

    public NElement payload(String key) {
        return payload.get(key);
    }

    // provenance
    public long sourceTid() {
        return sourceTid;
    }

    public long sourcePid() {
        return sourcePid;
    }

    public Instant firedAt() {
        return firedAt;
    }

    public NaruEventTarget target() {
        return target;
    }

    public NaruRetentionPolicy retentionPolicy() {
        return retentionPolicy;
    }

    // stats
    public synchronized void markVisited(long tid) {
        visitedTids.add(tid);
    }

    public synchronized void markConsumed(long tid) {
        visitedTids.add(tid);
        consumedTids.add(tid);
    }

    public synchronized boolean isVisitedBy(long tid) {
        return visitedTids.contains(tid);
    }

    public synchronized boolean isConsumedBy(long tid) {
        return consumedTids.contains(tid);
    }

    public synchronized int visitedCount() {
        return visitedTids.size();
    }

    public synchronized int consumedCount() {
        return consumedTids.size();
    }

    public synchronized long[] visitedTids() {
        return visitedTids.toArray();
    }

    public synchronized long[] consumedTids() {
        return consumedTids.toArray();
    }

    public synchronized boolean isVisitedTids(long id) {
        return visitedTids.contains(id);
    }

    public synchronized boolean isConsumedTid(long id) {
        return consumedTids.contains(id);
    }

    // retention
    public boolean shouldDrop() {
        return retentionPolicy.shouldDrop(this);
    }

    public long nextCheckMillis() {
        return retentionPolicy.nextCheckMillis(this);
    }

    @Override
    public String toString() {
        return "NaruEvent{seq=" + seq
                + ", name='" + name + "'"
                + ", source=" + sourceTid
                + ", firedAt=" + firedAt
                + ", visited=" + visitedCount()
                + ", consumed=" + consumedCount() + "}";
    }

}
