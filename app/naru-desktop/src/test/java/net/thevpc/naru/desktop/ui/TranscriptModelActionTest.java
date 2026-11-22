package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reply saying no usable model is set offers the picker; any other reply is left alone. */
class TranscriptModelActionTest {

    @BeforeAll
    static void nuts() {
        Nuts.require();
    }

    @Test
    void noModelReplyGetsAChooseModelButton() {
        NaruMessage reply = NaruMessage.assistant("No model is selected. Choose one yourself with /model list.");
        NaruMessage shown = TranscriptPane.withModelAction(reply);
        assertEquals(1, shown.getParts().size());
        assertEquals("button", shown.getParts().get(0).type());
        assertTrue(shown.getParts().get(0).payload().toString().contains(TranscriptPane.CHOOSE_MODEL_ACTION));
        assertTrue(reply.getParts().isEmpty(), "the transcript's own message must not change");
    }

    @Test
    void unknownModelReplyGetsTheButtonToo() {
        NaruMessage shown = TranscriptPane.withModelAction(
                NaruMessage.assistant("Selected model ollama/llama9 was not found."));
        assertEquals(1, shown.getParts().size());
    }

    @Test
    void ordinaryReplyIsUntouched() {
        NaruMessage reply = NaruMessage.assistant("Here is the refactor you asked for.");
        assertSame(reply, TranscriptPane.withModelAction(reply));
    }
}
