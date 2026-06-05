package net.thevpc.naru.impl.scheduler;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.agent.NaruSessionImpl;
import net.thevpc.nuts.text.NMsg;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class NaruSessionEventLogImpl implements NaruSessionEventLog {
    private final ConcurrentSkipListMap<Long, NaruEvent> log = new ConcurrentSkipListMap<>();
    private final AtomicLong seqCounter = new AtomicLong(0);
    private final NaruEventLogListener listener;

    public NaruSessionEventLogImpl(NaruEventLogListener listener) {
        this.listener = listener;
    }

    public void append(NaruEvent event) {
        NaruEvent newEvent = event.withSeq(seqCounter.incrementAndGet());
        log.put(newEvent.seq(), newEvent);
        listener.onEventAppended(newEvent);
    }

    @Override
    public NaruEvent get(long seq) {
        return log.get(seq);
    }

    @Override
    public List<NaruEvent> scan(long fromSeq, Predicate<NaruEvent> filter) {
        return log.tailMap(fromSeq).values().stream()
                .filter(filter != null ? filter : e -> true)
                .collect(Collectors.toList());
    }

    @Override
    public void markConsumed(long seq, long tid) {
        NaruEvent u = log.get(seq);
        if(u!=null){
            u.markConsumed(tid);
        }
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
