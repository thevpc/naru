package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class NaruDirectiveCallContextImpl implements NaruDirectiveCallContext {
    private final String name;
    private final String argument;
    private final NaruSessionContext session;

    public NaruDirectiveCallContextImpl(String name, String argument, NaruSessionContext session) {
        this.name = name;
        this.argument = argument;
        this.session = session;
    }

    @Override
    public String argument() {
        return argument;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public NaruSessionContext session() {
        return session;
    }
}
