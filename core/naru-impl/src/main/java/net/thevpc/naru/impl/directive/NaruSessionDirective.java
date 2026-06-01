package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.*;

public class NaruSessionDirective extends AbstractDirective {
    public NaruSessionDirective() {
        super("session", "session", "manage sessions", "sessions");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
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
                    executeChangeVisibility(NAruVisibility.PUBLIC, context, cmdLine);
                    break;
                }
                case "private": {
                    executeChangeVisibility(NAruVisibility.PRIVATE, context, cmdLine);
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
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();

        List<NaruResourceInfo> naruResourceInfos = task.session().sessionManager().list();
        int index = 1;
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s (%s) %s %s %s", index,
                    naruResourceInfo.getCreationDate(),
                    naruResourceInfo.getModificationDate(),
                    NaruUtils.timeAgo(naruResourceInfo.getModificationDate()),
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary3(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }


    public void executeClear(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        int count = task.session().sessionManager().clear();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s sessions", count));
    }

    public void executeDelete(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruTask task = context.task();

        NaruSessionManager sm = task.session().sessionManager();
        int trials = 0;
        int count = 0;
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            String uuid = sm.findByUuidOrName(a);
            trials++;
            if (uuid == null) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("session not found %s", a));
            } else {
                if (sm.delete(uuid)) {
                    count++;
                }
            }
        }
        if (trials == 0) {
            if (sm.delete(task.session().uuid())) {
                count++;
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s sessions", count));
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask session = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <n1>-<p1>,<n2>-<p2>", kk, NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list intervals"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("name")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           display session name"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("public")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           make current session public"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("private")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           make current session private"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk, NMsg.ofStyledPrimary4("drop")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop session by name"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("clear")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop  all sessions"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk, NMsg.ofStyledPrimary4("load")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           load session (or default 'main')"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("reload")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           reload from last saved"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("restore")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           resume from last snapshot"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("save")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           save session (or default 'main')"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("new")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           start a new session"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("copy")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           copy to a new session"));


        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    public void executeReload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().sessionManager().reload();
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Reloaded session."));
    }

    public void executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSessionManager sm = task.session().sessionManager();
        String name = cmdLine.next().flatMap(x -> x.asString()).orNull();
        if (NBlankable.isBlank(name)) {
            name = "main";
        }
        String a = sm.findByUuidOrName(name);
        if (a == null) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("session not found %s", name));
            return;
        }
        sm.load(a);
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session: %s", task.session().name()));
    }

    public void executeRestore(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSessionManager sm = task.session().sessionManager();
        sm.restoreSnapshot();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Restored session: %s", task.session().name()));
    }

    public void executeSave(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        if (NBlankable.isBlank(session.name()) || session.name().equals("NO_NAME")) {
            List<NaruMessage> history = task.context(NaruSource.values()).messages();
            history.add(NaruMessage.user("can you suggest a name for this session? dont be verbose in your response, only return the suggested name please."));
            NaruModelConfig model = task.model();
            NaruResponse chat = task.chat(model,
                    new NaruModelRequest(history,
                            task.context(NaruSource.values()).env()
                    )
            );
            if (chat.getMessage() != null) {
                session.setName(chat.getMessage().getContent());
            }
        }
        session.save();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Saved session: %s", NMsg.ofStyledString(session.name())));
    }

    public void executeNew(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().reset(false);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("new session."));
    }

    public void executeReset(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().reset(true);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
    }

    public void executeCopy(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().sessionManager().copyCurrent();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session copy : %s", task.session().name()));
    }

    public void executeName(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Current session: %s (%s)", task.session().name(), task.session().uuid()));
    }

    public void executeChangeVisibility(NAruVisibility makePublic, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        if (session.getVisibility()==makePublic) {
            //
        } else {
            session.setVisibility(makePublic);
            session.save();
            if (makePublic==NAruVisibility.PUBLIC) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("make current session public: %s (%s)", session.name(), session.uuid()));
            } else {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("make current session private: %s (%s)", session.name(), session.uuid()));
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
