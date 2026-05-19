package net.thevpc.naru.api.agent;

import java.time.Instant;

public class NaruResourceInfo {
    private String uuid;
    private String name;
    private NAruVisibility mode;
    private Instant creationDate;
    private Instant modificationDate;

    public NAruVisibility getMode() {
        return mode;
    }

    public NaruResourceInfo setMode(NAruVisibility mode) {
        this.mode = mode;
        return this;
    }


    public String getUuid() {
        return uuid;
    }

    public NaruResourceInfo setUuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public String getName() {
        return name;
    }

    public NaruResourceInfo setName(String name) {
        this.name = name;
        return this;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public NaruResourceInfo setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
        return this;
    }

    public Instant getModificationDate() {
        return modificationDate;
    }

    public NaruResourceInfo setModificationDate(Instant modificationDate) {
        this.modificationDate = modificationDate;
        return this;
    }
}
