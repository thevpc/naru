package net.thevpc.naru.impl.scheduler;

import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruEventFilter;
import net.thevpc.naru.api.scheduler.NaruSessionEventLog;
import net.thevpc.naru.api.scheduler.NaruEventRouting;
import net.thevpc.naru.impl.agent.NaruSessionImpl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class NaruSessionEventLogImpl implements NaruSessionEventLog {
    private final Map<Long, NaruEvent> log = new ConcurrentSkipListMap<>();
    private final AtomicLong seqCounter = new AtomicLong(0);
    private final NaruSessionImpl session;

    public NaruSessionEventLogImpl(NaruSessionImpl session) {
        this.session = session;
    }

    public void append(NaruEvent event) {
        log.put(event.seq(), event);
    }

//    public NaruEvent append(String type, Map<String, Object> args, long sourceTid, long sourcePid, Set<NaruEventRouting> routing) {
//        long n = seqCounter.incrementAndGet();
//        NaruEvent e = new NaruEvent(
//                type, args, sourceTid, sourcePid, routing
//        );
//        log.put(n, e);
//        return e;
//    }

    @Override
    public List<NaruEvent> scan(long fromSeq, NaruEventFilter filter) {
        return Collections.emptyList();
    }

    @Override
    public void markConsumed(long seq, long tid) {

    }

    @Override
    public void drop(long seq) {
        log.remove(seq);
    }

    @Override
    public long currentSeq() {
        return seqCounter.get();
    }
}
