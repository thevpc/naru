package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruSessionContext;

public interface NaruDirectiveCallContext {
    String name();
    String argument();
    NaruSessionContext session();
}
