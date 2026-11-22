package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

/**
 * A turn was refused because the session is running one ({@link NaruBusyPolicy}), or because
 * a hibernation it waited for outlasted the capacity timeout.
 */
public class NaruSessionBusyException extends NIllegalStateException {
    public NaruSessionBusyException(String id) { super(NMsg.ofC("session '%s' is busy", id)); }
}
