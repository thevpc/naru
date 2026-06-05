package net.thevpc.naru.api.scheduler;

import java.util.List;
import java.util.function.Predicate;

public interface NaruSessionEventLog {
    void append(NaruEvent event);
    List<NaruEvent> scan(long fromSeq, Predicate<NaruEvent> filter);

    void markConsumed(long seq, long tid);

    void drop(long seq);

    long currentSeq();

    NaruEvent get(long seq);
}
