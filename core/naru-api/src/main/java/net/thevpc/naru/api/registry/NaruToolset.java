package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;

import java.util.List;

public interface NaruToolset extends AutoCloseable {
    String id();

    List<NaruTool> tools();

    void open(NaruSession session);

    default NaruHibernationPolicy hibernationPolicy() { return NaruHibernationPolicy.PINS; }
    default boolean hasLiveResource(NaruSession session) { return false; }
    default void onHibernate(NaruSession session) { }
    default void onResume(NaruSession session) { }

    void close() throws Exception;
}
