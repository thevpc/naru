package net.thevpc.naru.impl.engine.scheduler;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.nuts.text.NMsg;

public class NaruInputRequest {
    private final NMsg prompt;
    private final NAruInputMode inputMode;

    public NaruInputRequest(NMsg prompt, NAruInputMode inputMode) {
        this.prompt = prompt;
        this.inputMode = inputMode;
    }
}
