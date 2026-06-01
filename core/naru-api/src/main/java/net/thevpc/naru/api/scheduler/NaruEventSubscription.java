package net.thevpc.naru.api.scheduler;

public class NaruEventSubscription {
    private final String routineName;
    private final NaruEventFilter filter;
    private final boolean once;

    public NaruEventSubscription(String routineName,
                                 NaruEventFilter filter,
                                 boolean once) {
        this.routineName = routineName;
        this.filter = filter;
        this.once = once;
    }

    public String routineName() { return routineName; }
    public NaruEventFilter filter() { return filter; }
    public boolean once() { return once; }
}