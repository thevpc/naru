package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;

public interface NaruTaskSchedulerView {
    void status(NaruTaskStatus newStatus);
    void doing(NaruStatement doing);
    void deliverInput(String line);

    NMsg pendingPrompt();
}
