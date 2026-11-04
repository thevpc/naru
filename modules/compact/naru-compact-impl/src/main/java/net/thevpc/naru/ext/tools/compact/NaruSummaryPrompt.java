package net.thevpc.naru.ext.tools.compact;

/**
 * The summarizer's prompt, versioned.
 *
 * <p>The version is part of the compaction cache key. A summary is cached under the exact
 * instructions that produced it, so changing the prompt must invalidate every entry -- and
 * putting the version in the key is the only way that happens without a manual cache purge
 * that someone has to remember.
 *
 * <p>The prompt asks for a fixed section structure rather than free prose. A model asked to
 * "summarize" writes an essay whose useful half is in the first paragraph; asked for named
 * sections, it writes something that can be read in order and re-summarized later without
 * losing the structure. That matters more than it looks: a summary that will itself be
 * summarized has to survive a second pass.
 */
public final class NaruSummaryPrompt {

    /**
     * Bump whenever the instructions below change in any way.
     *
     * <p>Not cosmetic: two summaries of the same content under different prompts are
     * different answers, and sharing a cache entry between them would serve whichever was
     * written first regardless of which was asked for.
     */
    public static final String TEMPLATE_VERSION = "v1";

    /** Header used when a chunked summary is folded into the running summary. */
    public static final String CHUNK_HEADER =
            "This is part {index} of {total} of a conversation that is too long to summarize in one pass.\n"
                    + "You are given the summary so far, then the next part. Write the summary of the whole so far,\n"
                    + "keeping everything already known that is still relevant and adding what is new.\n"
                    + "Do not drop earlier decisions because a later part does not mention them.\n";

    /** Header used for the final pass that enforces the token cap. */
    public static final String SHORTEN_HEADER =
            "The summary below is longer than the allowed length. Rewrite it to fit, keeping every "
                    + "goal, decision, identifier and open item and dropping prose. Do not add anything new.\n";

    private NaruSummaryPrompt() {
    }

    /**
     * The main summarization prompt.
     *
     * @param focus   optional hint about what the user cares about; empty means no hint
     * @param level   the effort level, named so the model knows how hard to work
     * @param maxTokens the cap, or -1 when none was set
     */
    public static String instruction(String focus, String level, long maxTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are compacting a conversation between a user and a coding agent, so that the ")
                .append("agent can keep working in the same session after the earlier turns are removed ")
                .append("from its context.\n\n");

        sb.append("Write a summary of the conversation below. This summary replaces the original in the ")
                .append("agent's context, so it must contain everything the agent needs to continue ")
                .append("without re-reading what was removed.\n\n");

        sb.append("KEEP, in this order of importance:\n");
        sb.append("1. The user's goals and constraints, including anything they said must NOT change.\n");
        sb.append("2. Decisions that were made, and the reasoning behind them.\n");
        sb.append("3. The current state: what is done, what is in progress, what the last action was.\n");
        sb.append("4. File paths, class and symbol names, identifiers, commands and arguments. ")
                .append("Copy these exactly; a paraphrased path is worse than no path.\n");
        sb.append("5. Errors that have not been resolved, with what was tried.\n");
        sb.append("6. Open TODOs and the next step.\n\n");

        sb.append("OMIT: greetings, acknowledgements, restatements of the question, and the full text of ")
                .append("tool output. Tool output is summarized as its effect, not quoted.\n\n");

        sb.append("Write plain text under exactly these headings, in this order, omitting a heading ")
                .append("only when it has no content:\n");
        sb.append("Goal\nDecisions\nState\nFiles & identifiers\nOpen items\n\n");

        sb.append("Be specific and terse. No preamble, no closing remarks, no markdown code fences.\n");

        sb.append("Effort level: ").append(level).append('.');
        if ("AGGRESSIVE".equals(level)) {
            sb.append(" At this level keep ONLY goals, decisions, current state, open TODOs, and the ")
                    .append("paths and identifiers that refer to them. Drop everything else entirely.");
        }
        sb.append('\n');

        if (maxTokens > 0) {
            sb.append("The summary must be at most ").append(maxTokens)
                    .append(" tokens. Aim well under it, since an over-length summary will be rejected ")
                    .append("and rewritten. Prefer cutting detail over cutting a decision.\n");
        }

        if (focus != null && !focus.isBlank()) {
            sb.append("Pay particular attention to this, and give it more space than the rest:\n")
                    .append(focus.trim()).append('\n');
        }
        return sb.toString();
    }

    /** The instruction for folding chunk {@code index} of {@code total} into a running summary. */
    public static String chunkInstruction(String focus, long maxTokens, int index, int total) {
        return CHUNK_HEADER.replace("{index}", Integer.toString(index))
                .replace("{total}", Integer.toString(total))
                + '\n'
                + instruction(focus, "NORMAL", maxTokens);
    }

    /** The instruction for the final length-enforcing pass. */
    public static String shortenInstruction(long maxTokens) {
        return SHORTEN_HEADER + "\nHard limit: " + maxTokens + " tokens. Output only the rewritten summary.\n";
    }
}