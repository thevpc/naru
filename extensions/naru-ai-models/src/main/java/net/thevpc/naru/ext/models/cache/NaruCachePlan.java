package net.thevpc.naru.ext.models.cache;

import net.thevpc.naru.api.model.NaruCachePlanView;
import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruContextSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The caching decision for one request: which segments are a continuation of
 * the previously cached prefix, and where a client-controlled cache marker
 * should go.
 *
 * <p>A plan is computed from the current segments, the persisted baseline and
 * the provider's {@link NaruCachingMode}. Providers translate it into their own
 * wire format; the decision itself is made once, in one place.
 */
public final class NaruCachePlan implements NaruCachePlanView {

    private final List<NaruContextSegment> segments;
    private final List<String> segmentKeys;
    private final int validPrefix;
    private final NaruCachingMode mode;
    private final int divergenceIndex;

    private NaruCachePlan(List<NaruContextSegment> segments, List<String> segmentKeys,
                          int validPrefix, NaruCachingMode mode, int divergenceIndex) {
        this.segments = segments;
        this.segmentKeys = segmentKeys;
        this.validPrefix = validPrefix;
        this.mode = mode;
        this.divergenceIndex = divergenceIndex;
    }

    /**
     * Decide the plan for a request.
     *
     * <p>The only trustworthy inputs are the segments themselves and a baseline
     * that {@link NaruCacheBaseline#matches} this provider/model/mode. When the
     * baseline is absent, stale, or belongs to a different target, the result is
     * a full miss: every segment is re-sent as fresh content.
     */
    public static NaruCachePlan of(NaruCacheableContext context, NaruCacheBaseline baseline,
                                    String providerName, String modelName, NaruCachingMode mode) {
        List<NaruContextSegment> segments = context == null ? List.of() : context.segments();
        List<String> keys = NaruCacheKeyChain.chain(segments);
        if (mode == null || mode == NaruCachingMode.NONE || segments.isEmpty()) {
            return new NaruCachePlan(segments, keys, 0, mode == null ? NaruCachingMode.NONE : mode, 0);
        }
        if (baseline == null || !baseline.matches(providerName, modelName, mode) || baseline.getSegmentKeys().isEmpty()) {
            return new NaruCachePlan(segments, keys, 0, mode, 0);
        }
        int valid = NaruCacheKeyChain.validPrefixLength(baseline.getSegmentKeys(), keys);
        return new NaruCachePlan(segments, keys, valid, mode, valid);
    }

    /**
     * The chain for this request, to be stored as the next baseline.
     */
    public List<String> segmentKeys() {
        return segmentKeys;
    }

    @Override
    public List<NaruContextSegment> segments() {
        return segments;
    }

    @Override
    public NaruCachingMode mode() {
        return mode;
    }

    /**
     * How many leading segments remain a valid continuation of the cached prefix.
     */
    public int validPrefix() {
        return validPrefix;
    }

    /**
     * First segment index that is <em>not</em> a continuation of the cached
     * prefix. Equal to {@link #segmentCount()} when nothing diverged.
     */
    public int divergenceIndex() {
        return divergenceIndex;
    }

    public int segmentCount() {
        return segments.size();
    }

    /**
     * Whether segment {@code i} can keep whatever cache state it already had.
     */
    public boolean isValid(int i) {
        return i < validPrefix;
    }

    /**
     * Whether a cache marker should be attached after segment {@code i}.
     */
    @Override
    public boolean isCacheHit(int i) {
        return i < validPrefix && segments.get(i).cacheable();
    }

    /**
     * Indices after which a client-controlled breakpoint should be placed.
     *
     * <p>Anthropic accepts at most {@code maxBreakpoints} per request, but a
     * request can easily contain more cacheable segments than that (system
     * prompt, tools, per-module indexed context, project files, ...). Dropping
     * the extras has to be done deliberately, because the naive choices are both
     * bad: taking the first N leaves the largest stable blocks uncached, and
     * taking the last N clusters every marker at the volatile tail where the next
     * turn invalidates all of them.
     *
     * <p>So the last cacheable segment is always taken — it is the only
     * breakpoint that covers the whole stable prefix, and losing it is the
     * difference between caching everything and caching nothing. The remaining
     * slots go to the most stable remaining candidates: longest declared
     * lifetime first, then largest content.
     */
    @Override
    public List<Integer> cacheBreakpoints(int maxBreakpoints) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < validPrefix; i++) {
            if (segments.get(i).cacheable()) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty() || maxBreakpoints <= 0) {
            return List.of();
        }
        if (candidates.size() <= maxBreakpoints) {
            return candidates;
        }
        List<Integer> chosen = new ArrayList<>();
        // anchor on the tail: the only marker that caches the entire prefix
        chosen.add(candidates.get(candidates.size() - 1));

        List<Integer> rest = new ArrayList<>(candidates.subList(0, candidates.size() - 1));
        rest.sort(Comparator
                .comparingLong((Integer i) -> segments.get(i).minLifetime() == null
                        ? 0L : segments.get(i).minLifetime().toMillis())
                .reversed()
                .thenComparing(Comparator.comparingInt(this::sizeOf).reversed())
                .thenComparing(Comparator.naturalOrder()));
        for (int i = 0; i < rest.size() && chosen.size() < maxBreakpoints; i++) {
            if (!chosen.contains(rest.get(i))) {
                chosen.add(rest.get(i));
            }
        }
        chosen.sort(Comparator.naturalOrder());
        return chosen;
    }

    /**
     * The stable prefix a stateful provider should freeze into a cached
     * resource: everything up to and including the last chosen breakpoint.
     *
     * <p>Everything after that point is sent per-request. Only the part up to the
     * final breakpoint is eligible to become a resource, because a
     * {@code CachedContent} resource that includes the volatile tail could never
     * be reused on a later turn.
     */
    public List<NaruContextSegment> resourcePrefix(List<Integer> breakpoints) {
        if (breakpoints.isEmpty()) {
            return List.of();
        }
        int last = breakpoints.get(breakpoints.size() - 1);
        return new ArrayList<>(segments.subList(0, last + 1));
    }

    /**
     * The segments sent fresh on this request: everything from the first cache
     * miss onwards, plus any segment after the cached prefix that is not itself
     * eligible.
     */
    public List<NaruContextSegment> volatileSuffix() {
        if (validPrefix >= segments.size()) {
            return List.of();
        }
        return new ArrayList<>(segments.subList(validPrefix, segments.size()));
    }

    private int sizeOf(int index) {
        return segments.get(index).canonical().length();
    }

    @Override
    public String toString() {
        return "NaruCachePlan[" + mode + ", segments=" + segments.size()
                + ", valid=" + validPrefix + ", divergence=" + divergenceIndex + "]";
    }
}
