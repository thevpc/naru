package net.thevpc.naru.impl.registry.builtindirectives.session;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;

public class NaruSaveDirective extends AbstractDirective {
    public NaruSaveDirective() {
        super("save","session", "save current session");
        register(new AbstractSubCommand(new SubCommandHelp("[<name>]", "save current session with optional name.\nwhen no name was provided, and this is a new session, a generated name will be guessed using the current model.\n when name is provided, it will be used to set name or rename the session.")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeSave(context, cmdLine);
            }
        });
    }




    public void executeSave(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        NArg n = cmdLine.next().orNull();
        if(n!=null && !n.isOption() && !NBlankable.isBlank(n.image())){
            session.setName(n.image());
        }
        if (NBlankable.isBlank(session.name()) || session.name().equals("NO_NAME")) {
            List<NaruMessage> history = context.task().context(NaruSource.values()).messages();
            history.add(NaruMessage.user("can you suggest a name for this session? dont be verbose in your response, only return the suggested name please."));
            NaruModelConfig model = context.task().model();

            NaruResponse chat = context.task().chat(
                    model,
                    new NaruModelRequest(history,
                            context.task().context(NaruSource.values()).env())
            );
            if (chat.getMessage() != null) {
                session.setName(chat.getMessage().getContent());
            }
        }
        session.save();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Saved session: %s", NMsg.ofStyledString(session.name())));
    }


}
