package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class NaruDirectiveCallContextImpl implements NaruDirectiveCallContext {
    private final String name;
    private final String argument;
    private final NaruSession session;

    public NaruDirectiveCallContextImpl(String name, String argument, NaruSession session) {
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
    public NaruSession session() {
        return session;
    }
}
