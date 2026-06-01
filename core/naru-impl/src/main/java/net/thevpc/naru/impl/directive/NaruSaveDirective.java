package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
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
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask session = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeSave(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           save current session"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }


    public void executeSave(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        if (NBlankable.isBlank(task.session().name()) || task.session().name().equals("NO_NAME")) {
            List<NaruMessage> history = context.task().context(NaruSource.values()).messages();
            history.add(NaruMessage.user("can you suggest a name for this session? dont be verbose in your response, only return the suggested name please."));
            NaruModelConfig model = context.task().model();

            NaruResponse chat = context.task().chat(
                    model,
                    new NaruModelRequest(history,
                            context.task().context(NaruSource.values()).env())
            );
            if (chat.getMessage() != null) {
                task.session().setName(chat.getMessage().getContent());
            }
        }
        task.session().save();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Saved session: %s", NMsg.ofStyledString(task.session().name())));
    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new ArrayList<>();
        return candidates;
    }
}
