package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.nuts.text.NMsg;

public interface NaruTaskSchedulerView {
    void status(NaruTaskStatus newStatus);

    void deliverInput(String line);

    NMsg pendingPrompt();
}
