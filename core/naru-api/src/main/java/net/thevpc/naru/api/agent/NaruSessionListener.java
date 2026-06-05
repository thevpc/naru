package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.scheduler.NaruEvent;

public interface NaruSessionListener {
    void onEventAppended(NaruEvent newEvent);
    void sessionStarted(NaruSession session);
    void sessionStopped(NaruSession session);

    void onSessionReloaded(NaruSession naruSession);
}
