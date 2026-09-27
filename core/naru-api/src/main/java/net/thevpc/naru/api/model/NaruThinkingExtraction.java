package net.thevpc.naru.api.model;

/**
 * Where a piece of thinking content came from.
 *
 * <p>Recorded rather than discarded because the two mechanisms are not
 * equivalent in what they promise. A native field is the provider telling us
 * this is reasoning; a tag is a convention we inferred from delimiters, which a
 * model can get wrong by mentioning {@code <think>} in its answer. A future
 * pass that prunes or summarises accumulated thinking needs to know which it was
 * looking at, and the extraction mode is the cheapest way to tell a reliable
 * segment from a guessed one.
 */
public enum NaruThinkingExtraction {

    /**
     * The provider returned reasoning in a dedicated field
     * ({@code message.thinking}, {@code reasoning_content}, a content block,
     * and so on), so no parsing was involved.
     */
    NATIVE_FIELD,

    /**
     * Reasoning was delimited inline in the text stream, split out by a
     * stateful tag parser.
     */
    TAG_DELIMITED
}
