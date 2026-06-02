package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;

import java.util.Collections;
import java.util.List;

public record StaticToolset(String id, List<NaruTool> tools) implements NaruToolset {

    public StaticToolset(String id, List<NaruTool> tools) {
        this.id = id;
        this.tools = Collections.unmodifiableList(tools);
    }

    @Override
    public void open(NaruSession session) { /* nothing */ }

    @Override
    public void close() { /* nothing */ }
}
