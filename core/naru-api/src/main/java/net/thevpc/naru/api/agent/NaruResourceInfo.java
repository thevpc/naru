package net.thevpc.naru.api.agent;

import java.time.Instant;

public class NaruResourceInfo {
    private String uuid;
    private String name;
    private NAruVisibility mode;
    private Instant creationInstant;
    private Instant modificationInstant;

    public NAruVisibility getMode() {
        return mode;
    }

    public NaruResourceInfo setVisibility(NAruVisibility mode) {
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

    public Instant getCreationInstant() {
        return creationInstant;
    }

    public NaruResourceInfo setCreationInstant(Instant creationInstant) {
        this.creationInstant = creationInstant;
        return this;
    }

    public Instant getModificationInstant() {
        return modificationInstant;
    }

    public NaruResourceInfo setModificationInstant(Instant modificationInstant) {
        this.modificationInstant = modificationInstant;
        return this;
    }
}
