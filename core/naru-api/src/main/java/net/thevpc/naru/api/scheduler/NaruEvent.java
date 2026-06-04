package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.util.NaruLongHashSet;
import net.thevpc.nuts.elem.NElement;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class NaruEvent {

    private final long seq;
    private final String name;
    private final Map<String, Object> payload;
    private final long sourceTid;
    private final Instant firedAt;
    private final NaruEventTarget target;
    private final NaruRetentionPolicy retentionPolicy;

    // stats — mutable, thread-safe via synchronized accessors
    private final NaruLongHashSet visitedTids = new NaruLongHashSet(4);
    private final NaruLongHashSet consumedTids = new NaruLongHashSet(4);

    public NaruEvent(
            long seq,
            String name,
            Map<String, Object> payload,
            long sourceTid,
            Instant firedAt,
            NaruEventTarget target,
            NaruRetentionPolicy retentionPolicy) {
        this.seq = seq;
        this.name = name;
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        this.sourceTid = sourceTid;
        this.firedAt = firedAt;
        this.target = target;
        this.retentionPolicy = retentionPolicy;
    }

    // identity
    public long seq() {
        return seq;
    }

    public String name() {
        return name;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public Object payload(String key) {
        return payload.get(key);
    }

    // provenance
    public long sourceTid() {
        return sourceTid;
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
