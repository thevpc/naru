package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruSummaryLevel;

/**
 * Turns a block of conversation into summary text.
 *
 * <p>An interface rather than a concrete LLM call so the interesting behaviour -- chunking,
 * length enforcement, the escalation path when a model ignores the cap -- can be tested
 * against a counting fake, and so a deployment can swap in a different summarizer without
 * touching the compactor.
 *
 * <p>Implementations must be safe to call concurrently: the compaction cache uses single
 * flight, so two tasks can be waiting on one summarization, and a stateful implementation
 * would serve one of them the other's conversation.
 */
public interface NaruSummarizer {

    /**
     * Summarizes a rendered conversation.
     *
     * @param content   the conversation as text, already rendered and already had thinking
     *                  segments removed
     * @param focus     optional hint, may be null
     * @param level     the effort level in force
     * @param maxTokens hard cap on the result, or -1 for none
     * @return the summary text
     * @throws Exception any failure; the caller treats it as "this attempt did not work" and
     *                   moves on, and never leaves partial state behind
     */
    String summarize(String content, String focus, NaruSummaryLevel level, long maxTokens) throws Exception;
}