package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.*;

public class SessionDirective extends AbstractDirective {
    public SessionDirective() {
        super("session","session", "manage sessions","sessions");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "name": {
                    executeName(context, cmdLine);
                    break;
                }
                case "list": {
                    executeList(context, cmdLine);
                    break;
                }
                case "public": {
                    executeMakePublic(true, context, cmdLine);
                    break;
                }
                case "private": {
                    executeMakePublic(false, context, cmdLine);
                    break;
                }
                case "drop":
                case "delete":
                case "rm": {
                    executeDelete(context, cmdLine);
                    break;
                }
                case "clear": {
                    executeClear(context, cmdLine);
                    break;
                }
                case "load": {
                    executeLoad(context, cmdLine);
                    break;
                }
                case "reload": {
                    executeReload(context, cmdLine);
                    break;
                }
                case "restore": {
                    executeRestore(context, cmdLine);
                    break;
                }
                case "save": {
                    executeSave(context, cmdLine);
                    break;
                }
                case "new": {
                    executeNew(context, cmdLine);
                    break;
                }
                case "reset": {
                    executeReset(context, cmdLine);
                    break;
                }
                case "copy": {
                    executeCopy(context, cmdLine);
                    break;
                }
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

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();

        List<NaruResourceInfo> naruResourceInfos = sessionContext.sessionManager().list();
        naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
        int index = 1;
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }


    public void executeClear(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        int count = sessionContext.sessionManager().clear();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s sessions", count));
    }

    public void executeDelete(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruSession sessionContext = context.session();

        NaruSessionManager sm = sessionContext.sessionManager();
        int trials = 0;
        int count = 0;
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            String uuid = sm.findByUuidOrName(a);
            trials++;
            if (uuid == null) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("session not found %s", a));
            } else {
                if (sm.delete(uuid)) {
                    count++;
                }
            }
        }
        if (trials == 0) {
            if (sm.delete(sessionContext.uuid())) {
                count++;
            }
        }
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s sessions", count));
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("session"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list <n1>-<p1>,<n2>-<p2>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list intervals"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s name", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           display session name"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s public", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           make current session public"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s private", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           make current session private"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s drop name", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop session by name"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s clear", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop  all sessions"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s load <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           load session (or default 'main')"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s reload", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           reload from last saved"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s restore", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           resume from last snapshot"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s save", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           save session (or default 'main')"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s new", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           start a new session"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s copy", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           copy to a new session"));


        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    public void executeReload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        sessionContext.sessionManager().reload();
        context.session().log(NaruLogMode.PROGRESS, NMsg.ofC("Reloaded session."));
    }

    public void executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruSessionManager sm = sessionContext.sessionManager();
        String name = cmdLine.next().flatMap(x->x.asString()).orNull();
        if (NBlankable.isBlank(name)) {
            name = "main";
        }
        String a = sm.findByUuidOrName(name);
        if (a == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("session not found %s", name));
            return;
        }
        sm.load(a);
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session: %s", sessionContext.name()));
    }

    public void executeRestore(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NaruSessionManager sm = sessionContext.sessionManager();
        sm.restoreSnapshot();
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Restored session: %s", sessionContext.name()));
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

    public void executeNew(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        sessionContext.reset(false);
        context.session().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
    }

    public void executeReset(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        sessionContext.reset(true);
        context.session().log(NaruLogMode.PROGRESS, NMsg.ofC("new session."));
    }

    public void executeCopy(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        sessionContext.sessionManager().copyCurrent();
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session copy : %s", sessionContext.name()));
    }

    public void executeName(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current session: %s (%s)", sessionContext.name(), sessionContext.uuid()));
    }

    public void executeMakePublic(boolean makePublic, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        if(sessionContext.isPublicSession()==makePublic){

        }else {
            sessionContext.setPublicSession(makePublic);
            sessionContext.save();
            if (makePublic) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("make current session public: %s (%s)", sessionContext.name(), sessionContext.uuid()));
            } else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("make current session private: %s (%s)", sessionContext.name(), sessionContext.uuid()));
            }
        }
    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new java.util.ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";

        if (wordIndex == 1) {
            addCandidates(candidates, currentArg, "name", "list", "drop", "delete", "rm", "clear", "load", "save", "new", "copy", "help");
        } else if (wordIndex == 2) {
            String prevArg = stringArray[1];
            if (prevArg.equals("load") || prevArg.equals("drop") || prevArg.equals("delete") || prevArg.equals("rm")) {
                for (NaruResourceInfo info : session.sessionManager().list()) {
                    if (info.getName() != null && !info.getName().isEmpty()) {
                        addCandidates(candidates, currentArg, info.getName());
                    }
                    if (info.getUuid() != null && !info.getUuid().isEmpty()) {
                        addCandidates(candidates, currentArg, info.getUuid());
                    }
                }
            }
        }
        return candidates;
    }
}
