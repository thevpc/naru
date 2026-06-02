package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.task.NaruTask;

public interface NaruDirectiveCallContext {
    String name();
    String argument();
    NaruTask task();
}
