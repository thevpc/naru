package net.thevpc.naru.impl.engine.scheduler;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruContextSegment;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSegmentContent;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The segmentation is what a provider's cache identity is computed from, so the
 * two things worth pinning are that a segment actually carries its messages
 * (otherwise every turn hashes the same, and history edits read as hits) and
 * that the segments concatenate back to the request in order (otherwise a cache
 * breakpoint lands on the wrong message).
 */
public class NaruContextSegmenterTest {

    @BeforeAll
    public static void setUpWorkspace() {
        // NaruMessage renders its content through NText, which needs a workspace.
        Nuts.require();
    }

    private static NaruMessage system(String name, String text) {
        return NaruMessage.system(text).setSource(NaruSource.SYSTEM).setSourceName(name);
    }

    private static NaruMessage user(String text) {
        return NaruMessage.user(text).setSource(NaruSource.USER).setTurnBoundary(true);
    }

    private static NaruMessage assistant(String text) {
        return NaruMessage.assistant(text).setSource(NaruSource.USER);
    }

    private static List<NaruToolDefinition> tools(String... names) {
        List<NaruToolDefinition> l = new ArrayList<>();
        for (String n : names) {
            l.add(new NaruToolDefinitionFunction(n, n, new ArrayList<>()));
        }
        return l;
    }

    private static List<String> canonicals(NaruCacheableContext ctx) {
        List<String> out = new ArrayList<>();
        for (NaruContextSegment s : ctx.segments()) {
            out.add(s.canonical());
        }
        return out;
    }

    @Test
    public void testToolsComeFirstAndMessageSegmentsConcatenateToTheRequest() {
        List<NaruMessage> all = new ArrayList<>();
        all.add(system("core", "you are naru"));
        all.add(user("first"));
        all.add(assistant("answer"));
        NaruCacheableContext ctx = NaruContextSegmenter.segment(all, tools("edit", "read"));

        List<NaruContextSegment> segments = ctx.segments();
        Assertions.assertTrue(segments.get(0).content() instanceof NaruSegmentContent.Tools,
                "tool definitions are position 0 of the cached prefix on every wire");

        List<NaruMessage> flattened = new ArrayList<>();
        for (NaruContextSegment s : segments) {
            if (s.content() instanceof NaruSegmentContent.Messages m) {
                flattened.addAll(m.messages());
            }
        }
        Assertions.assertEquals(all, flattened,
                "serializers assign messages to segments by position, so order must survive exactly");
    }

    @Test
    public void testTurnSegmentsCarryTheirMessages() {
        // The regression: NaruSegmentContent.Messages snapshots its list, so a
        // segment created while its run was still filling hashed an empty list.
        // Every turn then looked unchanged, including after a mid-history edit.
        List<NaruMessage> all = new ArrayList<>();
        all.add(system("core", "you are naru"));
        all.add(user("first"));
        all.add(assistant("one"));
        all.add(user("second"));
        all.add(assistant("two"));

        NaruCacheableContext ctx = NaruContextSegmenter.segment(all, List.of());
        List<NaruContextSegment> segments = ctx.segments();
        Assertions.assertEquals(3, segments.size(), "context block + two turns");
        Assertions.assertTrue(segments.get(1).canonical().contains("first"));
        Assertions.assertTrue(segments.get(1).canonical().contains("one"));
        Assertions.assertTrue(segments.get(2).canonical().contains("second"));
        Assertions.assertFalse(segments.get(1).canonical().isEmpty());
    }

    @Test
    public void testEachStableContributorIsItsOwnSegment() {
        List<NaruMessage> all = new ArrayList<>();
        all.add(system("core", "stable instructions"));
        all.add(system("plan", "### ACTIVE PLAN:\nstep 1 done"));
        all.add(system("plan", "extra plan lines"));
        all.add(system("skills", "skill index"));

        NaruCacheableContext ctx = NaruContextSegmenter.segment(all, List.of());
        List<String> c = canonicals(ctx);
        Assertions.assertEquals(3, c.size(), "adjacent messages of one contributor stay together");
        Assertions.assertTrue(c.get(1).contains("step 1 done"));
        Assertions.assertTrue(c.get(1).contains("extra plan lines"));
    }

    @Test
    public void testAChurningContributorOnlyInvalidatesFromItsOwnPosition() {
        List<NaruMessage> turn1 = new ArrayList<>();
        turn1.add(system("core", "stable instructions"));
        turn1.add(system("plan", "step 1 pending"));
        turn1.add(user("hello"));

        List<NaruMessage> turn2 = new ArrayList<>();
        turn2.add(system("core", "stable instructions"));
        turn2.add(system("plan", "step 1 done"));
        turn2.add(user("hello"));
        turn2.add(assistant("hi"));

        List<String> a = canonicals(NaruContextSegmenter.segment(turn1, tools("read")));
        List<String> b = canonicals(NaruContextSegmenter.segment(turn2, tools("read")));

        // A cache chain key at index i commits to canonical[0..i], so identical
        // canonicals at the head mean the head of the prefix survives the churn.
        Assertions.assertEquals(a.get(0), b.get(0), "tool definitions survive");
        Assertions.assertEquals(a.get(1), b.get(1), "the stable block before the plan survives");
        Assertions.assertNotEquals(a.get(2), b.get(2), "the plan itself changed");
    }

    @Test
    public void testTheInFlightTailIsVolatileAndATrailingStableBlockIsNot() {
        List<NaruMessage> conversation = new ArrayList<>();
        conversation.add(system("core", "stable instructions"));
        conversation.add(user("hello"));
        conversation.add(assistant("hi"));
        NaruCacheableContext ctx = NaruContextSegmenter.segment(conversation, List.of());
        List<NaruContextSegment> segments = ctx.segments();
        Assertions.assertFalse(segments.get(segments.size() - 1).cacheable(),
                "the tail is still being appended to, so a breakpoint there is wasted");
        Assertions.assertTrue(segments.get(0).cacheable());

        List<NaruMessage> stableOnly = new ArrayList<>();
        stableOnly.add(system("core", "stable instructions"));
        List<NaruContextSegment> out = NaruContextSegmenter.segment(stableOnly, List.of()).segments();
        Assertions.assertTrue(out.get(out.size() - 1).cacheable(),
                "no conversation means no in-flight tail to mark volatile");
    }

    @Test
    public void testNothingToSendHasNoPlan() {
        Assertions.assertNull(NaruContextSegmenter.segment(new ArrayList<>(), List.of()));
        Assertions.assertNull(NaruContextSegmenter.segment(new ArrayList<>(), null));
    }
}
