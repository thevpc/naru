package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruReplayItem;
import net.thevpc.naru.api.agent.NaruTranscriptReplay;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One reply, one card, whichever copy arrives first.
 *
 * <p>The engine checkpoints a message to the store and writes it to the host; the store echo
 * reaches the transcript at once, the stream only on the host's next flush. When the echo won,
 * the reply used to be drawn twice — once from the store, once from the stream.
 */
class TranscriptEchoRaceTest {

    private static final long TASK = 1L;
    private static final String FAILURE = "No model is selected. Choose one yourself with /model list"
            + " and /model use <key>, then submit your request again.";

    @BeforeAll
    static void nuts() {
        Nuts.require();
    }

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    /** The store's copy of the failure, the way the session model replays it. */
    private static List<TranscriptEvent> echo(String prefix, long storeId) {
        NaruMessage stored = NaruMessage.engine(NMsg.ofC(FAILURE).withLevel(Level.SEVERE));
        List<NaruReplayItem> items = NaruTranscriptReplay.replay(TASK, List.of(stored));
        List<TranscriptEvent> events = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            events.add(new TranscriptEvent.Replay(prefix + TASK + "-" + storeId + "-" + i, TASK, storeId, items.get(i)));
        }
        return events;
    }

    private static List<TranscriptEvent> stream(String id) {
        return List.of(new TranscriptEvent.Chunk(id, NaruLogMode.AGENT_RESPONSE, FAILURE, true, TASK),
                new TranscriptEvent.Closed(id));
    }

    private static long failureCards(TranscriptModel m) {
        return m.visible().stream().filter(b -> b.text().strip().equals(FAILURE)).count();
    }

    @Test
    void streamFirstThenEchoIsOneCard() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(stream("live-1"));
            m.apply(echo(TranscriptModel.LIVE_ECHO_PREFIX, 8));
            assertEquals(1, failureCards(m));
            assertTrue(m.visible().get(0).isChatMessage(), "the stream is promoted to the stored message");
        });
    }

    @Test
    void echoFirstThenStreamIsOneCard() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(echo(TranscriptModel.LIVE_ECHO_PREFIX, 8));
            m.apply(stream("live-1"));
            assertEquals(1, failureCards(m));
            assertTrue(m.visible().get(0).isChatMessage(), "the stored copy keeps its card and severity");
        });
    }

    @Test
    void echoFirstAndLateChunksOfTheStreamStayFolded() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(echo(TranscriptModel.LIVE_ECHO_PREFIX, 8));
            m.apply(List.of(new TranscriptEvent.Chunk("live-1", NaruLogMode.AGENT_RESPONSE, FAILURE, true, TASK)));
            // a trailing empty fragment and the close of the folded block must not bring it back
            m.apply(List.of(new TranscriptEvent.Chunk("live-1", NaruLogMode.AGENT_RESPONSE, "", true, TASK),
                    new TranscriptEvent.Closed("live-1")));
            assertEquals(1, failureCards(m));
        });
    }

    @Test
    void reloadedHistoryDoesNotSwallowTheSameReplyNow() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            // the same failure from an earlier turn, read back from the store on reopen
            m.apply(echo("restored-", 8));
            m.apply(stream("live-2"));
            m.apply(echo(TranscriptModel.LIVE_ECHO_PREFIX, 13));
            assertEquals(2, failureCards(m), "one for the old turn, one for this one");
        });
    }

    @Test
    void echoFirstInAReopenedSessionStillDrawsTheNewTurnOnce() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(echo("restored-", 8));
            m.apply(echo(TranscriptModel.LIVE_ECHO_PREFIX, 13));
            m.apply(stream("live-2"));
            assertEquals(2, failureCards(m), "one for the old turn, one for this one");
        });
    }
}
