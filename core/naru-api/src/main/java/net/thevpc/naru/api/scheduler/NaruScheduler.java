package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.agent.NaruSession;

public interface NaruScheduler {
    // --- lifecycle ---
    void start();

    void awaitTermination();

    void awaitTermination(long timeout);

    void shutdown();            // graceful — finish current tick then stop

    void kill();                // immediate — interrupt all workers

    // --- scheduler-level hold ---
    void hold();                // synchronous — blocks until all workers idle

    void resume();

    void tick(long tid);        // force one tick when task is held

    // --- step operations ---
    void stepAny();

    void step(long... tids);

    void stepAll();

    // --- mode ---
    void throttleDelay(long ms);

    // --- introspection ---
    NaruSchedulerStatus status();

    int workerCount();

    int activeCount();

    int readyCount();

    void runRetention();

    void runBlockedDrain();
}
