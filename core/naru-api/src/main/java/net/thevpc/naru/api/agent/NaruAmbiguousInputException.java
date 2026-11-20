package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.List;

public class NaruAmbiguousInputException extends NIllegalArgumentException {
    private final List<NaruPendingInput> pendingInputs;
    public NaruAmbiguousInputException(List<NaruPendingInput> pendingInputs) {
        super(NMsg.ofC("multiple input requests are pending: %s", pendingInputs));
        this.pendingInputs = List.copyOf(pendingInputs);
    }
    public List<NaruPendingInput> pendingInputs() { return pendingInputs; }
}
