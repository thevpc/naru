package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSource;

import java.util.List;

public class NaruMessagesBySource {
    private String sourceName;
    private NaruSource source;
    private List<NaruMessage> messages;

    public NaruMessagesBySource(String sourceName, NaruSource source, List<NaruMessage> messages) {
        this.sourceName = sourceName;
        this.source = source;
        this.messages = messages;
    }

    public String getSourceName() {
        return sourceName;
    }

    public NaruSource getSource() {
        return source;
    }

    public List<NaruMessage> getMessages() {
        return messages;
    }
}
