package net.thevpc.naru.impl.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import net.thevpc.naru.api.scheduler.*;

public class NaruTaskInboxImpl implements NaruTaskInbox {
    private final long taskId;
    private final NaruSessionEventLog sessionLog;
    private final ConcurrentSkipListSet<Long> seqs = new ConcurrentSkipListSet<>();
    private final Set<Long> consumed = ConcurrentHashMap.newKeySet();
    private volatile long watermark = 0;

    public NaruTaskInboxImpl(long taskId, NaruSessionEventLog sessionLog) {
        this.taskId = taskId;
        this.sessionLog = sessionLog;
    }

    public int size() {
        return seqs.size();
    }

    public boolean isEmpty() {
        return seqs.isEmpty();
    }

    @Override
    public void push(long seq) {
        seqs.add(seq);
    }

    @Override
    public NaruEvent peek(NaruEventFilter filter) {
        for (long seq : seqs.tailSet(watermark)) {
            if (consumed.contains(seq)) continue;
            NaruEvent event = sessionLog.get(seq);
            if (event == null) {
                // dropped by GC, skip
                consumed.add(seq);
                advanceWatermark();
                continue;
            }
            if (filter == null || filter.test(event)) {
                return event;
            }
        }
        return null;
    }

    @Override
    public NaruEvent consume(long seq) {
        if (consumed.contains(seq)) return null;
        NaruEvent event = sessionLog.get(seq);
        if (event == null) {
            consumed.add(seq);
            advanceWatermark();
            return event;
        }
        consumed.add(seq);
        advanceWatermark();
        return event;
    }

    @Override
    public List<NaruEvent> drainMatching(NaruEventFilter filter) {
        List<NaruEvent> result = new ArrayList<>();
        for (long seq : seqs.tailSet(watermark)) {
            if (consumed.contains(seq)) continue;
            NaruEvent event = sessionLog.get(seq);
            if (event == null) {
                consumed.add(seq);
                continue;
            }
            if (filter == null || filter.test(event)) {
                consumed.add(seq);
                result.add(event);
            }
        }
        advanceWatermark();
        return result;
    }

    @Override
    public long watermark() {
        return watermark;
    }

    private void advanceWatermark() {
        for (long seq : seqs.tailSet(watermark)) {
            if (consumed.contains(seq)) {
                watermark = seq + 1;
            } else {
                break;
            }
        }
    }
}
