package net.thevpc.naru.ext.models.cache;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-session prompt-cache bookkeeping.
 *
 * <p>Lives in the session extension slot ({@code ext/model-cache.tson}) rather
 * than in the session's own record, because this is model-layer state about a
 * remote provider's cache and has no business in the core session format. It
 * also gets the single-instance-per-session guarantee for free, which is what
 * lets the update path be a plain method call instead of a lookup dance on
 * every model turn.
 *
 * <p>State is keyed by provider, model and caching mode. A session that talks
 * to more than one model would otherwise have one model's cache identity
 * silently invalidate another's.
 */
public class NaruModelCacheExtension implements NaruSessionExtension {

    public static final String NAME = "model-cache";

    private final Map<String, NaruCacheBaseline> baselines = new LinkedHashMap<>();

    /**
     * The instance for a session, or null when the extension was not registered.
     *
     * <p>Callers treat null as "no cache support configured" and fall back to
     * full resend, which is the correct behaviour rather than an error.
     */
    public static NaruModelCacheExtension of(NaruSession session) {
        if (session == null || session.registry() == null) {
            return null;
        }
        try {
            return session.registry().extension(NAME, NaruModelCacheExtension.class).orNull();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String key(String providerName, String modelName, NaruCachingMode mode) {
        return providerName + "|" + modelName + "|" + (mode == null ? NaruCachingMode.NONE : mode).name();
    }

    public NaruCacheBaseline baseline(String providerName, String modelName, NaruCachingMode mode) {
        return baselines.get(key(providerName, modelName, mode));
    }

    /**
     * Record the chain this turn actually sent, so the next turn can tell a hit
     * from a miss.
     */
    public void commit(String providerName, String modelName, NaruCachingMode mode,
                       java.util.List<String> segmentKeys, long now) {
        if (mode == null || mode == NaruCachingMode.NONE || segmentKeys == null || segmentKeys.isEmpty()) {
            return;
        }
        String k = key(providerName, modelName, mode);
        NaruCacheBaseline previous = baselines.get(k);
        // preserve a cached-resource id across a chain update; the resource is
        // invalidated separately, by the provider that owns it
        String resourceId = previous == null ? null : previous.getCachedResourceId();
        long expiresAt = previous == null ? -1 : previous.getResourceExpiresAt();
        baselines.put(k, new NaruCacheBaseline(providerName, modelName, mode,
                segmentKeys, resourceId, expiresAt, now));
    }

    /**
     * Associate a stateful cache resource with this target. Used by providers
     * whose cache lives in a separate server-side resource.
     */
    public void commitResource(String providerName, String modelName, NaruCachingMode mode,
                               String resourceId, long expiresAt, java.util.List<String> segmentKeys, long now) {
        String k = key(providerName, modelName, mode);
        NaruCacheBaseline previous = baselines.get(k);
        baselines.put(k, new NaruCacheBaseline(providerName, modelName, mode,
                segmentKeys != null ? segmentKeys
                        : (previous == null ? java.util.List.of() : previous.getSegmentKeys()),
                resourceId, expiresAt, now));
    }

    /**
     * Drop a resource reference without discarding the chain, so the next turn
     * can still reuse the prefix it already proved.
     */
    public void clearResource(String providerName, String modelName, NaruCachingMode mode) {
        String k = key(providerName, modelName, mode);
        NaruCacheBaseline previous = baselines.get(k);
        if (previous != null) {
            baselines.put(k, previous.withResource(null, -1, previous.getUpdatedAt()));
        }
    }

    public void clear() {
        baselines.clear();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public NOptional<NElement> load(NaruSession session, NPath file) {
        baselines.clear();
        if (file == null || !file.exists()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no model cache state at %s", file));
        }
        try {
            NElement e = NElementReader.ofTson().ntf(false).read(file);
            NObjectElement o = e.asObject().orNull();
            if (o == null) {
                return NOptional.ofNamedEmpty(NMsg.ofC("no model cache state at %s", file));
            }

            NElement entries = o.get("baselines").orNull();
            if (entries != null && entries.isAnyArray()) {
                for (NElement child : entries.asArray().get()) {
                    NObjectElement entry = child.asObject().orNull();
                    if (entry == null) {
                        continue;
                    }
                    NaruCacheBaseline b = NaruCacheBaseline.fromElement(entry);
                    if (b.getProviderName() != null) {
                        baselines.put(key(b.getProviderName(), b.getModelName(), b.getCachingMode()), b);
                    }
                }
            }
        } catch (RuntimeException ex) {
            // A corrupt cache hint must never cost the user their session: the
            // failure mode is a cold cache, which is always safe.
            baselines.clear();
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("no model cache state at %s", file));
    }

    @Override
    public NElement save(NaruSession session) {
        if (baselines.isEmpty()) {
            // tells the core to remove any stale state file
            return null;
        }
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("schemaVersion", NaruCacheBaseline.SCHEMA_VERSION);
        NArrayElementBuilder arr = NElement.ofArrayBuilder();
        for (NaruCacheBaseline baseline : baselines.values()) {
            arr.add(baseline.toElement());
        }
        b.set("baselines", arr.build());
        return b.build();
    }

    @Override
    public void close() {
        baselines.clear();
    }
}
