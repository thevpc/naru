package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class NaruTokenTransaction {
    private final Labels labels;
    private final String sessionId;
    private final String userId;
    private final NaruModelConfig model;
    private final long promptTokens;
    private final long completionTokens;
    /**
     * Input tokens billed at the cache-write rate, or -1 when the provider
     * reports no cache accounting.
     */
    private final long cacheWriteTokens;
    /**
     * Input tokens billed at the discounted cache-read rate, or -1 when the
     * provider reports no cache accounting. These are a subset of
     * {@code promptTokens}, not an addition to it.
     */
    private final long cacheReadTokens;
    /**
     * The price resolved when the call was tracked, or null when the model was
     * unpriced. Snapshotted rather than looked up at report time on purpose:
     * {@code price set} must not rewrite the cost of calls already made, and an
     * unpriced call must stay visibly unpriced even after a price appears.
     */
    private final NaruUnitPrice price;
    private final Instant timestamp;
    private final NDuration duration;

    public NaruTokenTransaction(String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens, Instant timestamp, NDuration duration) {
        this(sessionId, userId, model, promptTokens, completionTokens, -1, -1, timestamp, duration);
    }

    public NaruTokenTransaction(String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens,
                                long cacheWriteTokens, long cacheReadTokens, Instant timestamp, NDuration duration) {
        this(buildLabels(sessionId, userId, model), sessionId, userId, model, promptTokens, completionTokens,
                cacheWriteTokens, cacheReadTokens, null, timestamp, duration);
    }

    public NaruTokenTransaction(Labels labels, String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens,
                                long cacheWriteTokens, long cacheReadTokens, Instant timestamp, NDuration duration) {
        this(labels, sessionId, userId, model, promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens,
                null, timestamp, duration);
    }

    public NaruTokenTransaction(Labels labels, String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens,
                                long cacheWriteTokens, long cacheReadTokens, NaruUnitPrice price, Instant timestamp, NDuration duration) {
        this.labels = labels == null ? buildLabels(sessionId, userId, model) : labels;
        this.sessionId = sessionId;
        // a blank id is no id: storing it raw produced a row the model-wide listing
        // filtered out, so the call vanished from the report while still counting.
        this.userId = NStringUtils.stripToNull(userId);
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.price = price;
        this.timestamp = timestamp;
        this.duration = duration;
    }

    /**
     * The same call with the price resolved from the price table. Used at track
     * time, once, so every later read sees the price the call was actually made
     * under.
     */
    public NaruTokenTransaction withPrice(NaruUnitPrice price) {
        return new NaruTokenTransaction(labels, sessionId, userId, model, promptTokens, completionTokens,
                cacheWriteTokens, cacheReadTokens, price, timestamp, duration);
    }

    private static Labels buildLabels(String sessionId, String userId, NaruModelConfig model) {
        Labels.Builder b = Labels.builder();
        if (sessionId != null) {
            b.add("session", sessionId);
        }
        String uid = NStringUtils.stripToNull(userId);
        if (uid != null) {
            b.add("user", uid);
        }
        if (model != null) {
            if (model.provider() != null) {
                b.add("provider", model.provider());
            }
            if (model.model() != null) {
                b.add("model", model.model());
            }
        }
        return b.build();
    }

    public Labels getLabels() {
        return labels;
    }

    public NDuration getDuration() {
        return duration;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public NaruModelConfig getModel() {
        return model;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public NaruUnitPrice getPrice() {
        return price;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    // ── persistence ──────────────────────────────────────────────────────────

    /**
     * One transaction as a self-contained element. Money stays inside
     * {@link NaruUnitPrice#toElement()} (as strings); token counts are numbers.
     */
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        NObjectElementBuilder lb = NObjectElementBuilder.of();
        for (Map.Entry<String, String> e : labels.asMap().entrySet()) {
            lb.add(e.getKey(), NElement.ofString(e.getValue()));
        }
        o.set("labels", lb.build());
        o.set("sessionId", sessionId);
        o.set("userId", userId);
        o.set("model", model == null ? null : model.toElement());
        o.set("promptTokens", promptTokens);
        o.set("completionTokens", completionTokens);
        o.set("cacheWriteTokens", cacheWriteTokens);
        o.set("cacheReadTokens", cacheReadTokens);
        o.set("price", price == null ? null : price.toElement());
        o.set("timestamp", NElement.ofInstant(timestamp));
        o.set("durationMillis", duration == null ? 0L : duration.toMillis());
        return o.build();
    }

    /**
     * Reads back what {@link #toElement()} wrote. Returns null when the element is not
     * an object, or is missing the model or timestamp, so a corrupt log line can be
     * skipped rather than failing a whole report or counting as a zero-token call.
     */
    public static NaruTokenTransaction ofElement(NElement e) {
        NObjectElement o = e == null ? null : e.asObject().orNull();
        if (o == null) {
            return null;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        NObjectElement lb = o.get("labels").flatMap(x -> x.isNull() ? null : x.asObject()).orNull();
        if (lb != null) {
            for (NPairElement p : lb.namedPairs()) {
                String k = p.key().asStringValue().orNull();
                String v = p.value().asStringValue().orNull();
                if (k != null && v != null) {
                    labels.put(k, v);
                }
            }
        }
        NElement me = o.get("model").orNull();
        NaruModelConfig model = (me == null || me.isNull()) ? null : new NaruModelConfig(me);
        Instant timestamp = o.getInstantValue("timestamp").orNull();
        if (model == null || timestamp == null) {
            // an unterminated line from a killed process parses leniently but has no
            // model or time; treating it as a call would fabricate one
            return null;
        }
        return new NaruTokenTransaction(
                Labels.of(labels),
                o.getStringValue("sessionId").orNull(),
                o.getStringValue("userId").orNull(),
                model,
                o.getLongValue("promptTokens").orElse(0L),
                o.getLongValue("completionTokens").orElse(0L),
                o.getLongValue("cacheWriteTokens").orElse(-1L),
                o.getLongValue("cacheReadTokens").orElse(-1L),
                NaruUnitPrice.ofElement(o.get("price").orNull()),
                timestamp,
                NDuration.ofMillis(o.getLongValue("durationMillis").orElse(0L)));
    }
}
