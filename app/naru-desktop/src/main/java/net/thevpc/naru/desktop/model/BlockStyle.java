package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;

import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

/**
 * How each of the nine {@link NaruLogMode} values is presented. The REPL renders these as nine
 * ANSI gutter colours and nothing else; here the mode decides the label, whether the body is
 * markdown or fixed-width, and how the block answers the three questions below.
 * <p>
 * v1 requirement: every mode has a defined rendering (plan §6).
 *
 * <h2>The three questions</h2>
 * A log mode is not one property, and treating it as one (a single {@code verboseOnly} flag) is
 * what made the conversation view confusing: it put "engine narration" and "raw debug dump" in the
 * same bucket. A mode really answers three independent questions:
 * <ul>
 *   <li>{@link #audience()} — <b>who is this for?</b> {@link Audience#CONVERSATION} is content the
 *       Conversation tab shows by default; {@link Audience#ENGINE_DETAIL} is mechanics a user only
 *       asks for with the Activity toggle.</li>
 *   <li>{@link #lifecycle()} — <b>does it stay?</b> {@link Lifecycle#DURABLE} blocks are the record;
 *       {@link Lifecycle#TRANSIENT} blocks narrate a turn and are dropped from the conversation once
 *       a real message lands (the Logs tab keeps them).</li>
 *   <li>{@link #collapsedByDefault()} — <b>is it folded?</b> A long, rarely-the-point block is shown
 *       but collapsed, so it is one click away instead of scrolled past.</li>
 * </ul>
 * {@code LEVEL} is not one of the nine modes; a single mode can be logged at several levels. It is
 * deliberately separate so a style — which is fixed per mode — never has to guess, and so
 * {@link #TRACE}/{@link #DEBUG} can move behind Activity without pretending to be a different mode.
 */
public record BlockStyle(String label,
                         Color gutter,
                         Color foreground,
                         boolean markdown,
                         boolean monospace,
                         boolean collapsedByDefault,
                         Audience audience,
                         Lifecycle lifecycle) {

    /** Who a block is written for. */
    public enum Audience {
        /** The conversation: the user is reading this on purpose. */
        CONVERSATION,
        /** Engine mechanics: shown only when the user asks for detail. */
        ENGINE_DETAIL
    }

    /** Whether a block is part of the record or narrates a turn. */
    public enum Lifecycle {
        /** Stays. It is either durable history or the answer the user asked for. */
        DURABLE,
        /** Superseded by the next real message; the Logs tab still keeps it. */
        TRANSIENT
    }

    private static final Map<NaruLogMode, BlockStyle> STYLES = new EnumMap<>(NaruLogMode.class);

    static {
        // The answer, as the model wrote it. It streams here, then the durable assistant message
        // promotes this block (TranscriptModel.streamedAnswerFor) so one answer is one card rather
        // than a raw echo plus a message.
        STYLES.put(NaruLogMode.MODEL_RESPONSE,
                style("Answer", new Color(0x455A64), false, false, false, Audience.CONVERSATION, Lifecycle.DURABLE));
        // A reply to a directive (a path, a listing) and, on the error path, a turn-fatal message.
        // Both are things the user must see: a directive's result is why they typed it, and a
        // failure they never saw is a failure they cannot act on.
        STYLES.put(NaruLogMode.AGENT_RESPONSE,
                style("Agent", new Color(0x2E7D32), true, false, false, Audience.CONVERSATION, Lifecycle.DURABLE));
        // The model's reasoning: long, the answer is the point, so show it folded. It is not
        // transient — reasoning about a finished turn is still worth re-reading.
        STYLES.put(NaruLogMode.MODEL_THINKING,
                style("Thinking", new Color(0x7B1FA2), false, false, true, Audience.CONVERSATION, Lifecycle.DURABLE));
        // Tool / shell / session output. A tool call has its own card, so what lands here is the
        // output that has no structured event: warnings, session notices, command results.
        STYLES.put(NaruLogMode.SCRIPT,
                style("Output", new Color(0x00695C), false, true, false, Audience.CONVERSATION, Lifecycle.DURABLE));
        // Narration while the turn runs: "Model: gpt-4o…", "Tool: file_read", tool results,
        // "⏹ Interrupted.". Worth seeing live; superseded by the message it announces. Errors
        // logged in this mode are reclassified durable at the event level (NaruSessionModel), so
        // this TRANSIENT default never drops a failure.
        STYLES.put(NaruLogMode.PROGRESS,
                style("Progress", new Color(0xEF6C00), false, false, false, Audience.CONVERSATION, Lifecycle.TRANSIENT));
        // Engine mechanics, revealed by Activity. The engine itself already suppresses SCHEDULER
        // and TRACE unless tracing is on (NaruSessionImpl), so this is the second net, not the
        // only one.
        STYLES.put(NaruLogMode.SCHEDULER,
                style("Scheduler", new Color(0x607D8B), false, true, true, Audience.ENGINE_DETAIL, Lifecycle.DURABLE));
        STYLES.put(NaruLogMode.DEBUG,
                style("Debug", new Color(0x795548), false, true, true, Audience.ENGINE_DETAIL, Lifecycle.DURABLE));
        STYLES.put(NaruLogMode.TRACE,
                style("Trace", new Color(0x9E9E9E), false, true, true, Audience.ENGINE_DETAIL, Lifecycle.DURABLE));
        // The assembled prompt, verbatim. A debug artifact, not conversation, and emitted once.
        STYLES.put(NaruLogMode.RAW,
                style("Raw", new Color(0x37474F), false, true, true, Audience.ENGINE_DETAIL, Lifecycle.DURABLE));
    }

    /** The audience and lifecycle of a mode, for callers that only need those. */
    public static BlockStyle of(NaruLogMode mode) {
        BlockStyle s = STYLES.get(mode);
        return s != null ? s : style(mode.name(), Color.GRAY, false, false, true,
                Audience.ENGINE_DETAIL, Lifecycle.DURABLE);
    }

    /**
     * A tool card is not a log mode: it is a block the host built from an E1 lifecycle event, and
     * it borrows {@code SCRIPT}'s audience so the conversation shows it. Its own style says "Tool"
     * and draws it fixed-width, which is what a JSON result wants.
     */
    public static final BlockStyle TOOL = style("Tool", new Color(0x0277BD), false, true, false,
            Audience.CONVERSATION, Lifecycle.DURABLE);

    public static BlockStyle forBlock(TranscriptBlock b) {
        if (b != null && b.isToolCard()) {
            return TOOL;
        }
        if (b != null && b.isDirectiveResult()) {
            // A directive result is a reply to something the user typed, so it is conversation
            // whatever mode the directive logged it in.
            return style("Command", new Color(0x0277BD), false, true, false,
                    Audience.CONVERSATION, Lifecycle.DURABLE);
        }
        return of(b == null ? null : b.mode());
    }

    private static BlockStyle style(String label, Color gutter, boolean markdown, boolean monospace,
                                    boolean collapsed, Audience audience, Lifecycle lifecycle) {
        // foreground follows the gutter hue so a dark PLAF still reads; the widget tints further
        return new BlockStyle(label, gutter, gutter.darker(), markdown, monospace, collapsed,
                audience, lifecycle);
    }
}
