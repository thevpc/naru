package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruToolCall;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The card's text, tested where it is written. The header is allowed to clip — a pasted file in an
 * argument must not make a one-line row three screens tall — and the body is not, because the body
 * being whole is the reason this phase exists.
 */
class ToolCardTextTest {

    private static NaruToolCallEvent ev(NaruToolCallEvent.Kind kind, Map<String, Object> args,
                                        String result, long ms) {
        NaruToolCall c = new NaruToolCall("call-1", "my_tool", args);
        return switch (kind) {
            case STARTED -> NaruToolCallEvent.started(4, c);
            case PARKED -> NaruToolCallEvent.parked(4, c);
            case RESULT -> NaruToolCallEvent.result(4, c, result, ms);
            case FAILED -> NaruToolCallEvent.failed(4, c, result, ms);
        };
    }

    @Test
    void argumentsRenderOnOneLineInOrder() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "notes.txt");
        args.put("limit", 40);
        args.put("raw", false);
        args.put("offset", null);
        args.put("nested", Map.of("a", 1));
        args.put("list", List.of("x", 2));
        String s = ToolCardText.arguments(args);
        assertTrue(s.startsWith("{path:\"notes.txt\",limit:40,raw:false,offset:null"), s);
        assertTrue(s.contains("nested:{a:1}"), s);
        assertTrue(s.contains("list:[\"x\",2]"), s);
        assertEquals(s, s.replace("\n", ""), "a header is one line whatever the arguments are");
    }

    @Test
    void aLongArgumentValueIsClippedWithItsSizeStated() {
        String s = ToolCardText.arguments(Map.of("content", "z".repeat(10_000)));
        assertTrue(s.length() < 400, "the header stays short: " + s.length());
        assertTrue(s.contains("(10000 chars)"), "but never pretends the value was that short");
    }

    @Test
    void quotesAndNewlinesStayInsideTheStringTheyBelongTo() {
        String s = ToolCardText.arguments(Map.of("cmd", "echo \"hi\"\nls"));
        assertEquals("{cmd:\"echo \\\"hi\\\"\\nls\"}", s);
    }

    @Test
    void noArgumentsIsAnEmptyObject() {
        assertEquals("{}", ToolCardText.arguments(Map.of()));
        assertEquals("{}", ToolCardText.arguments(null));
    }

    @Test
    void durationsAreReadableAndAbsentWhenUnmeasured() {
        assertEquals("450 ms", ToolCardText.duration(450));
        assertEquals("1.2 s", ToolCardText.duration(1_234));
        assertNull(ToolCardText.duration(NaruToolCallEvent.UNKNOWN_DURATION));
    }

    @Test
    void eachKindWritesItsOwnHeaderLine() {
        Map<String, Object> args = Map.of("path", "a.txt");
        assertTrue(ToolCardText.header(ev(NaruToolCallEvent.Kind.STARTED, args, null, -1))
                .startsWith("▶ my_tool {path:\"a.txt\"}"));
        assertTrue(ToolCardText.header(ev(NaruToolCallEvent.Kind.PARKED, args, null, -1))
                .contains("waiting for an answer"));
        String ok = ToolCardText.header(ev(NaruToolCallEvent.Kind.RESULT, args, "0123456789", 30));
        assertTrue(ok.startsWith("✔ my_tool · 30 ms · 10 chars · call-1"), ok);
        String bad = ToolCardText.header(ev(NaruToolCallEvent.Kind.FAILED, args, "ERROR: no", -1));
        assertTrue(bad.startsWith("✗ my_tool · 9 chars"), bad);
        assertTrue(ToolCardText.header(ev(NaruToolCallEvent.Kind.STARTED, args, null, -1))
                .endsWith("\n"), "so a card made of these lines stays readable as text");
    }

    @Test
    void anUnnamedToolSaysSoInsteadOfRenderingABlank() {
        NaruToolCallEvent e = NaruToolCallEvent.started(1, new NaruToolCall("id", "", Map.of()));
        assertTrue(ToolCardText.header(e).contains("(unnamed tool)"), ToolCardText.header(e));
    }

    @Test
    void theBodyIsTheWholeResultWithNoCap() {
        String big = "r".repeat(300_000);
        assertEquals(big, ToolCardText.cardBody(ev(NaruToolCallEvent.Kind.RESULT,
                Map.of(), big, 5)), "the 300-character console abbreviation stops at the console");
        assertNull(ToolCardText.cardBody(ev(NaruToolCallEvent.Kind.STARTED, Map.of(), null, -1)));
    }
}
