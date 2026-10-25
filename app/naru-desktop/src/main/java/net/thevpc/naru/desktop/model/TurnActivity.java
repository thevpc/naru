package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.desktop.host.TranscriptEvent;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the current turn is doing, for the live strip above the composer.
 * <p>
 * This is a small projection of the same {@link TranscriptEvent} batches the transcript consumes:
 * it does not render, poll, or touch the engine. Its job is to answer three questions cheaply while
 * a turn runs:
 * <ul>
 *   <li>what kind of work is happening now (thinking, writing, a tool, waiting)?</li>
 *   <li>which tool, and which file did it name, so the verb can be specific?</li>
 *   <li>how much has happened since the reader sent the last line (tools, failures, files, time)?</li>
 * </ul>
 * The summary is deliberately internal: it counts the events this turn produced, not the files in
 * the worktree. The live strip gets its file-level +/− counts from a separate, bounded git read
 * ({@code GitChangeSummary}), so a missing git executable or a slow worktree never affects the
 * activity model or the transcript. Git statistics also stay in the Workbench/Diff panel, which
 * has the full unified diff.
 * <p>
 * EDT-confined, Swing-free, and not thread-safe, like {@link TranscriptModel}.
 */
public final class TurnActivity {

    public enum Kind {
        IDLE,
        STARTING,
        THINKING,
        WRITING,
        TOOL,
        WORKING,
        WAITING
    }

    /** Immutable view for a widget; {@link #startedAtMillis()} is 0 when no turn is active. */
    public record Snapshot(Kind kind,
                           String toolName,
                           String lastFile,
                           int toolCalls,
                           int failedToolCalls,
                           int filesTouched,
                           long startedAtMillis,
                           long elapsedMillis) {

        public boolean active() {
            return kind != Kind.IDLE && startedAtMillis > 0;
        }
    }

    private static final Set<String> FILE_KEYS = Set.of(
            "path", "paths", "file", "files", "filename", "filenames",
            "target", "targets", "source", "destination", "notebook_path");

    private Kind kind = Kind.IDLE;
    private String toolName;
    private String lastFile;
    private long startedAtMillis;
    private long lastEventAtMillis;
    private int toolCalls;
    private int failedToolCalls;
    private final Set<String> files = new LinkedHashSet<>();
    private final Set<String> countedCalls = new LinkedHashSet<>();

    /**
     * Begin a new turn. Called when a user line or directive is actually sent, not when it is
     * merely queued: a queued line has not reached the engine and has not changed its state yet.
     */
    public void beginTurn() {
        kind = Kind.STARTING;
        toolName = null;
        lastFile = null;
        toolCalls = 0;
        failedToolCalls = 0;
        files.clear();
        countedCalls.clear();
        startedAtMillis = System.currentTimeMillis();
        lastEventAtMillis = startedAtMillis;
    }

    /** The session is booting; there is no turn yet, but the strip can say "getting ready". */
    public void starting() {
        if (kind == Kind.IDLE) {
            startedAtMillis = System.currentTimeMillis();
            lastEventAtMillis = startedAtMillis;
        }
        kind = Kind.STARTING;
    }

    /** The session stopped, the tab was reloaded, or the turn ended without a message. */
    public void idle() {
        kind = Kind.IDLE;
        toolName = null;
        lastFile = null;
    }

    /** Feed one flushed batch, in order. The transcript applies the same batch beside this. */
    public void accept(List<TranscriptEvent> batch) {
        if (batch == null) {
            return;
        }
        for (TranscriptEvent event : batch) {
            accept(event);
        }
    }

