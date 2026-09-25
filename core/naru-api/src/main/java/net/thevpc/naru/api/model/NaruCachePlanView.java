package net.thevpc.naru.api.model;

import java.util.List;

/**
 * What a serializer needs to know to place cache markers, without knowing how
 * NARU decides cache validity.
 *
 * <p>Kept in {@code naru-api} as a narrow view so serializers can be written
 * against it while the invalidation logic stays in the model layer. It answers
 * two questions only: which segments are still a cached prefix, and where should
 * a marker go.
 */
public interface NaruCachePlanView {

    /**
     * The segments in emission order.
     */
    List<NaruContextSegment> segments();

    /**
     * Whether segment {@code i} is a continuation of the previously cached
     * prefix <em>and</em> the caller marked it cacheable. A segment past the
     * divergence point is never a hit, whatever the caller claimed, because its
     * position in the prefix changed.
     */
    boolean isCacheHit(int i);

    /**
     * Segment indices after which a marker should be placed, capped at
     * {@code maxBreakpoints}. May return fewer than the cap when fewer segments
     * qualify; never returns more.
     */
    List<Integer> cacheBreakpoints(int maxBreakpoints);

    NaruCachingMode mode();

    /**
     * The plan used when a request carries no segmentation, or when the
     * provider has no caching support. Everything is a miss, so a serializer
     * that ignores it entirely produces the same body as before.
     */
    static NaruCachePlanView none() {
        return None.INSTANCE;
    }

    /**
     * A plan derived straight from a context with no prior baseline: used by
     * serializers in tests and by callers that want markers on the first call.
     */
    static NaruCachePlanView allCacheable(NaruCacheableContext context) {
        List<NaruContextSegment> segments = context == null ? List.of() : context.segments();
        return new NaruCachePlanView() {
            @Override
            public List<NaruContextSegment> segments() {
                return segments;
            }

            @Override
            public boolean isCacheHit(int i) {
                return i >= 0 && i < segments.size() && segments.get(i).cacheable();
            }

            @Override
            public List<Integer> cacheBreakpoints(int maxBreakpoints) {
                List<Integer> all = new java.util.ArrayList<>();
                for (int i = 0; i < segments.size(); i++) {
                    if (segments.get(i).cacheable()) {
                        all.add(i);
                    }
                }
                return all.size() <= maxBreakpoints ? all : all.subList(0, maxBreakpoints);
            }

            @Override
            public NaruCachingMode mode() {
                return NaruCachingMode.EXPLICIT_INLINE;
            }
        };
    }

    /**
     * @param segments ignored; a plan with no segmentation has none
     */
    final class None implements NaruCachePlanView {
        static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public List<NaruContextSegment> segments() {
            return List.of();
        }

        @Override
        public boolean isCacheHit(int i) {
            return false;
        }

        @Override
        public List<Integer> cacheBreakpoints(int maxBreakpoints) {
            return List.of();
        }

        @Override
        public NaruCachingMode mode() {
            return NaruCachingMode.NONE;
        }
    }
}
