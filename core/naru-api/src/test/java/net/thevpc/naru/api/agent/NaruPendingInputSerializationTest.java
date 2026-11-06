package net.thevpc.naru.api.agent;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NaruPendingInputSerializationTest {

    @BeforeAll
    static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    void roundTripsAsNamedObject() {
        NaruPendingInput original = new NaruPendingInput(42L, "request-1", "Which one?");

        NElement element = original.toElement();

        assertInstanceOf(NObjectElement.class, element);
        assertEquals("NaruPendingInput", ((NObjectElement) element).name().orNull());
        assertEquals(original, NaruPendingInput.of(element).get());
    }

    @Test
    void readsUnwrappedObjectAndRejectsObjectsWithoutTaskId() {
        NElement unwrapped = NElement.ofObjectBuilder()
                .set("taskId", 42L)
                .set("requestId", "request-1")
                .set("prompt", "Which one?")
                .build();
        NElement missingTaskId = NElement.ofObjectBuilder()
                .set("requestId", "request-1")
                .set("prompt", "Which one?")
                .build();

        assertEquals(new NaruPendingInput(42L, "request-1", "Which one?"),
                NaruPendingInput.of(unwrapped).get());
        assertTrue(NaruPendingInput.of(missingTaskId).isEmpty());
    }
}
