package net.thevpc.naru.api.scheduler;

import java.util.Map;

public class NaruEventSubscription {
    private final String routineName;
    private final NaruEventFilter filter;
    private final boolean once;
    private final Map<String,Object> args;

    public NaruEventSubscription(String routineName,
                                 Map<String,Object> args,
                                 NaruEventFilter filter,
                                 boolean once) {
        this.routineName = routineName;
        this.filter = filter;
        this.args = args;
        this.once = once;
    }

    public Map<String, Object> args() {
        return args;
    }

    public String routineName() { return routineName; }
    public NaruEventFilter filter() { return filter; }
    public boolean once() { return once; }
}