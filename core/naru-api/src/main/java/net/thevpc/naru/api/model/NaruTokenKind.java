package net.thevpc.naru.api.model;

/**
 * Which part of a model call a token belongs to.
 *
 * <p>Split out because reasoning is billed and metered differently from visible
 * output, and the difference is invisible if both are folded into one
 * "completion" number: a long think followed by a one-line answer looks
 * identical to a long answer, even though they cost very different amounts and
 * mean very different things when read back from a session.
 */
public enum NaruTokenKind {
    /**
     * Tokens the provider read: the system prompt, history, and tool results.
     */
    INPUT,
    /**
     * Tokens the model produced that the user sees as the answer.
     */
    OUTPUT,
    /**
     * Tokens the model spent reasoning before or instead of answering.
     *
     * <p>Counted against consumption and limits, but kept separate from
     * {@link #OUTPUT} so a transcript can show what the model actually thought
     * rather than only what it said.
     */
    THINKING;

    /**
     * Whether tokens of this kind occupy the model's context window.
     *
     * <p>All three do. Reasoning is not free: it is generated text, and it has
     * to be carried back in on the following request, so a context budget that
     * ignores it under-counts by however much the model thought.
     */
    public boolean consumesContext() {
        return true;
    }

    /**
     * Whether tokens of this kind are charged as model output.
     *
     * <p>True for {@link #OUTPUT} and {@link #THINKING}, since providers bill
     * reasoning tokens at output rates.
     */
    public boolean isBilled() {
        return this != INPUT;
    }

    /**
     * The wire/display name, matching the token-kind names providers use in
     * their usage payloads.
     */
    public String wireName() {
        return switch (this) {
            case INPUT -> "input_tokens";
            case OUTPUT -> "output_tokens";
            case THINKING -> "thinking_tokens";
        };
    }
}
