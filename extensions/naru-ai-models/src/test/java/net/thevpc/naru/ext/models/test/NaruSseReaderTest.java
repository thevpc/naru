package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.ext.models.stream.NaruSseReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Framing tests for the SSE reader.
 *
 * <p>These are the cases that only fail against a real server: a body that ends
 * without its final blank line, keep-alives interleaved with data, a payload
 * split over several {@code data:} lines, and a transport that hands over one
 * character at a time.
 */
public class NaruSseReaderTest {

    private List<NaruSseReader.Event> readAll(String body) throws IOException {
        List<NaruSseReader.Event> events = new ArrayList<>();
        try (NaruSseReader reader = NaruSseReader.of(body)) {
            NaruSseReader.Event event;
            while ((event = reader.read()) != null) {
                events.add(event);
            }
        }
        return events;
    }

    private List<String> dataOf(String body) throws IOException {
        List<String> out = new ArrayList<>();
        for (NaruSseReader.Event event : readAll(body)) {
            out.add(event.data());
        }
        return out;
    }

    @Test
    public void aSimpleFrameIsRead() throws IOException {
        Assertions.assertEquals(List.of("{\"a\":1}"),
                dataOf("data: {\"a\":1}\n\n"));
    }

    @Test
    public void framesArriveInOrder() throws IOException {
        Assertions.assertEquals(List.of("one", "two", "three"),
                dataOf("data: one\n\ndata: two\n\ndata: three\n\n"));
    }

    @Test
    public void commentsAreNotEvents() throws IOException {
        // a keep-alive during a long silent generation
        String body = ": ping\n\n" + "data: real\n\n" + ": ping\n\n" + "data: also-real\n\n";
        Assertions.assertEquals(List.of("real", "also-real"), dataOf(body));
    }

    @Test
    public void multiLineDataIsJoinedWithNewline() throws IOException {
        // concatenation instead of joining would produce invalid JSON
        String body = "data: {\"name\":\ndata: \"value\"}\n\n";
        Assertions.assertEquals(List.of("{\"name\":\n\"value\"}"), dataOf(body));
    }

    @Test
    public void aFinalFrameWithoutABlankLineIsStillDelivered() throws IOException {
        // many servers close the socket right after the last frame
        Assertions.assertEquals(List.of("last"), dataOf("data: last"));
    }

    @Test
    public void aFinalFrameWithABlankLineIsDelivered() throws IOException {
        Assertions.assertEquals(List.of("last"), dataOf("data: last\n\n"));
    }

    @Test
    public void onlyOneLeadingSpaceIsStripped() throws IOException {
        Assertions.assertEquals(List.of(" padded"), dataOf("data:  padded\n\n"));
    }

    @Test
    public void aFieldWithNoSpaceAfterTheColonIsRead() throws IOException {
        Assertions.assertEquals(List.of("tight"), dataOf("data:tight\n\n"));
    }

    @Test
    public void aFieldWithNoColonIsAFieldWithAnEmptyValue() throws IOException {
        Assertions.assertEquals(List.of(""), dataOf("data\n\n"));
    }

    @Test
    public void eventNameAndIdAreCaptured() throws IOException {
        List<NaruSseReader.Event> events = readAll(
                "event: message\nid: 42\ndata: payload\n\n");
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals("message", events.get(0).name());
        Assertions.assertEquals("42", events.get(0).id());
        Assertions.assertEquals("payload", events.get(0).data());
    }

    @Test
    public void stateDoesNotLeakBetweenFrames() throws IOException {
        // a second frame with no name must not inherit the first frame's name
        List<NaruSseReader.Event> events = readAll(
                "event: first\ndata: a\n\ndata: b\n\n");
        Assertions.assertEquals("first", events.get(0).name());
        Assertions.assertNull(events.get(1).name());
    }

    @Test
    public void crlfLineEndingsAreHandled() throws IOException {
        Assertions.assertEquals(List.of("a", "b"), dataOf("data: a\r\n\r\ndata: b\r\n\r\n"));
    }

    @Test
    public void loneCarriageReturnLineEndingsAreHandled() throws IOException {
        Assertions.assertEquals(List.of("a", "b"), dataOf("data: a\r\rdata: b\r\r"));
    }

    @Test
    public void anEmptyBodyYieldsNoEvents() throws IOException {
        Assertions.assertTrue(readAll("").isEmpty());
    }

    @Test
    public void blankLinesOnlyYieldNoEvents() throws IOException {
        Assertions.assertTrue(readAll("\n\n\n\n").isEmpty());
    }

    @Test
    public void aBodyDeliveredOneCharacterAtATimeStillFrames() throws IOException {
        // the transport is free to split anywhere; a frame boundary must not
        // depend on where a read happens to end
        String body = ": keep-alive\n\n"
                + "data: {\"delta\":\"hel\"}\n\n"
                + "event: message\ndata: [DONE]\n\n";
        try (NaruSseReader reader = new NaruSseReader(new OneByteAtATime(body))) {
            List<String> data = new ArrayList<>();
            NaruSseReader.Event event;
            while ((event = reader.read()) != null) {
                data.add(event.data());
            }
            Assertions.assertEquals(List.of("{\"delta\":\"hel\"}", "[DONE]"), data);
        }
    }

    @Test
    public void aFrameIsReturnedBeforeTheStreamEnds() throws IOException {
        // the whole point: a frame must be available as soon as it is complete,
        // not after the connection closes
        String body = "data: early\n\ndata: late\n\n";
        try (NaruSseReader reader = new NaruSseReader(new BlockingTail(body))) {
            NaruSseReader.Event first = reader.read();
            Assertions.assertNotNull(first);
            Assertions.assertEquals("early", first.data());
        }
    }

    /**
     * Yields one character per read, so every frame is split across reads.
     */
    private static class OneByteAtATime extends Reader {
        private final String text;
        private int pos;

        OneByteAtATime(String text) {
            this.text = text;
        }

        @Override
        public int read(char[] buffer, int off, int len) {
            if (pos >= text.length()) {
                return -1;
            }
            buffer[off] = text.charAt(pos++);
            return 1;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Emits the first two frames eagerly, then blocks forever, standing in for a
     * server that is still generating.
     */
    private static class BlockingTail extends Reader {
        private final String text;
        private int pos;

        BlockingTail(String text) {
            this.text = text;
        }

        @Override
        public int read(char[] buffer, int off, int len) {
            int available = Math.min(len, text.length() - pos);
            if (available <= 0) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
            text.getChars(pos, pos + available, buffer, off);
            pos += available;
            return available;
        }

        @Override
        public void close() {
        }
    }
}