    private void accept(TranscriptEvent event) {
        if (event == null) {
            return;
        }
        // A scheduled or restored conversation can speak without going through submit(). The first
        // output of a quiet session is still a turn: start the clock here so elapsed time and the
        // per-turn counters are not measured from the moment the window opened.
        if (kind == Kind.IDLE
                && (event instanceof TranscriptEvent.Chunk || event instanceof TranscriptEvent.ToolCall)) {
            beginTurn();
        }
        lastEventAtMillis = System.currentTimeMillis();
        if (event instanceof TranscriptEvent.Chunk chunk) {
            noteChunk(chunk);
        } else if (event instanceof TranscriptEvent.ToolCall toolCall) {
            noteTool(toolCall.call());
        } else if (event instanceof TranscriptEvent.InputRequested) {
            kind = Kind.WAITING;
        } else if (event instanceof TranscriptEvent.HostClosed) {
            idle();
        } else if (event instanceof TranscriptEvent.Message message) {
            // A durable assistant message is the turn's answer, even if no chunk was flushed for it
            // (a restored or host-injected message). Never let a user message reset the counters:
            // restored history would otherwise look like the first line of a new turn.
            if (message.message() != null
                    && message.message().getRole() == net.thevpc.naru.api.agent.NaruRole.assistant
                    && kind != Kind.WAITING) {
                kind = Kind.WRITING;
            }
        }
    }

    private void noteChunk(TranscriptEvent.Chunk chunk) {
        if (chunk.text() == null || chunk.text().isBlank()) {
            return;
        }
        NaruLogMode mode = chunk.mode();
        if (mode == null) {
            return;
        }
        switch (mode) {
            case MODEL_THINKING -> kind = Kind.THINKING;
            case MODEL_RESPONSE, AGENT_RESPONSE -> kind = Kind.WRITING;
            case SCRIPT -> kind = Kind.WORKING;
            case PROGRESS, SCHEDULER -> {
                // Narration alone does not override a structured tool/thinking state; it is only
                // "working" when nothing more specific has happened yet.
                if (kind == Kind.IDLE || kind == Kind.STARTING) {
                    kind = Kind.WORKING;
                }
            }
            default -> {
                // Trace/Debug/Raw are engine detail: they do not describe user-visible activity.
            }
        }
    }

    private void noteTool(NaruToolCallEvent call) {
        if (call == null) {
            return;
        }
        if (call.toolName() != null && !call.toolName().isBlank()) {
            toolName = call.toolName();
        }
        String key = call.taskId() + "\u0000"
                + (call.callId() == null || call.callId().isBlank() ? String.valueOf(call.toolName()) : call.callId());
        // STARTED and the matching final event are separate; count the call once even if the host
        // sees a final event for a call whose start was dropped under load.
        if (countedCalls.add(key)) {
            toolCalls++;
        }
        noteFiles(call.arguments());
        switch (call.kind()) {
            case STARTED -> kind = Kind.TOOL;
            case PARKED -> kind = Kind.WAITING;
            case FAILED -> {
                failedToolCalls++;
                kind = Kind.TOOL;
            }
            case RESULT -> {
                // Keep the specific verb until the next chunk says what the model does with the
                // result. The strip is about the turn, not only the last microsecond.
                if (kind != Kind.WAITING) {
                    kind = Kind.TOOL;
                }
            }
        }
    }

    private void noteFiles(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getKey() == null
                    || !FILE_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            collectFiles(entry.getValue());
        }
    }

    private void collectFiles(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            String path = text.strip();
            if (!path.isEmpty() && !path.contains("://")) {
                files.add(path);
                lastFile = path;
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectFiles(item);
            }
        } else if (value instanceof Object[] array) {
            for (Object item : array) {
                collectFiles(item);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectFiles(item);
            }
        }
    }

    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        return new Snapshot(kind, toolName, lastFile, toolCalls, failedToolCalls, files.size(),
                startedAtMillis, startedAtMillis == 0 ? 0 : Math.max(0, now - startedAtMillis));
    }

    /** When the last event arrived; the busy check uses it to bridge gaps between polls. */
    public long lastEventAtMillis() {
        return lastEventAtMillis;
    }
}
