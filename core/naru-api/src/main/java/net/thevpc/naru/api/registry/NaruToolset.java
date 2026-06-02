package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;

import java.util.List;

public interface NaruToolset extends AutoCloseable {
    String id();

    List<NaruTool> tools();

    void open(NaruSession session);

    void close() throws Exception;
}