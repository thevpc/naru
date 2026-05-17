package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class SetDirective extends AbstractDirective {
    public SetDirective() {
        super("set", "set variable value");
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
            context.session().setGlobalState(key, value);
        }

        context.session().advancePcOrEnd();
    }
}
