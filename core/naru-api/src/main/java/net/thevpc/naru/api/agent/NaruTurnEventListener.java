package net.thevpc.naru.api.agent;

@FunctionalInterface
public interface NaruTurnEventListener {
    void onEvent(NaruTurnEvent event);
}
