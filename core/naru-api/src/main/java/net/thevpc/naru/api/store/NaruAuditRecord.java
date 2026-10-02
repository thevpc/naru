package net.thevpc.naru.api.store;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One provider call attempt, as recorded in the audit log.
 *
 * <p>The reason this is a type and not a {@code Map<String,Object>} is the payload. A
 * record's {@code requestBody} is the entire conversation as it was sent, so on a session
 * that has run a hundred turns the audit log holds a hundred copies of those hundred
 * turns -- an amount of data that grows with the square of the session and is re-read in
 * full by anything that displays the log.
 *
 * <p>So the body is stored once, as a blob, and the record keeps the reference. Two records
 * with the same body share the blob, and a body that is a prefix of a longer one does not
 * help, but the conversation as a whole is stored exactly once no matter how many calls
 * carry it.
 *
 * <p>Records written before the split carry their body inline, and are read exactly as they
 * were. They are not rewritten on read: the audit log is an append-only record of what
 * actually happened, and rewriting history to make it tidier would be a worse lie than an
 * untidy file.
 */
public class NaruAuditRecord implements NToElement {

    private final String id;
    private final Instant timestamp;
    private final long taskId;
    private final String taskName;
    private final String sessionUuid;
    private final String provider;
    private final String model;
    private final String url;
    private final String method;
    private final Map<String, String> requestHeaders;
    private final Map<String, String> responseHeaders;
    private final int attempt;
    private final long durationMs;
    private final Integer statusCode;
    private final String statusMessage;
    private final String errorType;
    private final String errorMessage;
    /**
     * Content-addressed reference to the request body, or null when the body was small
     * enough to be worth inlining, or when it has been pruned away.
     */
    private final String requestBodyRef;
    private final String responseBodyRef;
    /** Bodies still carried inline, as written by older versions. */
    private final NElement requestBodyInline;
    private final NElement responseBodyInline;
    /**
     * Bodies fetched from the blob store on this read.
     *
     * <p>Populated only by {@link NaruAuditStore#read}, never written back: a record read
     * with its bodies resolved must still serialise as the refs it was stored as, or
     * rewriting the log during a prune would inline the conversation a hundred times over.
     */
    private final NElement requestBodyResolved;
    private final NElement responseBodyResolved;
    /** True when a ref is known to no longer resolve. */
    private final boolean pruned;

