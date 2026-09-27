package net.thevpc.naru.ext.models.stream;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * Pull-based reader for {@code text/event-stream} bodies.
 *
 * <p>Exists because the wire format is not line-delimited JSON and assuming it
 * is gets it wrong in ways that only show up against a real server:
 *
 * <ul>
 *   <li>a frame's payload can be split across several {@code data:} lines, which
 *       must be rejoined with a newline rather than concatenated;</li>
 *   <li>a comment line beginning with {@code :} is a keep-alive, not an event,
 *       and a long silent generation is mostly keep-alives;</li>
 *   <li>a server that closes the connection right after the last frame has no
 *       trailing blank line, so a pending frame has to be dispatched at
 *       end-of-input or the final token is lost.</li>
 * </ul>
 *
 * <p>It reads on demand from the underlying {@link Reader} rather than
 * pre-splitting, which is what makes it a real stream: a frame is handed back as
 * soon as its terminating blank line arrives, and nothing waits for the
 * response to end.
 *
 * <p>Not thread-safe, and not meant to be: a stream has one reader, on the
 * thread that made the call.
 */
public class NaruSseReader implements Closeable {

    /**
     * One dispatched server-sent event.
     *
     * @param name the {@code event:} field, or {@code null} when the server did
     *             not name the event -- which for OpenAI-compatible streams
     *             means the default {@code message} type
     * @param data the {@code data:} lines joined with {@code \n}, never
     *             {@code null} but possibly empty
     * @param id   the {@code id:} field, or {@code null}
     */
    public record Event(String name, String data, String id) {
        public static Event of(String data) {
            return new Event(null, data, null);
        }
    }

    /**
     * The end-of-stream sentinel every OpenAI-compatible server sends, as a
     * bare data payload. Not a JSON document, so it can never arrive as one.
     */
    public static final String DONE = "[DONE]";

    private final BufferedReader reader;
    private final StringBuilder data = new StringBuilder();
    private String name;
    private String id;
    /**
     * Whether any field at all has been seen, which is what makes a frame worth
     * dispatching. Kept apart from {@link #hasData} because a frame may legally
     * start with {@code event:} or {@code id:} and still be the first thing to
     * contribute to the data buffer.
     */
    private boolean sawField;
    private boolean hasData;
    private boolean endOfInput;
    private boolean closed;

    public NaruSseReader(Reader reader) {
        // Buffered because readLine is called per frame and an unbuffered reader
        // would turn every character into a syscall on the transport.
        this.reader = reader instanceof BufferedReader
                ? (BufferedReader) reader
                : new BufferedReader(reader);
    }

    /**
     * Convenience for tests and for replaying a captured body.
     */
    public static NaruSseReader of(String sseBody) {
        return new NaruSseReader(new StringReader(sseBody == null ? "" : sseBody));
    }

    /**
     * The next event, blocking until one is complete.
     *
     * @return the event, or {@code null} once the stream is exhausted
     * @throws IOException if the underlying transport fails
     */
    public Event read() throws IOException {
        if (endOfInput) {
            return null;
        }
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                endOfInput = true;
                return dispatchPending();
            }
            if (line.isEmpty()) {
                // A blank line terminates the frame. A frame with no field at
                // all is the extra keep-alive blank some servers send, and
                // dispatching it would hand the caller an empty event.
                Event event = dispatchPending();
                if (event != null) {
                    return event;
                }
                continue;
            }
            acceptLine(line);
        }
    }

    /**
     * Consumes the whole remaining stream, which is what a caller that only
     * wants the assembled response does.
     */
    public void readAll() throws IOException {
        while (read() != null) {
            // discarding
        }
    }

    private void acceptLine(String line) {
        if (line.charAt(0) == ':') {
            // comment / keep-alive
            return;
        }
        int colon = line.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            // A line with no colon is a field with an empty value, per the spec.
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
        }
        switch (field) {
            case "data": {
                // Joined with a newline, not appended: a payload split over
                // several data lines is one document and JSON does not allow a
                // raw newline inside a string. The join keys off the data buffer
                // alone, so a preceding event:/id: line does not prepend a
                // newline to the payload.
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
                sawField = true;
                break;
            }
            case "event": {
                name = value;
                sawField = true;
                break;
            }
            case "id": {
                // A NUL in an id is explicitly ignored by the spec, and letting
                // it through would corrupt resumption bookkeeping.
                if (value.indexOf('\0') < 0) {
                    id = value;
                }
                sawField = true;
                break;
            }
            case "retry": {
                // reconnection hint; this reader never reconnects
                sawField = true;
                break;
            }
            default: {
                // unknown field: ignored, but it did start a frame
                sawField = true;
                break;
            }
        }
    }

    private Event dispatchPending() {
        if (!sawField) {
            return null;
        }
        Event event = new Event(name, data.toString(), id);
        data.setLength(0);
        name = null;
        id = null;
        sawField = false;
        hasData = false;
        return event;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        reader.close();
    }
}
