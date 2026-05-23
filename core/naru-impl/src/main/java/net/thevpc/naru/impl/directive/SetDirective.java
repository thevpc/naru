package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class SetDirective extends AbstractDirective {
    public SetDirective() {
        super("set","routine", "set variable value");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        String raw = context.argument();
        String assignment = raw.substring(4).trim();
        int eqIdx = assignment.indexOf('=');
        if (eqIdx == -1) { /* error handling */
            return;
        }

        String key = assignment.substring(0, eqIdx).trim();
        String value = assignment.substring(eqIdx + 1).trim();

        // Unquote if needed
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }

        // Store locally by default
        RunContext current = context.session().getTopContext();
        if (current != null) {
            current.setState(key, value);
        } else {
            // Fallback to global if no context active
            context.session().setSessionEnv(key, value);
        }

        context.session().advancePcOrEnd();
    }
}
