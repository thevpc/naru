package net.thevpc.naru.impl.engine.scheduler;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruContextSegment;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSegmentContent;
import net.thevpc.naru.api.model.NaruToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a built prompt into the segments a provider can cache.
 *
 * <h2>The invariant that makes this safe</h2>
 *
 * Serializers map a segment onto the wire by <em>position</em>: the Anthropic
 * serializer walks the segments and assigns each message of the flat request to
 * the next one, so segments must concatenate to {@code request.messages()} in
 * exactly that order. Any other layout silently moves a cache breakpoint onto
 * the wrong message — the request still succeeds and the provider caches less
 * than the caller asked for. So segments are contiguous runs of the input list;
 * never regroup it.
 *
 * <h2>Why one run per contributor</h2>
 *
 * The stable region used to be a single segment, so a single churning
 * contributor — the active plan re-renders every turn — changed that segment's
 * hash and, because the cached prefix is positional, invalidated everything
 * after it too. Splitting the region into one segment per contributor keeps
 * everything <em>before</em> the churning one a valid prefix.
 *
 * <p>It cannot rescue what follows the change, and that limit belongs to the
 * provider rather than to NARU: a prefix cache has no way to reuse bytes that
 * sit after an edited position. Material that churns every turn belongs after
 * the conversation, not inside the system block.
 */
final class NaruContextSegmenter {

    private NaruContextSegmenter() {
    }

    /**
     * @return the segmentation, or {@code null} when there is nothing to cache
     */
    static NaruCacheableContext segment(List<NaruMessage> all, List<NaruToolDefinition> tools) {
        List<NaruContextSegment> segments = new ArrayList<>();
        if (tools != null && !tools.isEmpty()) {
            // Tool definitions travel beside the messages on every wire, so their
            // position in this list is free — and worth spending. Anthropic
            // evaluates the prefix as tools, then system, then messages, so
            // matching that order is what makes "this segment changed" correctly
            // imply "everything after it is suspect".
            segments.add(NaruContextSegment.cacheable("tool-defs", NaruSegmentContent.Tools.of(tools)));
        }
        List<List<NaruMessage>> runs = new ArrayList<>();
        List<Boolean> conversationRuns = new ArrayList<>();
        List<NaruMessage> current = null;
        String currentKey = null;
        for (NaruMessage m : all) {
            boolean conversation = isConversation(m);
            if (current == null
                    || conversation != conversationRuns.get(runs.size() - 1)
                    || (conversation ? m.isTurnBoundary() : !runKey(m).equals(currentKey))) {
                current = new ArrayList<>();
                runs.add(current);
                conversationRuns.add(conversation);
                currentKey = runKey(m);
            }
            current.add(m);
        }
        int turnIndex = 0;
        for (int i = 0; i < runs.size(); i++) {
            boolean conversation = conversationRuns.get(i);
            String id = conversation ? "turn-" + turnIndex : "context-" + (i + 1);
            // Snapshot only now: NaruSegmentContent.Messages copies its list on
            // construction, so a segment built while the run was still filling
            // would cache the identity of an empty list forever.
            NaruContextSegment segment = NaruContextSegment.cacheable(id, NaruSegmentContent.Messages.of(runs.get(i)));
            if (conversation) {
                turnIndex++;
            }
            segments.add(segment);
        }
        if (!runs.isEmpty() && conversationRuns.get(runs.size() - 1)) {
            // The tail is what this turn is still appending to; a breakpoint on it
            // would be invalidated by the next message the agent adds.
            int last = segments.size() - 1;
            NaruContextSegment tail = segments.get(last);
            segments.set(last, NaruContextSegment.volatileSegment(tail.id(), tail.content()));
        }
        return segments.isEmpty() ? null : NaruCacheableContext.of(segments);
    }

    /**
     * The attribution that decides whether two adjacent stable messages belong
     * to the same caching candidate: one contributor is one block of prompt text
     * — one extension, one agent markdown file, one mode prompt.
     */
    private static String runKey(NaruMessage m) {
        NaruSource source = m.getSource();
        String name = m.getSourceName();
        return (source == null ? "-" : source.name()) + '|' + (name == null ? "-" : name);
    }

    private static boolean isConversation(NaruMessage m) {
        return m.getSource() == NaruSource.USER;
    }
}
