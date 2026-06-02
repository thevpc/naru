package net.thevpc.naru.impl.registry.builtindirectives.general;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;

public class NaruBufferDirective extends AbstractDirective {
    public NaruBufferDirective() {
        super("buffer", "general", "switch input mode (line <> buffer)");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        task.inputMode(task.inputMode() == NAruInputMode.LINE ? NAruInputMode.BLOC : NAruInputMode.LINE);
    }
}
