package net.thevpc.naru.api.scheduler;

import java.util.List;

public interface NaruSessionEventLog {
    void append(NaruEvent event);
    List<NaruEvent> scan(long fromSeq, NaruEventFilter filter);

    void markConsumed(long seq, long tid);

    void drop(long seq);

    long currentSeq();

    NaruEvent get(long seq);
}
