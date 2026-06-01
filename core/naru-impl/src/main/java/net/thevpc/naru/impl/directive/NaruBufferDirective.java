package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

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
