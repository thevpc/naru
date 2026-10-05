package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.List;

public class NaruGoDirective extends NaruDirectiveBase {
    public NaruGoDirective() {
        super("go", "general", "call model without additional prompt");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String prompt = task.inputBuffer();
                task.inputBuffer("");
                if (NBlankable.isBlank(prompt)) {
                    NaruModelRequest cc = task.context();
                    List<NaruMessage> messages = cc.messages()
                            .stream().filter(x -> {
                                switch (x.getRole()) {
                                    case summary:
                                        return false;
                                    case system:
                                    case user:
                                    case tool:
                                        return true;
                                }
                                return false;
                            }).toList();
                    if (messages.isEmpty()) {
                        task.log(NaruLogMode.TRACE, NMsg.ofC("nothing to do..."));
                        return NaruStmtResult.ofError("nothing to do...");
                    }
                    NaruMessage last = messages.get(messages.size() - 1);
                    if (last.getRole() == NaruRole.assistant) {
                        task.log(NaruLogMode.TRACE, NMsg.ofC("nothing to do..."));
                        return NaruStmtResult.ofError("nothing to do...");
                    }
                }
                task.prependStatement(NaruStatementHelper.ofModelCall(prompt));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

}
