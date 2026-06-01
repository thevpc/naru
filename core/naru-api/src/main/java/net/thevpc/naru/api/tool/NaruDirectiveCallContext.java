package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruTask;

public interface NaruDirectiveCallContext {
    String name();
    String argument();
    NaruTask task();
}
