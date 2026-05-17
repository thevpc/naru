package net.thevpc.naru.api.agent;

import java.time.Instant;

public class NaruSessionInfo {
    private String uuid;
    private String name;
    private Instant creationDate;
    private Instant modificationDate;

    public String getUuid() {
        return uuid;
    }

    public NaruSessionInfo setUuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public String getName() {
        return name;
    }

    public NaruSessionInfo setName(String name) {
        this.name = name;
        return this;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public NaruSessionInfo setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
        return this;
    }

    public Instant getModificationDate() {
        return modificationDate;
    }

    public NaruSessionInfo setModificationDate(Instant modificationDate) {
        this.modificationDate = modificationDate;
        return this;
    }
}
