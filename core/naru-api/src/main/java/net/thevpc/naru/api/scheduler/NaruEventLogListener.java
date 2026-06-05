package net.thevpc.naru.api.scheduler;

public interface NaruEventLogListener {
    void onEventAppended(NaruEvent newEvent) ;
}
