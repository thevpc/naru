package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.Arrays;
import java.util.List;

public class NaruCdDirective extends NaruDirectiveBase {
    public NaruCdDirective() {
        super("cd", "fs", "change directory");
        register(new AbstractSubCommand(new SubCommandHelp("<dir>", "change directory to <dir>")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NPath p2 = NBlankable.isBlank(context.argument()) ? context.task().projectDir() : NPath.of(context.argument());
                String denied = NaruFsGuard.deny(task, p2, NaruResourceType.DIR, NaruAction.LIST);
                if (denied != null) {
                    NMsg msg = NMsg.ofC("%s", denied).asError();
                    context.task().addResultMessage(msg);
                    context.outln(msg);
                    return NaruStmtResult.ofError(1);
                }
                if (p2.isDirectory()) {
                    task.setWorkingDir(p2);
                    NMsg msg = NMsg.ofC("change working directory to %s", task.workingDir());
                    context.task().addResultMessage(msg);
                    context.outln(msg);
                    return NaruStmtResult.ofSuccess(NElement.of(task.workingDir().toString()),msg );
                } else {
                    NMsg msg = NMsg.ofC("directory not found: %s", p2).asError();
                    context.task().addResultMessage(msg);
                    context.errln(msg);
                    return NaruStmtResult.ofError();
                }
            }
        });
    }

    @Override
    public NArgCompleteResult resolveCandidates(
            NCmdLine cmdLine,
            NArgCompletePosition pos,
            NaruSession session) {
        List<NArgCompleteCandidate> candidates = new java.util.ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String prefix = wordIndex < stringArray.length ? stringArray[wordIndex] : "";

        if (wordIndex == 1) {
            String dirPath = prefix.isEmpty() ? "." : prefix;
            java.io.File f = new java.io.File(dirPath);
            java.io.File parent = f.getParentFile();
            String namePrefix = f.getName();

            if (prefix.endsWith("/") || prefix.endsWith("\\") || prefix.isEmpty() || (f.isDirectory() && prefix.endsWith(java.io.File.separator))) {
                parent = f;
                namePrefix = "";
            } else if (parent == null) {
                parent = new java.io.File(".");
            }

            java.io.File resolvedParent = parent;
            if (!parent.isAbsolute() && session.workingDir() != null) {
                resolvedParent = new java.io.File(new java.io.File(session.workingDir().toAbsolute().toString()), parent.getPath());
            }

            java.io.File[] files = resolvedParent.listFiles();
            if (files != null) {
                for (java.io.File child : files) {
                    if (child.isDirectory() && child.getName().startsWith(namePrefix)) {
                        String resultPath;
                        if (parent.getPath().equals(".")) {
                            resultPath = child.getName();
                        } else {
                            resultPath = new java.io.File(parent, child.getName()).getPath();
                        }
                        if (resultPath.startsWith("." + java.io.File.separator)) {
                            resultPath = resultPath.substring(2);
                        }
                        candidates.add(NArgCompleteCandidate.of(resultPath + java.io.File.separator));
                    }
                }
            }
        }
        return NArgCompleteResult.of(candidates, Arrays.asList(NArgCompleteFlag.NOSPACE));
    }
}
