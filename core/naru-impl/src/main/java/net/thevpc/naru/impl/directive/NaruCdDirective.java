package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.List;

public class NaruCdDirective extends AbstractDirective {
    public NaruCdDirective() {
        super("cd","general", "change directory");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        task.setWorkingDir(NBlankable.isBlank(context.argument()) ? context.task().projectDir() : NPath.of(context.argument()));
        context.task().addHistory(NaruMessage.user(NMsg.ofC("change working directory to %s", task.workingDir()).toString()));
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("change directory to : %s", task.workingDir()));
    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new java.util.ArrayList<>();
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
                        candidates.add(new net.thevpc.nuts.cmdline.DefaultNArgCandidate(resultPath + java.io.File.separator));
                    }
                }
            }
        }
        return candidates;
    }
}
