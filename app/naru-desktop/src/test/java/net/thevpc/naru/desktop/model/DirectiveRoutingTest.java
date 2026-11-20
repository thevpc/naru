package net.thevpc.naru.desktop.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What counts as a directive. The composer routes a line to the engine's directive path — executed
 * on the live conversation task — only when this says so; everything else stays a message.
 * <p>
 * The distinction matters because a directive queued as a task message is answered by the model
 * as if it were a prompt, which is how {@code /model} came back as a model listing.
 */
class DirectiveRoutingTest {

    @Test
    void aSlashCommandIsADirective() {
        assertTrue(NaruSessionModel.isDirective("/model"));
        assertTrue(NaruSessionModel.isDirective("/model use 3"));
        assertTrue(NaruSessionModel.isDirective("/session save"));
        assertTrue(NaruSessionModel.isDirective("   /help"));
    }

    @Test
    void aPlainMessageIsNotADirective() {
        assertFalse(NaruSessionModel.isDirective("model"));
        assertFalse(NaruSessionModel.isDirective("how do I /model?"));
        assertFalse(NaruSessionModel.isDirective(""));
        assertFalse(NaruSessionModel.isDirective(null));
    }

    @Test
    void aLoneSlashIsNotADirective() {
        // "/" on its own is not a directive name; the engine has nothing to dispatch it to, so it
        // must not be treated as one here either
        assertFalse(NaruSessionModel.isDirective("/"));
        assertFalse(NaruSessionModel.isDirective(" / "));
    }
}
