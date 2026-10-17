package net.thevpc.naru.api.model;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

/**
 * Thrown when a model call is refused because a {@link NaruModelFilter} denies the model.
 *
 * <p>Fail-closed: the check runs before any transport is built, so a denied model
 * (typically a paid cloud provider in a session pinned to local models) can never reach the
 * network. It is a decision of the configuration rather than a failure of the provider, so
 * it is reported as such.
 */
public class NaruModelDeniedException extends NIllegalStateException {

    private final String model;

    public NaruModelDeniedException(NaruModelKey model) {
        super(NMsg.ofC("model '%s' is not allowed by the session's model filter", model));
        this.model = model == null ? null : model.toString();
    }

    /** The denied model key, {@code provider/model}. */
    public String model() {
        return model;
    }
}
