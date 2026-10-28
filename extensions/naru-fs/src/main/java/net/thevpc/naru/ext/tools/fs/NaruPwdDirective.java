package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;

public class NaruPwdDirective extends NaruDirectiveBase {
    public NaruPwdDirective() {
        super("pwd","fs", "print working directory");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                context.outln(NMsg.ofC("current working directory is %s", task.workingDir()));
                return NaruStmtResult.ofSuccess(
                        NElement.of(task.workingDir().toString())
                        ,null);
            }
        });
    }

}
