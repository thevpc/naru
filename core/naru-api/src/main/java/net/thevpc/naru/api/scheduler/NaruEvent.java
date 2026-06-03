package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;

import java.util.Map;
import java.util.Set;

public class NaruEvent {
    private final String type;
    private final Map<String,Object> args;
    private final long sourceTid; // who emitted it
    private final long sourcePid; // who emitted it
    private final long timestamp;
    private final Set<NaruEventRouting> routing;
    private transient boolean marked = false;
    public NaruEvent(String type, Map<String,Object> args, long sourceTid,long sourcePid,Set<NaruEventRouting> routing) {
        this.type = type;
        this.args = args;
        this.sourceTid = sourceTid;
        this.sourcePid = sourcePid;
        this.timestamp = System.currentTimeMillis();
        this.routing = routing;
    }

    public Set<NaruEventRouting> routing() {
        return routing;
    }

    public boolean isMarked() {
        return marked;
    }

    public NaruEvent setMarked(boolean marked) {
        this.marked = marked;
        return this;
    }

    public String type() { return type; }
    public Map<String, Object> args() { return args; }
    public long sourceTid() { return sourceTid; }
    public long sourcePid() { return sourcePid; }
    public long timestamp() { return timestamp; }
}
