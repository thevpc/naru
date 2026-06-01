package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class NaruDirectiveCallContextImpl implements NaruDirectiveCallContext {
    private final String name;
    private final String argument;
    private final NaruTask task;

    public NaruDirectiveCallContextImpl(String name, String argument, NaruTask task) {
        this.name = name;
        this.argument = argument;
        this.task = task;
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
    public NaruTask task() {
        return task;
    }
}
