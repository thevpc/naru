package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.scheduler.NaruEvent;

import java.util.ArrayList;
import java.util.List;

class NaruSessionListenerDelegate implements NaruSessionListener {
    private final List<NaruSessionListener> ad;

    public NaruSessionListenerDelegate(List<NaruSessionListener> ad) {
        this.ad = new ArrayList<>(ad);
    }

    @Override
    public void onEventAppended(NaruEvent newEvent) {
        for (NaruSessionListener li : ad) {
            li.onEventAppended(newEvent);
        }
    }

    @Override
    public void sessionStarted(NaruSession session) {
        for (NaruSessionListener li : ad) {
            li.sessionStarted(session);
        }
    }

    @Override
    public void sessionStopped(NaruSession session) {
        for (NaruSessionListener li : ad) {
            li.sessionStopped(session);
        }
    }

    @Override
    public void onSessionReloaded(NaruSession session) {
        for (NaruSessionListener li : ad) {
            li.onSessionReloaded(session);
        }
    }
}
