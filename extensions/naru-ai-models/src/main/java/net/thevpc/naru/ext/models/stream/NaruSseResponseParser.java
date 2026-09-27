package net.thevpc.naru.ext.models.stream;

import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.nuts.net.NHttpResponse;

import java.io.IOException;

/**
 * Base for protocols whose stream arrives as {@code text/event-stream}.
 *
 * <p>Handles the transport-shaped work that is identical for every SSE protocol
 * -- opening the body, splitting frames, stopping on a cancel -- and leaves the
 * protocol-specific part, {@link #onEvent}, to the subclass. Anthropic's stream
 * and OpenAI's differ in payload shape but not in framing, and framing is where
 * the subtle bugs live.
 *
 * <p>A read that stops early is reported as interrupted rather than as a
 * successful end of stream, so a cancel or a dropped connection cannot be
 * mistaken for a model that finished its thought.
 */
public abstract class NaruSseResponseParser implements NaruStreamResponseParser {

    @Override
    public NaruResponse read(NHttpResponse response) throws IOException {
        boolean interrupted = false;
        try (NaruSseReader reader = new NaruSseReader(response.content().asReader())) {
            NaruSseReader.Event event;
            while ((event = reader.read()) != null) {
                if (isCancelled()) {
                    interrupted = true;
                    break;
                }
                if (!onEvent(event.name(), event.data())) {
                    // The protocol signalled its own end; not an interruption.
                    break;
                }
            }
        }
        return finish(interrupted);
    }

    /**
     * Consumes one event payload.
     *
     * @param name the {@code event:} field, or {@code null} for the default type
     * @param data the {@code data:} lines, already rejoined
     * @return {@code false} to stop reading, which is how a protocol reports a
     *         clean end of stream
     */
    public abstract boolean onEvent(String name, String data);

    /**
     * Assembles the response once reading has stopped.
     *
     * @param interrupted {@code true} when reading stopped before the protocol's
     *                    own end-of-stream marker
     */
    public abstract NaruResponse finish(boolean interrupted);

    /**
     * Polled between events, which is where a cancel lands.
     */
    public boolean isCancelled() {
        return false;
    }
}
