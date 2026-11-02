package net.thevpc.naru.api.agent;

/**
 * What a titler is being asked to name, and therefore what it is allowed to read.
 *
 * <p>Two modes because the two jobs differ in cost and in how much they may look at.
 * An automatic first title runs on the first user message, which is the cheapest thing
 * that still says what the session is about -- and it is the only moment that fires
 * without being asked, so it must stay small. A regeneration happens because a person
 * asked for a better name, so it reads the conversation.
 */
public enum NaruTitleMode {
    /**
     * The first user message of the session's main task, truncated. Used by the automatic
     * trigger: at that point there is nothing else to read.
     */
    FIRST_MESSAGE,
    /**
     * A bounded excerpt of the stored history -- the first and last few user and assistant
     * turns. Used by {@code /session title} and the desktop's regenerate action.
     *
     * <p>Reads <b>stored</b> history, not the compacted context: a session whose
     * conversation was compacted has already lost its opening turns from what the model
     * sees, and titling from the compaction summary would name the session after whatever
     * the summarizer chose to keep.
     */
    EXCERPT
}