    private NaruAuditRecord(Builder b) {
        this.id = b.id;
        this.timestamp = b.timestamp;
        this.taskId = b.taskId;
        this.taskName = b.taskName;
        this.sessionUuid = b.sessionUuid;
        this.provider = b.provider;
        this.model = b.model;
        this.url = b.url;
        this.method = b.method;
        this.requestHeaders = b.requestHeaders;
        this.responseHeaders = b.responseHeaders;
        this.attempt = b.attempt;
        this.durationMs = b.durationMs;
        this.statusCode = b.statusCode;
        this.statusMessage = b.statusMessage;
        this.errorType = b.errorType;
        this.errorMessage = b.errorMessage;
        this.requestBodyRef = b.requestBodyRef;
        this.responseBodyRef = b.responseBodyRef;
        this.requestBodyInline = b.requestBodyInline;
        this.responseBodyInline = b.responseBodyInline;
        this.requestBodyResolved = b.requestBodyResolved;
        this.responseBodyResolved = b.responseBodyResolved;
        this.pruned = b.pruned;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads a record as it was written, whether it holds refs or inline bodies.
     *
     * <p>{@code missing} is what the caller found when it went looking for the blobs this
     * record refers to. Passing true marks the record pruned, which is information the
     * caller needs and cannot recompute: the ref is still there, and only the store knows
     * the blob is gone.
     */
    public static NaruAuditRecord of(NElement element, boolean missing) {
        NObjectElement o = element.asObject().get();
        Builder b = new Builder();
        b.id = o.getStringValue("id").orNull();
        b.timestamp = o.getInstantValue("timestamp").orNull();
        b.taskId = o.getLongValue("taskId").orElse(0L);
        b.taskName = o.getStringValue("taskName").orNull();
        b.sessionUuid = o.getStringValue("sessionUuid").orNull();
        b.provider = o.getStringValue("provider").orNull();
        b.model = o.getStringValue("model").orNull();
        b.url = o.getStringValue("url").orNull();
        b.method = o.getStringValue("method").orNull();
        b.requestHeaders = readMap(o, "requestHeaders");
        b.responseHeaders = readMap(o, "responseHeaders");
        b.attempt = o.getIntValue("attempt").orElse(0);
        b.durationMs = o.getLongValue("durationMs").orElse(0L);
        Integer sc = o.getIntValue("statusCode").orNull();
        b.statusCode = sc;
        b.statusMessage = o.getStringValue("statusMessage").orNull();
        NElement err = o.get("error").orNull();
        if (err != null && err.isAnyObject()) {
            b.errorType = err.asObject().get().getStringValue("type").orNull();
            b.errorMessage = err.asObject().get().getStringValue("message").orNull();
        }
        b.requestBodyRef = o.getStringValue("requestBodyRef").orNull();
        b.responseBodyRef = o.getStringValue("responseBodyRef").orNull();
        NElement rb = o.get("requestBody").orNull();
        if (rb != null && !rb.isNull()) {
            b.requestBodyInline = rb;
        }
        NElement sb = o.get("responseBody").orNull();
        if (sb != null && !sb.isNull()) {
            b.responseBodyInline = sb;
        }
        b.pruned = missing;
        return new NaruAuditRecord(b);
    }

    private static Map<String, String> readMap(NObjectElement o, String key) {
        NElement e = o.get(key).orNull();
        if (e == null || !e.isAnyObject()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (NElement child : e.asObject().get().children()) {
            if (child.isNamedPair()) {
                m.put(child.asPair().get().key().asStringValue().orNull(),
                        child.asPair().get().value().asStringValue().orElse(""));
            }
        }
        return m;
    }

    public String id() {
        return id;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public long taskId() {
        return taskId;
    }

    public String taskName() {
        return taskName;
    }

    public String sessionUuid() {
        return sessionUuid;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public String url() {
        return url;
    }

    public String method() {
        return method;
    }

    public Map<String, String> requestHeaders() {
        return requestHeaders;
    }

    public Map<String, String> responseHeaders() {
        return responseHeaders;
    }

    public int attempt() {
        return attempt;
    }

    public long durationMs() {
        return durationMs;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public String errorType() {
        return errorType;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String requestBodyRef() {
        return requestBodyRef;
    }

    public String responseBodyRef() {
        return responseBodyRef;
    }

    public NElement requestBodyInline() {
        return requestBodyInline;
    }

    public NElement responseBodyInline() {
        return responseBodyInline;
    }

    /**
     * The request body as it was sent, or null if it has been pruned away.
     *
     * <p>Works the same whether the body was stored inline or as a reference: callers
     * display a body, and should not have to know which. A record that has been read
     * through {@link NaruAuditStore#read} has its blob fetched already; one read through
     * {@link NaruAuditStore#listRefs} returns only what is inline.
     */
    public NElement requestBody() {
        return requestBodyInline != null ? requestBodyInline : requestBodyResolved;
    }

    /** The response body, or null if pruned. See {@link #requestBody()}. */
    public NElement responseBody() {
        return responseBodyInline != null ? responseBodyInline : responseBodyResolved;
    }

    public boolean pruned() {
        return pruned;
    }

    /**
     * A copy of this record with bodies fetched from the blob store.
     *
     * <p>Used by {@link NaruAuditStore#read}. Marked pruned when a ref does not resolve,
     * so a caller can tell "no body was recorded" from "the body is gone".
     */
    public NaruAuditRecord withResolvedBodies(NElement requestBody, NElement responseBody, boolean pruned) {
        Builder b = toBuilder();
        b.requestBodyResolved = requestBody;
        b.responseBodyResolved = responseBody;
        b.pruned = pruned;
        return new NaruAuditRecord(b);
    }

    /** A builder seeded from this record, for the copies above. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = id;
        b.timestamp = timestamp;
        b.taskId = taskId;
        b.taskName = taskName;
        b.sessionUuid = sessionUuid;
        b.provider = provider;
        b.model = model;
        b.url = url;
        b.method = method;
        b.requestHeaders = requestHeaders;
        b.responseHeaders = responseHeaders;
        b.attempt = attempt;
        b.durationMs = durationMs;
        b.statusCode = statusCode;
        b.statusMessage = statusMessage;
        b.errorType = errorType;
        b.errorMessage = errorMessage;
        b.requestBodyRef = requestBodyRef;
        b.responseBodyRef = responseBodyRef;
        b.requestBodyInline = requestBodyInline;
        b.responseBodyInline = responseBodyInline;
        b.pruned = pruned;
        return b;
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("id", id);
        o.set("timestamp", NElement.ofInstant(timestamp));
        o.set("taskId", taskId);
        o.set("taskName", taskName);
        o.set("sessionUuid", sessionUuid);
        o.set("provider", provider);
        o.set("model", model);
        o.set("url", url);
        o.set("method", method);
        o.set("requestHeaders", headersElement(requestHeaders));
        o.set("requestBodyRef", requestBodyRef);
        o.set("requestBody", requestBodyInline);
        o.set("attempt", attempt);
        o.set("durationMs", durationMs);
        o.set("statusCode", statusCode);
        o.set("statusMessage", statusMessage);
        o.set("responseHeaders", headersElement(responseHeaders));
        o.set("responseBodyRef", responseBodyRef);
        o.set("responseBody", responseBodyInline);
        if (errorType != null || errorMessage != null) {
            o.set("error", NObjectElementBuilder.of()
                    .set("type", errorType)
                    .set("message", errorMessage)
                    .build());
        }
        if (pruned) {
            o.set("pruned", true);
        }
        return o.build();
    }

    private static NElement headersElement(Map<String, String> headers) {
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            b.add(e.getKey(), NElement.ofString(e.getValue() == null ? "" : e.getValue()));
        }
        return b.build();
    }

    public static class Builder {
        private String id;
        private Instant timestamp;
        private long taskId;
        private String taskName;
        private String sessionUuid;
        private String provider;
        private String model;
        private String url;
        private String method;
        private Map<String, String> requestHeaders = new LinkedHashMap<>();
        private Map<String, String> responseHeaders = new LinkedHashMap<>();
        private int attempt;
        private long durationMs;
        private Integer statusCode;
        private String statusMessage;
        private String errorType;
        private String errorMessage;
        private String requestBodyRef;
        private String responseBodyRef;
        private NElement requestBodyInline;
        private NElement responseBodyInline;
        private NElement requestBodyResolved;
        private NElement responseBodyResolved;
        private boolean pruned;

        public Builder id(String v) { this.id = v; return this; }

        public Builder timestamp(Instant v) { this.timestamp = v; return this; }

        public Builder taskId(long v) { this.taskId = v; return this; }

        public Builder taskName(String v) { this.taskName = v; return this; }

        public Builder sessionUuid(String v) { this.sessionUuid = v; return this; }

        public Builder provider(String v) { this.provider = v; return this; }

        public Builder model(String v) { this.model = v; return this; }

        public Builder url(String v) { this.url = v; return this; }

        public Builder method(String v) { this.method = v; return this; }

        public Builder requestHeaders(Map<String, String> v) { this.requestHeaders = v == null ? new LinkedHashMap<>() : v; return this; }

        public Builder responseHeaders(Map<String, String> v) { this.responseHeaders = v == null ? new LinkedHashMap<>() : v; return this; }

        public Builder attempt(int v) { this.attempt = v; return this; }

        public Builder durationMs(long v) { this.durationMs = v; return this; }

        public Builder statusCode(Integer v) { this.statusCode = v; return this; }

        public Builder statusMessage(String v) { this.statusMessage = v; return this; }

        public Builder error(String type, String message) { this.errorType = type; this.errorMessage = message; return this; }

        public Builder requestBodyRef(String v) { this.requestBodyRef = v; return this; }

        public Builder responseBodyRef(String v) { this.responseBodyRef = v; return this; }

        public Builder requestBodyInline(NElement v) { this.requestBodyInline = v; return this; }

        public Builder responseBodyInline(NElement v) { this.responseBodyInline = v; return this; }

        public Builder pruned(boolean v) { this.pruned = v; return this; }

        public NaruAuditRecord build() {
            return new NaruAuditRecord(this);
        }
    }
}