package net.thevpc.naru.ext.models.cache;

import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * The last cache identity this session established, persisted between turns.
 *
 * <p>Identity is scoped to a provider, a model and a caching mode on purpose.
 * A session that switches model, or an endpoint reconfigured from an
 * OpenAI-compatible one to Anthropic, has a baseline that describes a different
 * server's cache entirely; reusing it would claim a hit for a prefix the new
 * provider has never seen. Scoping makes that a cold start rather than a lie.
 */
public class NaruCacheBaseline {

    /** Bumped if the on-disk shape changes in a way older readers cannot handle. */
    public static final int SCHEMA_VERSION = 1;

    private final String providerName;
    private final String modelName;
    private final NaruCachingMode cachingMode;
    private final List<String> segmentKeys;
    /** Resource id for {@link NaruCachingMode#EXPLICIT_RESOURCE}; null otherwise. */
    private final String cachedResourceId;
    /** Epoch millis the cached resource is expected to disappear; -1 if unknown. */
    private final long resourceExpiresAt;
    private final long updatedAt;

    public NaruCacheBaseline(String providerName, String modelName, NaruCachingMode cachingMode,
                             List<String> segmentKeys, String cachedResourceId, long resourceExpiresAt, long updatedAt) {
        this.providerName = providerName;
        this.modelName = modelName;
        this.cachingMode = cachingMode == null ? NaruCachingMode.NONE : cachingMode;
        this.segmentKeys = segmentKeys == null ? List.of() : List.copyOf(segmentKeys);
        this.cachedResourceId = cachedResourceId;
        this.resourceExpiresAt = resourceExpiresAt;
        this.updatedAt = updatedAt;
    }

    public static NaruCacheBaseline empty() {
        return new NaruCacheBaseline(null, null, NaruCachingMode.NONE, List.of(), null, -1, -1);
    }

    public String getProviderName() {
        return providerName;
    }

    public String getModelName() {
        return modelName;
    }

    public NaruCachingMode getCachingMode() {
        return cachingMode;
    }

    public List<String> getSegmentKeys() {
        return segmentKeys;
    }

    public String getCachedResourceId() {
        return cachedResourceId;
    }

    public long getResourceExpiresAt() {
        return resourceExpiresAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Whether this baseline can be compared against a request for the given
     * provider/model/mode. A mismatch on any axis means a cold cache.
     */
    public boolean matches(String providerName, String modelName, NaruCachingMode mode) {
        return java.util.Objects.equals(this.providerName, providerName)
                && java.util.Objects.equals(this.modelName, modelName)
                && this.cachingMode == mode;
    }

    /**
     * Whether a cached resource id may still be trusted at {@code now}.
     *
     * <p>An unknown expiry is treated as expired. The alternative is holding a
     * reference to a resource the provider already dropped, which surfaces as an
     * opaque 404 on the next call and costs a round trip to diagnose.
     */
    public boolean isResourceUsable(long now, long safetyMarginMillis) {
        if (cachedResourceId == null || cachedResourceId.isBlank()) {
            return false;
        }
        if (resourceExpiresAt < 0) {
            return false;
        }
        return resourceExpiresAt - safetyMarginMillis > now;
    }

    public NaruCacheBaseline withSegmentKeys(List<String> keys) {
        return new NaruCacheBaseline(providerName, modelName, cachingMode, keys, cachedResourceId, resourceExpiresAt, updatedAt);
    }

    public NaruCacheBaseline withResource(String resourceId, long expiresAt, long now) {
        return new NaruCacheBaseline(providerName, modelName, cachingMode, segmentKeys, resourceId, expiresAt, now);
    }

    public NaruCacheBaseline withUpdatedAt(long now) {
        return new NaruCacheBaseline(providerName, modelName, cachingMode, segmentKeys, cachedResourceId, resourceExpiresAt, now);
    }

    public NElement toElement() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("schemaVersion", SCHEMA_VERSION);
        b.set("provider", providerName);
        b.set("model", modelName);
        b.set("cachingMode", cachingMode.name());
        NArrayElementBuilder keys = NArrayElementBuilder.of();
        for (String k : segmentKeys) {
            keys.add(NElement.ofString(k));
        }
        b.set("segmentKeys", keys.build());
        if (cachedResourceId != null) {
            b.set("cachedResourceId", cachedResourceId);
        }
        if (resourceExpiresAt >= 0) {
            b.set("resourceExpiresAt", resourceExpiresAt);
        }
        b.set("updatedAt", updatedAt);
        return b.build();
    }

    /**
     * Reads a baseline back. Any structural surprise yields {@link #empty()}
     * rather than an exception: a corrupt cache hint must never cost the user
     * their session, and a cold cache is always a safe answer.
     */
    public static NaruCacheBaseline fromElement(NElement e) {
        if (e == null || !e.isAnyObject()) {
            return empty();
        }
        try {
            NObjectElement o = e.asObject().get();
            List<String> keys = new ArrayList<>();
            NElement keysElement = o.get("segmentKeys").orNull();
            if (keysElement != null && keysElement.isAnyArray()) {
                for (NElement child : keysElement.asArray().get()) {
                    child.asStringValue().ifPresent(keys::add);
                }
            }
            NaruCachingMode mode = NaruCachingMode.NONE;
            String modeName = o.getStringValue("cachingMode").orNull();
            if (modeName != null) {
                try {
                    mode = NaruCachingMode.valueOf(modeName);
                } catch (IllegalArgumentException ignored) {
                    // a mode this build does not know about is not a reason to
                    // fail the load; treat it as "no caching" and move on
                }
            }
            return new NaruCacheBaseline(
                    o.getStringValue("provider").orNull(),
                    o.getStringValue("model").orNull(),
                    mode,
                    keys,
                    o.getStringValue("cachedResourceId").orNull(),
                    o.getLongValue("resourceExpiresAt").orElse(-1L),
                    o.getLongValue("updatedAt").orElse(-1L)
            );
        } catch (RuntimeException ex) {
            return empty();
        }
    }
}
