package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * No model produced a usable title.
 *
 * <p>Carries every model that was tried with the reason each one failed, because the two
 * diagnoses a user needs are "the cheap model I configured is not registered" and "the
 * cheap model answered with punctuation". A single flattened message cannot answer either,
 * and this is the one place that knows the whole list.
 *
 * <p>Thrown by the manual paths only. The automatic trigger swallows it: a session that
 * could not be titled keeps its placeholder and says nothing, because titling is a
 * convenience and must never surface as an error in the middle of a conversation.
 */
public class NaruTitleException extends NIllegalStateException {

    /** One model that was tried, and what it did instead of answering. */
    public record Attempt(String model, String reason) {
    }

    private final List<Attempt> attempts;

    public NaruTitleException(List<Attempt> attempts, String extra) {
        super(NMsg.ofC("could not generate a session title: %s", describe(attempts, extra)));
        this.attempts = Collections.unmodifiableList(new ArrayList<>(attempts));
    }

    private static String describe(List<Attempt> attempts, String extra) {
        List<String> parts = new ArrayList<>();
        for (Attempt a : attempts) {
            parts.add(a.model() + ": " + a.reason());
        }
        String joined = parts.isEmpty() ? "no model was tried" : String.join("; ", parts);
        return extra == null || extra.isBlank() ? joined : joined + " (" + extra + ")";
    }

    /** Every model that was tried, in the order they were tried. */
    public List<Attempt> attempts() {
        return attempts;
    }
}
