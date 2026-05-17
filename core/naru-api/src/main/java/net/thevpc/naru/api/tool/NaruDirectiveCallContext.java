package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruSession;

public interface NaruDirectiveCallContext {
    String name();
    String argument();
    NaruSession session();
}
