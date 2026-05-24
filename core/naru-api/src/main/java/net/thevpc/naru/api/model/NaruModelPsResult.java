package net.thevpc.naru.api.model;

import java.time.Instant;

public class NaruModelPsResult {
    private final NaruModelKey model;
    private final long size;
    private final Instant expiresAt;
    private final long sizeVram;

    public NaruModelPsResult(NaruModelKey model, long size, Instant expiresAt, long sizeVram) {
        this.model = model;
        this.size = size;
        this.expiresAt = expiresAt;
        this.sizeVram = sizeVram;
    }

    public NaruModelPsResult withProvider(String provider) {
        return new NaruModelPsResult(new NaruModelKey(provider, model.model()), size, expiresAt, sizeVram);
    }

    public NaruModelKey getModel() {
        return model;
    }

    public long getSize() {
        return size;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getSizeVram() {
        return sizeVram;
    }
}
