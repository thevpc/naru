package net.thevpc.naru.api.agent;

/** One input submitted to a session handle. */
public record NaruInput(String text, String requestId, String idempotencyKey) {
    public static NaruInput of(String text) { return new NaruInput(text, null, null); }
    public NaruInput requestId(String value) { return new NaruInput(text, value, idempotencyKey); }
    public NaruInput idempotencyKey(String value) { return new NaruInput(text, requestId, value); }
}
