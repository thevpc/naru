package net.thevpc.naru.api.model;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

/**
 * Thrown when a request cannot be sent because the history ends somewhere no model can
 * continue from.
 *
 * <p>Exists as its own type because the alternative is the worst kind of failure: the
 * request is accepted by the transport and rejected by the provider with a 400 whose
 * text names a wire format, not the thing that is actually wrong. A dangling tool call,
 * a tool result with no call behind it, a request whose last turn the protocol refuses
 * to accept -- all of them are decisions made here, so they are reported as decisions.
 *
 * <p>The message is required to say what the tail was and what was expected instead.
 */
public class NaruInvalidHistoryException extends NIllegalStateException {

    private final String model;
    private final String reason;

    public NaruInvalidHistoryException(String model, String reason) {
        super(NMsg.ofC("cannot send to %s: %s", model, reason));
        this.model = model;
        this.reason = reason;
    }

    /** The model key the request was addressed to. */
    public String model() {
        return model;
    }

    /** Why the tail cannot be sent, without the model name. */
    public String reason() {
        return reason;
    }
}