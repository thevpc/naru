package net.thevpc.naru.api.model;

import java.util.List;

/**
 * The payload of a {@link NaruContextSegment}.
 *
 * <p>Sealed on purpose: a segment can only ever carry one of the two things a
 * provider request is actually built from, so a provider's serializer can switch
 * exhaustively on the type and be forced by the compiler to handle both. An
 * open-ended {@code Object} payload would let a caller smuggle in a shape no
 * serializer knows how to emit.
 */
public sealed interface NaruSegmentContent {

    /**
     * A run of conversation messages, in the order they must be emitted.
     */
    record Messages(List<NaruMessage> messages) implements NaruSegmentContent {
        public Messages {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public static Messages of(List<NaruMessage> messages) {
            return new Messages(messages);
        }

        public static Messages of(NaruMessage... messages) {
            return new Messages(List.of(messages));
        }
    }

    /**
     * The tool definitions for the request. These travel beside the messages
     * rather than inside them on every wire protocol, so they are modelled as
     * their own segment kind instead of being faked as a message.
     */
    record Tools(List<NaruToolDefinition> tools) implements NaruSegmentContent {
        public Tools {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static Tools of(List<NaruToolDefinition> tools) {
            return new Tools(tools);
        }
    }
}
