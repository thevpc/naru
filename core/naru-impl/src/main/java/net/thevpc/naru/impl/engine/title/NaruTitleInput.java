package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;

import java.util.ArrayList;
import java.util.List;

/**
 * The text a titler is allowed to look at, and nothing more.
 * <p>
 * Two shapes because the two jobs cost differently. A first message is the whole of what a
 * session knows about itself at the moment it is named, so the trigger pays one truncated
 * message. A regeneration is asked for because the first guess was wrong, so it gets to
 * read the conversation -- bounded, because a session with four hundred turns does not need
 * four hundred turns to be recognisable, and an unbounded excerpt would price a title at
 * the full session.
 * <p>
 * The window is deliberately first-few and last-few rather than a slice of the middle. The
 * opening states the intent; the end states what the session was actually doing when a
 * person decided it needed a better name. Everything between those two is usually the same
 * topic worked through.
 */
final class NaruTitleInput {

    /** A first message is a prompt, not a document; past this it is a different job. */
    static final int FIRST_MESSAGE_CAP = 2000;

    /** Per-message cap in an excerpt: one long paste should not crowd out the rest. */
    static final int EXCERPT_MESSAGE_CAP = 1000;

    /** Turns kept from each end of an excerpt. */
    static final int EXCERPT_HEAD_TURNS = 3;
    static final int EXCERPT_TAIL_TURNS = 3;

    /** Ceiling on the whole excerpt, so a session of long turns still costs a bounded call. */
    static final int EXCERPT_TOTAL_CAP = 6000;

    private NaruTitleInput() {
    }

    /**
     * The excerpt for one task's history.
     *
     * <p>Reads {@link NaruTask#history()} -- the full stored conversation -- rather than
     * {@code context()}, which is the compacted view a model is sent. Naming a session after
     * its compaction summary would name it after whatever the summarizer chose to keep, and
     * the summary's own text is written for retrieval, not for a label.
     *
     * @return the turns to send, or an empty list when the history holds nothing a title can
     *         be made from
     */
    static List<String> excerpt(NaruTask task) {
        List<String> turns = speakable(task);
        if (turns.isEmpty()) {
            return List.of();
        }
        List<String> window;
        if (turns.size() <= EXCERPT_HEAD_TURNS + EXCERPT_TAIL_TURNS) {
            // overlapping ends: take the whole thing once rather than repeat the middle
            window = new ArrayList<>(turns);
        } else {
            window = new ArrayList<>(turns.subList(0, EXCERPT_HEAD_TURNS));
            window.addAll(turns.subList(turns.size() - EXCERPT_TAIL_TURNS, turns.size()));
        }
        return capTotal(window);
    }

    /** The first user message of a task, truncated, or null when the task has none. */
    static String firstMessage(NaruTask task) {
        for (NaruMessage m : history(task)) {
            if (m.getRole() != NaruRole.user) {
                continue;
            }
            // A first message that arrived as a tool result or a directive is not what the
            // user came here to talk about, and titling from it names the session after the
            // plumbing.
            String text = text(m);
            if (text != null && !text.isEmpty()) {
                return truncate(text, FIRST_MESSAGE_CAP);
            }
        }
        return null;
    }

    /**
     * The turns worth reading, in order: user and assistant text only.
     *
     * <p>Excluded, and why each is excluded rather than merely trimmed. Tool calls and tool
     * results are a transcript of the mechanics; a session that spent twenty turns running
     * tests would be titled after a stack trace. Thinking is dropped to the model's own
     * filtered text, which is the answer without the reasoning. System and engine messages
     * describe the runtime, not the topic. Summaries are the compaction view this method
     * exists to avoid. Directives are commands the user typed to the shell.
     */
    private static List<String> speakable(NaruTask task) {
        List<String> out = new ArrayList<>();
        for (NaruMessage m : history(task)) {
            NaruRole role = m.getRole();
            if (role != NaruRole.user && role != NaruRole.assistant) {
                continue;
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                continue;
            }
            String text = text(m);
            if (text == null || text.isEmpty()) {
                continue;
            }
            out.add((role == NaruRole.user ? "user: " : "assistant: ")
                    + truncate(text, EXCERPT_MESSAGE_CAP));
        }
        return out;
    }

    private static List<NaruMessage> history(NaruTask task) {
        if (task == null) {
            return List.of();
        }
        List<NaruMessage> h = task.history();
        return h == null ? List.of() : h;
    }

    /** The message as the user would read it, or null. Never includes reasoning. */
    private static String text(NaruMessage m) {
        String filtered = m.getFilteredContent();
        if (filtered == null) {
            return null;
        }
        String s = filtered.strip();
        return s.isEmpty() ? null : s;
    }

    private static List<String> capTotal(List<String> turns) {
        List<String> out = new ArrayList<>(turns.size());
        int used = 0;
        for (String t : turns) {
            used += t.length();
            if (used > EXCERPT_TOTAL_CAP) {
                break;
            }
            out.add(t);
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + " […]";
    }
}
