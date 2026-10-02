package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link NaruMessage} is what the session store persists one file per, and what the audit
 * store keys its content hashes on. A field that does not survive {@code of(toElement())}
 * is therefore not a cosmetic loss: it silently changes what a later run sends to a model.
 *
 * <p>These tests pin the round trip with <em>every</em> field set, and pin the two
 * properties the storage layer depends on: a typical message is unchanged on disk
 * (so existing session files stay readable and byte-identical), and {@code toElement()} is
 * idempotent (so hashing a message twice gives the same answer).
 */
public class NaruMessageSerializationTest {

    /**
     * Building and writing elements goes through the Nuts workspace, which is not
     * implicitly available in a plain unit test.
     */
    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            Nuts.require();
        }
        Nuts.require();
    }

    /**
     * Every field, at once, with none of them at a default. A test that leaves a field
     * alone cannot catch that field being dropped, so this one deliberately does not.
     */
    private static NaruMessage fullyPopulated() {
        java.util.Map<String, Object> a1 = new java.util.LinkedHashMap<>();
        a1.put("path", "A.java");
        a1.put("line", 12);
        a1.put("wholeFile", true);
        java.util.Map<String, Object> a2 = new java.util.LinkedHashMap<>();
        a2.put("q", "foo");
        NaruMessage m = NaruMessage.assistantWithToolCalls("here is the answer",
                List.of(
                        new NaruToolCall("call-1", "read_file", a1),
                        new NaruToolCall("call-2", "grep", a2)
                ));
        m.setSourceName("my-extension").setSource(NaruSource.AGENT);
        m.setToolCallId("outer-call-id");
        m.setImages(List.of("AAAAbase64", "BBBBbase64"));
        m.setTurnBoundary(true);
        // a second segment that is *not* the last, so ordering is observable, and one that
        // is incomplete, so the "omitted when true" branch is not what gets exercised
        m.addThinkingSegment(new NaruThinkingSegment(0, "let me look at the imports",
                NaruThinkingExtraction.NATIVE_FIELD, "qwen", 42L, false));
        m.addThinkingSegment(new NaruThinkingSegment(1, "found it", null, "qwen"));
        return m;
    }

    @Test
    public void everyFieldSurvivesTheRoundTrip() {
        NaruMessage original = fullyPopulated();
        NaruMessage reloaded = NaruMessage.of(original.toElement());

        Assertions.assertEquals(original.getRole(), reloaded.getRole());
        Assertions.assertEquals(original.getContent(), reloaded.getContent());
        Assertions.assertEquals(original.getSourceName(), reloaded.getSourceName());
        Assertions.assertEquals(original.getSource(), reloaded.getSource());
        Assertions.assertEquals(original.getImages(), reloaded.getImages());
        Assertions.assertEquals(original.getToolCallId(), reloaded.getToolCallId());
        Assertions.assertEquals(original.getToolName(), reloaded.getToolName());
        Assertions.assertEquals(original.isTurnBoundary(), reloaded.isTurnBoundary());
        Assertions.assertEquals(original.getThinking(), reloaded.getThinking());

        Assertions.assertNotNull(reloaded.getToolCalls(), "tool calls must survive");
        Assertions.assertEquals(original.getToolCalls().size(), reloaded.getToolCalls().size());
        for (int i = 0; i < original.getToolCalls().size(); i++) {
            NaruToolCall a = original.getToolCalls().get(i);
            NaruToolCall b = reloaded.getToolCalls().get(i);
            Assertions.assertEquals(a.getId(), b.getId());
            Assertions.assertEquals(a.getName(), b.getName());
            Assertions.assertEquals(a.getArguments(), b.getArguments());
        }

        List<NaruThinkingSegment> before = original.getThinkingSegments();
        List<NaruThinkingSegment> after = reloaded.getThinkingSegments();
        Assertions.assertNotNull(after, "thinking segments must survive");
        Assertions.assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            Assertions.assertEquals(before.get(i).getIndex(), after.get(i).getIndex());
            Assertions.assertEquals(before.get(i).getText(), after.get(i).getText());
            Assertions.assertEquals(before.get(i).getExtraction(), after.get(i).getExtraction());
            Assertions.assertEquals(before.get(i).getProvider(), after.get(i).getProvider());
            Assertions.assertEquals(before.get(i).getThinkingTokens(), after.get(i).getThinkingTokens());
            Assertions.assertEquals(before.get(i).isComplete(), after.get(i).isComplete());
        }
    }

    /**
     * The store hashes the compact TSON of {@code toElement()}. If a second pass produced a
     * different element, an unchanged message would be rewritten on every save.
     */
    @Test
    public void toElementIsStableOnASecondPass() {
        NaruMessage original = fullyPopulated();
        NElement first = original.toElement();
        NElement second = NaruMessage.of(first).toElement();

        Assertions.assertEquals(compact(first), compact(second));
    }

    @Test
    public void aPlainUserMessageIsUnchangedOnDisk() {
        // the overwhelmingly common shape: nothing deviates from the defaults, so nothing
        // is added, and existing session files keep reading identically
        NaruMessage m = NaruMessage.user("hello").setTurnBoundary(true);
        NElement e = m.toElement();
        Assertions.assertNull(e.asObject().get().get("source").orNull());
        Assertions.assertNull(e.asObject().get().get("sourceName").orNull());
        Assertions.assertNull(e.asObject().get().get("toolCallId").orNull());
        Assertions.assertEquals("hello", e.asObject().get().getStringValue("content").orNull());
        Assertions.assertTrue(e.asObject().get().getBooleanValue("turnBoundary").orElse(false));
    }

    @Test
    public void toolCallIdIsPersisted() {
        NaruMessage m = NaruMessage.tool("read_file", "call-42", "file contents");
        NaruMessage reloaded = NaruMessage.of(m.toElement());
        Assertions.assertEquals("call-42", reloaded.getToolCallId());
        Assertions.assertEquals("read_file", reloaded.getToolName());
        Assertions.assertEquals(NaruSource.AGENT, reloaded.getSource());
    }

    @Test
    public void aNonDefaultSourceIsPersistedAndRestored() {
        // an extension message that looks like a user turn, attributed to its extension
        NaruMessage m = NaruMessage.user("context you should know")
                .setSource(NaruSource.SKILL)
                .setSourceName("javadoc");
        NaruMessage reloaded = NaruMessage.of(m.toElement());
        Assertions.assertEquals(NaruSource.SKILL, reloaded.getSource());
        Assertions.assertEquals("javadoc", reloaded.getSourceName());
    }

    /**
     * A session file written before `source` was persistable has no such key. Reading it
     * must still attribute messages the way the factory helpers would, or a reload would
     * make every assistant and tool message look like it came from the user -- which
     * changes prompt-cache segmentation and the {@code /context} listing.
     */
    @Test
    public void aMissingSourceIsInferredFromTheRole() {
        for (NaruRole role : NaruRole.values()) {
            NElement legacy = NElementReader.ofTson().ntf(false)
                    .read("{\"role\":\"" + role.name() + "\",\"content\":\"x\"}");
            NaruMessage reloaded = NaruMessage.of(legacy);
            switch (role) {
                case assistant:
                    Assertions.assertEquals(NaruSource.ASSISTANT, reloaded.getSource(), role.name());
                    break;
                case tool:
                    Assertions.assertEquals(NaruSource.AGENT, reloaded.getSource(), role.name());
                    break;
                case user:
                    Assertions.assertEquals(NaruSource.USER, reloaded.getSource(), role.name());
                    break;
                default:
                    Assertions.assertEquals(NaruSource.SYSTEM, reloaded.getSource(), role.name());
                    break;
            }
        }
    }

    @Test
    public void aLegacyThinkingStringIsStillReadable() {
        // sessions written before thinking segments existed carry a single string; the
        // store must not try to migrate them, only keep them loadable
        NaruMessage reloaded = NaruMessage.of(net.thevpc.nuts.elem.NElementReader.ofTson()
                .ntf(false)
                .read("{\"role\":\"assistant\",\"content\":\"hi\",\"thinking\":\"old reasoning\"}"));
        Assertions.assertEquals("old reasoning", reloaded.getThinking());
        Assertions.assertNull(reloaded.getThinkingSegments());
    }

    @Test
    public void withContentKeepsEverythingButTheContent() {
        NaruMessage original = fullyPopulated();
        NaruMessage derived = original.withContent("merged answer");

        Assertions.assertEquals("merged answer", derived.getContent());
        Assertions.assertEquals(original.getRole(), derived.getRole());
        Assertions.assertEquals(original.getSource(), derived.getSource());
        Assertions.assertEquals(original.getSourceName(), derived.getSourceName());
        Assertions.assertEquals(original.getImages(), derived.getImages());
        Assertions.assertEquals(original.getToolCallId(), derived.getToolCallId());
        Assertions.assertEquals(original.getToolName(), derived.getToolName());
        Assertions.assertEquals(original.isTurnBoundary(), derived.isTurnBoundary(),
                "turnBoundary feeds prompt-cache segmentation and must not be dropped");
        Assertions.assertEquals(original.getThinking(), derived.getThinking());
        Assertions.assertEquals(original.getThinkingSegments().size(),
                derived.getThinkingSegments().size());

        // independent copies, not shared instances
        derived.getToolCalls().get(0).setName("changed");
        Assertions.assertNotEquals("changed", original.getToolCalls().get(0).getName(),
                "withContent must not share NaruToolCall instances with the original");
    }

    /**
     * A TSON string value carries its own quotes, so reading an argument back out of a
     * session file used to hand the tool a path with a pair of quotes glued to it. Tools are
     * dispatched with these values verbatim, so the damage is not cosmetic: it is a wrong
     * argument in a file the user cannot see.
     */
    @Test
    public void toolCallArgumentsComeBackAsTheValuesThatWereWritten() {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("plain", "A.java");
        args.put("number", 42);
        args.put("flag", Boolean.TRUE);
        args.put("withQuotes", "he said \"stop\"");
        args.put("jsonish", "{\"path\":\"x\"}");
        args.put("empty", "");

        NaruMessage reloaded = NaruMessage.of(
                NaruMessage.assistantWithToolCalls("x", List.of(new NaruToolCall("c1", "t", args))).toElement());

        java.util.Map<String, Object> back = reloaded.getToolCalls().get(0).getArguments();
        Assertions.assertEquals("A.java", back.get("plain"));
        Assertions.assertEquals(42L, ((Number) back.get("number")).longValue());
        Assertions.assertEquals(Boolean.TRUE, back.get("flag"));
        Assertions.assertEquals("he said \"stop\"", back.get("withQuotes"));
        Assertions.assertEquals("{\"path\":\"x\"}", back.get("jsonish"));
        Assertions.assertEquals("", back.get("empty"));

        // and re-serializing must not drift
        Assertions.assertEquals(compact(reloaded.toElement()), compact(reloaded.toElement()));
    }

    @Test
    public void nullFieldsStayNullRatherThanBecomingEmpty() {
        NaruMessage m = NaruMessage.assistant("just text");
        NaruMessage reloaded = NaruMessage.of(m.toElement());
        Assertions.assertNull(reloaded.getImages());
        Assertions.assertNull(reloaded.getToolCalls());
        Assertions.assertNull(reloaded.getToolName());
        Assertions.assertNull(reloaded.getToolCallId());
        Assertions.assertNull(reloaded.getSourceName());
        Assertions.assertNull(reloaded.getThinking());
        Assertions.assertFalse(reloaded.hasThinking());
    }

    private static String compact(NElement e) {
        return NElementWriter.ofTson().ntf(false).compact(true).formatPlain(e);
    }
}
