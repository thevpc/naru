package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionManager;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SaveDirective extends AbstractDirective {
    public SaveDirective() {
        super("save","session", "save current session");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
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
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           save current session"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }


    public void executeSave(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        if (NBlankable.isBlank(sessionContext.name()) || sessionContext.name().equals("NO_NAME")) {
            List<NaruMessage> history = context.session().history(true);
            history.add(NaruMessage.user("can you suggest a name for this session? dont be verbose in your response, only return the suggested name please."));
            NaruModelConfig model = context.session().model();
            NaruResponse chat = context.session().chat(model,
                    history, Collections.emptyList()
            );
            if (chat.getMessage() != null) {
                sessionContext.setName(chat.getMessage().getContent());
            }
        }
        sessionContext.save();
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Saved session: %s", NMsg.ofStyledString(sessionContext.name())));
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
