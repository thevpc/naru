package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.*;

public class NaruSessionDirective extends NaruDirectiveBase {
    public NaruSessionDirective() {
        super("session", "session", "manage sessions", "sessions");
        noCommand("list");
        register(new AbstractSubCommand("current", NText.ofPlain("show current session")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeName(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("list", NText.ofPlain("list saved sessions")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("public", NText.ofPlain("change current session visibility to public")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeChangeVisibility(NAruVisibility.PUBLIC, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("private", NText.ofPlain("change current session visibility to private")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeChangeVisibility(NAruVisibility.PRIVATE, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("delete", NText.ofPlain("delete session")
                ,new SubCommandHelp("<name>...", "delete session by name")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeDelete(context, cmdLine);
            }

            @Override
            public List<NArgCompleteCandidate> resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
                List<NArgCompleteCandidate> candidates = new java.util.ArrayList<>();
                String[] stringArray = cmdLine.toStringArray();
                int wordIndex = pos.wordIndex();
                if (wordIndex == 2) {
                    String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";
                    for (NaruResourceInfo info : session.sessionManager().list()) {
                        if (info.getName() != null && !info.getName().isEmpty()) {
                            addCandidates(candidates, currentArg, info.getName());
                        }
                        if (info.getUuid() != null && !info.getUuid().isEmpty()) {
                            addCandidates(candidates, currentArg, info.getUuid());
                        }
                    }
                }
                return candidates;
            }
        });
        register(new AbstractSubCommand("purge", NText.ofPlain("purge all sessions")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePurge(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("load", NText.ofPlain("load session by name (or path)")
                ,new SubCommandHelp("<name>...", "load session by name")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeLoad(context, cmdLine);
            }

            @Override
            public List<NArgCompleteCandidate> resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
                List<NArgCompleteCandidate> candidates = new java.util.ArrayList<>();
                String[] stringArray = cmdLine.toStringArray();
                int wordIndex = pos.wordIndex();
                if (wordIndex == 2) {
                    String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";
                    for (NaruResourceInfo info : session.sessionManager().list()) {
                        if (info.getName() != null && !info.getName().isEmpty()) {
                            addCandidates(candidates, currentArg, info.getName());
                        }
                        if (info.getUuid() != null && !info.getUuid().isEmpty()) {
                            addCandidates(candidates, currentArg, info.getUuid());
                        }
                    }
                }
                return candidates;
            }
        });
        register(new AbstractSubCommand("reload", NText.ofPlain("reload current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeReload(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("restore", NText.ofPlain("resume from last snapshot")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRestore(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("save", NText.ofPlain("save current session")
            ,new SubCommandHelp("[<name>]", "save current session with optional name.\nwhen no name was provided, and this is a new session, a generated name will be guessed using the current model.\n when name is provided, it will be used to set name or rename the session.")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeSave(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("new", NText.ofPlain("start a new session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeNew(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("reset", NText.ofPlain("reset current session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeReset(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("copy", NText.ofPlain("copy current session to a new session")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeCopy(context, cmdLine);
            }
        });
    }



    public NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();

        List<NaruResourceInfo> naruResourceInfos = task.session().sessionManager().list();
        NStringBuilder sb = NStringBuilder.of();
        int index = 1;
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            NMsg msg = NMsg.ofC("[%s] %s %s (%s) %s %s %s", index,
                    naruResourceInfo.getCreationInstant(),
                    naruResourceInfo.getModificationInstant(),
                    NaruUtils.timeAgo(naruResourceInfo.getModificationInstant()),
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary3(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName())
            );
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
            index++;
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }


    public NaruStmtResult executePurge(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        int count = task.session().sessionManager().purge();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("purged %s sessions", count));
        return NaruStmtResult.ofSuccess(count);
    }

    public NaruStmtResult executeDelete(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return NaruStmtResult.ofSuccess(null);
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
        return NaruStmtResult.ofSuccess(count);
    }

    public NaruStmtResult executeReload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().reload();
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Reloaded session."));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSessionManager sm = task.session().sessionManager();
        String name = cmdLine.next().flatMap(x -> x.asString()).orNull();
        if (NBlankable.isBlank(name)) {
            name = "main";
        }
        String a = sm.findByUuidOrName(name);
        if (a == null) {
            NMsg msg = NMsg.ofC("session not found %s", name);
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        task.session().load(a);
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session: %s", task.session().name()));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeRestore(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().restoreSnapshot();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Restored session: %s", task.session().name()));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeSave(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        NArg n = cmdLine.next().orNull();
        if(n!=null && !n.isOption() && !NBlankable.isBlank(n.image())){
            session.setName(n.image());
        }
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
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeNew(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().reset(false);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("new session."));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeReset(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().reset(true);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeCopy(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().copy();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded session copy : %s", task.session().name()));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeName(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NMsg msg = NMsg.ofC("Current session: %s (%s)", task.session().name(), task.session().uuid());
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        NStringBuilder sb = NStringBuilder.of();
        sb.println(msg.toString());
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    public NaruStmtResult executeChangeVisibility(NAruVisibility makePublic, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        if (session.getVisibility() == makePublic) {
            //
        } else {
            session.setVisibility(makePublic);
            session.save();
            if (makePublic == NAruVisibility.PUBLIC) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("make current session public: %s (%s)", session.name(), session.uuid()));
            } else {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("make current session private: %s (%s)", session.name(), session.uuid()));
            }
        }
        return NaruStmtResult.ofSuccess(null);
    }

}
