package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live strip's source of truth, headless. The properties that matter are the ones a reader
 * sees: what kind of work is happening, which tool and file are named, and what has changed since
 * the line they sent.
 */
class TurnActivityTest {

    @Test
    void aTurnRemembersItsToolsFilesAndFailures() {
        TurnActivity activity = new TurnActivity();
        activity.beginTurn();
        activity.accept(List.of(
                new TranscriptEvent.Chunk("t1", NaruLogMode.MODEL_THINKING, "let me look", false),
                new TranscriptEvent.ToolCall(NaruToolCallEvent.started(1,
                        new NaruToolCall("c1", "read_file", Map.of("path", "/p/A.java")))),
                new TranscriptEvent.ToolCall(NaruToolCallEvent.failed(1,
                        new NaruToolCall("c2", "write_file",
                                Map.of("path", "/p/B.java", "files", List.of("/p/C.java"))),
                        "denied", 4))));

        TurnActivity.Snapshot snapshot = activity.snapshot();
        assertEquals(TurnActivity.Kind.TOOL, snapshot.kind(),
                "the last thing that happened is the tool, not the thinking before it");
        assertEquals("write_file", snapshot.toolName());
        assertEquals(2, snapshot.toolCalls(), "start and a final-without-start each count once");
        assertEquals(1, snapshot.failedToolCalls());
        assertEquals(3, snapshot.filesTouched(), "a path and a list of files all count");
        assertTrue(snapshot.elapsedMillis() >= 0);
    }

    @Test
    void theSameCallIsNotCountedTwiceWhenItSettles() {
        TurnActivity activity = new TurnActivity();
        activity.beginTurn();
        NaruToolCall call = new NaruToolCall("same", "bash", Map.of("path", "/p/A.sh"));
        activity.accept(List.of(
                new TranscriptEvent.ToolCall(NaruToolCallEvent.started(1, call)),
                new TranscriptEvent.ToolCall(NaruToolCallEvent.result(1, call, "ok", 12))));
        assertEquals(1, activity.snapshot().toolCalls());
        assertEquals(1, activity.snapshot().filesTouched());
    }

    @Test
    void aNewTurnClearsThePreviousOne() {
        TurnActivity activity = new TurnActivity();
        activity.beginTurn();
        activity.accept(List.of(new TranscriptEvent.ToolCall(NaruToolCallEvent.started(1,
                new NaruToolCall("c1", "read_file", Map.of("path", "/p/A.java"))))));
        assertEquals(1, activity.snapshot().toolCalls());

        activity.beginTurn();
        TurnActivity.Snapshot fresh = activity.snapshot();
        assertEquals(0, fresh.toolCalls());
        assertEquals(0, fresh.filesTouched());
        assertEquals(TurnActivity.Kind.STARTING, fresh.kind());
    }

    @Test
    void chunksMoveTheKindFromThinkingToWriting() {
        TurnActivity activity = new TurnActivity();
        activity.beginTurn();
        activity.accept(List.of(new TranscriptEvent.Chunk("m1",
                NaruLogMode.MODEL_THINKING, "think", false)));
        assertEquals(TurnActivity.Kind.THINKING, activity.snapshot().kind());
        activity.accept(List.of(new TranscriptEvent.Chunk("m1",
                NaruLogMode.MODEL_RESPONSE, "answer", false)));
        assertEquals(TurnActivity.Kind.WRITING, activity.snapshot().kind());
    }

    @Test
    void outputFromAQuietSessionStartsItsOwnTurn() {
        TurnActivity activity = new TurnActivity();
        activity.accept(List.of(new TranscriptEvent.Chunk("m1",
                NaruLogMode.MODEL_RESPONSE, "scheduled answer", true)));
        TurnActivity.Snapshot snapshot = activity.snapshot();
        assertTrue(snapshot.active(),
                "a scheduled or restored answer is a turn even when submit() never ran");
        assertEquals(TurnActivity.Kind.WRITING, snapshot.kind());
    }
}
