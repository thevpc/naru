package net.thevpc.naru.impl.engine.scheduler;

import net.thevpc.naru.api.agent.NaruInputMode;
import net.thevpc.nuts.text.NMsg;

public class NaruInputRequest {
    private final NMsg prompt;
    private final NaruInputMode inputMode;

    public NaruInputRequest(NMsg prompt, NaruInputMode inputMode) {
        this.prompt = prompt;
        this.inputMode = inputMode;
    }
}
