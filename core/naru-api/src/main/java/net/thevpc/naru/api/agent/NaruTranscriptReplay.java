package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruThinkingSegment;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.nuts.log.NMsgIntent;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a stored conversation into the items a transcript should draw.
 *
 * <p>Why this is in the engine and not in a host: the durable record and the live stream describe
 * the same turn in two different shapes, and every host that has to translate one of them
 * eventually disagrees with the others. A live turn publishes a tool call as a typed
 * {@link NaruToolCallEvent} and its reasoning as {@link NaruLogMode#MODEL_THINKING} stream
 * fragments; the same turn, read back from the store, is an assistant message holding
 * {@code toolCalls} and {@code thinkingSegments} plus a {@code role=tool} reply that belongs to
 * neither. Without one agreed translation, a host guesses — and the guess the desktop made was
 * "assistant or engine means a generic agent message", which redrew every restored reasoning
 * block, tool card and progress line as the same "NARU" prose.
 *
 * <p>The rule it applies is the one {@code publishToInteraction} already uses for a live turn:
 * severity picks the channel, and the message's own {@code intent} and {@code level} are the
 * facts that decide it. Nothing here invents a classification the engine did not already make.
 *
 * <h2>Pairing</h2>
 * A call and its result are two messages apart: the assistant asks, the engine runs, and a
 * {@code role=tool} message answers under the same {@code toolCallId}. They become one card, so a
 * reloaded session shows the tool with its result exactly as the live turn did. A call with no
 * reply (interrupted mid-dispatch, or a parked interactive tool nobody answered) still becomes a
 * card, because a tool that ran and is missing from the transcript reads as a tool that never ran.
 */
public final class NaruTranscriptReplay {

    /**
     * Intent for content that is reasoning rather than an answer.
     *
     * <p>An intent, not a role: the {@code think} tool's scratchpad is genuinely engine output —
     * the engine wrote it into the history on the model's behalf — and it must survive being read
     * by a host that draws roles. Marking it lets such a host put it on the reasoning channel
     * instead of the prose one, which is the difference between a Thinking card and a message
     * that looks like the agent decided to explain itself.
     */
    public static final NMsgIntent THINKING = NMsgIntent.of("THINKING");

    private NaruTranscriptReplay() {
    }

    /**
     * The channel one message should be drawn on, ignoring any pairing with a later result.
     *
     * <p>Single-message form of {@link #replay}, for a host that receives durable messages one at
     * a time as they are checkpointed and only needs the classification to agree with the live
     * stream it already drew.
     */
    public static NaruLogMode modeOf(NaruMessage message) {
        NaruReplayItem item = message == null ? null
                : expand(message, -1).stream().findFirst().orElse(null);
        if (item != null) {
            return item.mode();
        }
        return NaruLogMode.AGENT_RESPONSE;
    }

    /**
     * Expand a whole conversation into drawable items, in history order.
     *
     * @param taskId the task the messages belong to, carried onto every tool card so a host keys a
     *               card on the same pair it uses for a live call
     * @param history the stored messages of one task, oldest first
     * @return one item per thing a transcript should show; never null, but a message that carries
     *         nothing to draw contributes no item
     */
    public static List<NaruReplayItem> replay(long taskId, List<NaruMessage> history) {
        List<NaruReplayItem> out = new ArrayList<>();
        if (history == null || history.isEmpty()) {
            return out;
        }
        // callId (or tool name, for a model that sent no id) -> the open card waiting for its result
        Map<String, Integer> openCalls = new HashMap<>();
        for (NaruMessage m : history) {
            if (m == null || m.getRole() == null) {
                continue;
            }
            if (m.getRole() == NaruRole.tool) {
                Integer at = openCalls.remove(toolKey(m.getToolCallId(), m.getToolName()));
                if (at != null) {
                    out.set(at, new NaruReplayItem(NaruLogMode.SCRIPT, null, null,
                            settledEvent(out.get(at).toolCall(), m)));
                    // the result is in the card now; drawing the message again would show "ok"
                    // as a sentence the agent supposedly said
                    continue;
                }
            }
            for (NaruReplayItem item : expand(m, taskId)) {
                out.add(item);
                if (item.isToolCall()) {
                    openCalls.put(eventKey(item.toolCall()), out.size() - 1);
                }
            }
        }
        for (int i = 0; i < out.size(); i++) {
            NaruReplayItem item = out.get(i);
            if (item.isToolCall() && !item.toolCall().isFinal()) {
                // never answered: the turn ended, or a crash cut the session short. Close the card
                // without inventing a result, so it reads as finished rather than still running
                out.set(i, new NaruReplayItem(NaruLogMode.SCRIPT, null, null,
                        NaruToolCallEvent.result(taskId, callOf(item.toolCall()), null,
                                NaruToolCallEvent.UNKNOWN_DURATION)));
            }
        }
        return out;
    }

    /**
     * Draw a stored conversation on the session's own display channels.
     *
     * <p>This is the seam that makes a reopen look the same in two hosts. The desktop raises typed
     * transcript events, but a terminal host has only {@link NaruSession#log(NaruLogMode, NMsg)} and
     * {@link NaruSession.reportToolCall}, and a host given those two calls will print whatever the
     * engine would have printed for the live turn. Routing the items through the session rather than
     * letting each host re-read the messages is what keeps the two from drifting: the classification
     * is done once, here, and both hosts draw the same reasoning block, the same tool card, the same
     * progress line.
     *
     * <p>Nothing is written back to history. This is a display path only, so reprinting a restored
     * session cannot duplicate it.
     */
    public static void publishTo(NaruSession session, long taskId, List<NaruMessage> history) {
        if (session == null) {
            return;
        }
        for (NaruReplayItem item : replay(taskId, history)) {
            if (item.isToolCall()) {
                session.reportToolCall(item.toolCall());
            } else if (item.text() != null) {
                session.log(item.mode(), item.text(), taskId);
            }
        }
    }

    /**
     * The items one message contributes, in the order the turn produced them: reasoning, then the
     * answer, then the calls it asked for.
     */
    private static List<NaruReplayItem> expand(NaruMessage m, long taskId) {
        List<NaruReplayItem> out = new ArrayList<>();
        NaruRole role = m.getRole();
        if (role == NaruRole.system) {
            // context material the model is given, never something the user said or the agent
            // answered; every host has always skipped it and none could tell why
            return out;
        }
        if (role == NaruRole.assistant) {
            List<NaruThinkingSegment> segments = m.getThinkingSegments();
            if (segments != null) {
                for (NaruThinkingSegment segment : segments) {
                    if (segment == null || NBlankable.isBlank(segment.getText())) {
                        continue;
                    }
                    out.add(new NaruReplayItem(NaruLogMode.MODEL_THINKING,
                            NMsg.ofP(segment.getText()), null, null));
                }
            } else if (!NBlankable.isBlank(m.getThinking())) {
                // a session written before segments existed keeps its reasoning as one string
                out.add(new NaruReplayItem(NaruLogMode.MODEL_THINKING,
                        NMsg.ofP(m.getThinking()), null, null));
            }
            if (hasText(m)) {
                out.add(new NaruReplayItem(NaruLogMode.MODEL_RESPONSE, m.getContent(), m, null));
            }
            if (m.getToolCalls() != null) {
                for (NaruToolCall call : m.getToolCalls()) {
                    // started, not settled: replay() folds this call's own result message in when
                    // the history holds one, and closes it without a result when it does not
                    out.add(new NaruReplayItem(NaruLogMode.SCRIPT, null, null,
                            NaruToolCallEvent.started(taskId, call)));
                }
            }
            return out;
        }
        if (role == NaruRole.tool) {
            // reached only when no open call matched it: a result with no recorded call is still
            // a tool that ran, so it becomes a card of its own instead of prose
            out.add(new NaruReplayItem(NaruLogMode.SCRIPT, null, null,
                    NaruToolCallEvent.result(taskId, orphanCall(m),
                            m.getFilteredContent(), NaruToolCallEvent.UNKNOWN_DURATION)));
            return out;
        }
        if (role == NaruRole.engine) {
            NMsg c = m.getContent();
            if (c == null || c.isBlank()) {
                return out;
            }
            // The same severity rule the live publish path uses, so a restored progress line is
            // still progress: transient narration that a later durable item supersedes. Anything
            // below FINE stays on the progress channel too — its stored level is what a host
            // filters on, and dropping it here would hide it from a log view that could show it.
            if (THINKING.equals(c.intent())) {
                out.add(new NaruReplayItem(NaruLogMode.MODEL_THINKING, c, null, null));
            } else if (c.normalizedLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                out.add(new NaruReplayItem(NaruLogMode.AGENT_RESPONSE, c, m, null));
            } else {
                out.add(new NaruReplayItem(NaruLogMode.PROGRESS, c, null, null));
            }
            return out;
        }
        if (role == NaruRole.user) {
            if (hasText(m)) {
                out.add(new NaruReplayItem(NaruLogMode.SCRIPT, m.getContent(), m, null));
            }
            return out;
        }
        // directive and summary: durable statements the user is owed, drawn as agent output
        if (hasText(m)) {
            out.add(new NaruReplayItem(role == NaruRole.directive ? NaruLogMode.DIRECTIVE
                    : NaruLogMode.AGENT_RESPONSE, m.getContent(), m, null));
        }
        return out;
    }

    private static boolean hasText(NaruMessage m) {
        NMsg c = m.getContent();
        if (c != null && !c.isBlank()) {
            return true;
        }
        return !m.getParts().isEmpty();
    }

    /**
     * Fold a stored {@code role=tool} result into the card its call already opened.
     *
     * <p>The duration is always {@link NaruToolCallEvent#UNKNOWN_DURATION}: a tool result message
     * records no dispatch time, so a restored card would have to claim a measurement that never
     * happened. The header a host draws from this event then shows the result length and omits the
     * time, which is what the console already does for an unknown.
     */
    private static NaruToolCallEvent settledEvent(NaruToolCallEvent started, NaruMessage result) {
        String text = result.getFilteredContent();
        boolean failed = text != null && text.startsWith("ERROR");
        return failed
                ? NaruToolCallEvent.failed(started.taskId(), callOf(started), text,
                NaruToolCallEvent.UNKNOWN_DURATION)
                : NaruToolCallEvent.result(started.taskId(), callOf(started), text,
                NaruToolCallEvent.UNKNOWN_DURATION);
    }

    /** The call a card was opened with, rebuilt as a value for a final event. */
    private static NaruToolCall callOf(NaruToolCallEvent event) {
        NaruToolCall call = new NaruToolCall();
        call.setId(event.callId());
        call.setName(event.toolName());
        call.setArguments(event.arguments());
        return call;
    }

    private static NaruToolCall orphanCall(NaruMessage m) {
        NaruToolCall call = new NaruToolCall();
        call.setId(m.getToolCallId());
        call.setName(m.getToolName());
        return call;
    }

    /** The key of the card an event opened, in the same terms as the call it came from. */
    private static String eventKey(NaruToolCallEvent event) {
        return toolKey(event.callId(), event.toolName());
    }

    private static String toolKey(String callId, String toolName) {
        // the id is what the model chose to send and some models send none, so the name is the
        // fallback — matching the key a live card is settled by (TranscriptModel.cardKey)
        return (callId == null || callId.isBlank() ? String.valueOf(toolName) : callId);
    }
}
