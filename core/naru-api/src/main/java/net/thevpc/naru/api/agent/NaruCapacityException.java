package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

public class NaruCapacityException extends NIllegalStateException {
    public NaruCapacityException(int cap) { super(NMsg.ofC("no live session slot available (cap %s)", cap)); }
}
