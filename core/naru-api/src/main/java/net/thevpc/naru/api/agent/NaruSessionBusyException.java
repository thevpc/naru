package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

public class NaruSessionBusyException extends NIllegalStateException {
    public NaruSessionBusyException(String id) { super(NMsg.ofC("session '%s' is busy", id)); }
}
