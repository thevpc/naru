package net.thevpc.naru.api.scheduler;

import java.util.List;

public interface NaruTaskInbox {
    int size();
    void push(long seq);

    NaruEvent peek(NaruEventFilter filter);

    NaruEvent consume(long seq);
    NaruEvent consume(NaruEventFilter filter);

    List<NaruEvent> drainMatching(NaruEventFilter filter);

    long watermark();
}
