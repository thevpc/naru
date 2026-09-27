package net.thevpc.naru.api.model;

/**
 * What a piece of model output <i>is</i>, independent of how it was produced.
 *
 * <p>Thinking is deliberately a kind of content rather than a separate
 * subsystem. A provider that returns reasoning in a dedicated field, a provider
 * that wraps it in {@code <think>} tags, and a provider that returns a single
 * finished blob all end up emitting the same kinds, so nothing downstream has
 * to know which mechanism was used.
 */
public enum NaruChunkKind {

    /**
     * Model reasoning, which the user may want shown but which is not the answer.
     */
    THINKING,

    /**
     * The answer itself: the text the user asked for.
     */
    ANSWER,

    /**
     * A request from the model to call a tool.
     */
    TOOL_CALL,

    /**
     * The result of a tool call, fed back to the model.
     */
    TOOL_RESULT
}
