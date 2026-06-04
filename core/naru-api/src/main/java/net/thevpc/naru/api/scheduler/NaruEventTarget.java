package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;

public interface NaruEventTarget {
    boolean matches(NaruTask candidate);
}
