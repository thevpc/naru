package net.thevpc.naru.api.scheduler;

public class NaruEvent {
    private final String type;
    private final Object data;
    private final long sourceTid; // who emitted it
    private final long timestamp;

    public NaruEvent(String type, Object data, long sourceTid) {
        this.type = type;
        this.data = data;
        this.sourceTid = sourceTid;
        this.timestamp = System.currentTimeMillis();
    }

    public String type() { return type; }
    public Object data() { return data; }
    public long sourceTid() { return sourceTid; }
    public long timestamp() { return timestamp; }
}
