package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.plan.NaruPlan;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NStringBuilder;

public class NaruPlanDirectiveProvider extends NaruDirectiveProviderBase {

    public NaruPlanDirectiveProvider() {
        super("plan");
        this.registerDirective(new NaruPlanDirective());
    }

    public static class NaruPlanDirective extends NaruDirectiveBase {
        public NaruPlanDirective() {
            super("plan", "plan", "show or clear the current task's execution plan");
            noCommand("show");
            register(new AbstractSubCommand("show", NText.ofPlain("show the current task's plan")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    NaruPlan p = task.session().planManager().activePlan(task.id()).orNull();
                    NStringBuilder sb = NStringBuilder.of();
                    if (p == null) {
                        NMsg msg = NMsg.ofC("no active plan for task %s", task.id());
                        task.log(NaruLogMode.AGENT_RESPONSE, msg);
                        sb.println(msg.toString());
                    } else {
                        for (String line : p.render().split("\n")) {
                            NMsg msg = NMsg.ofP(line);
                            task.log(NaruLogMode.AGENT_RESPONSE, msg);
                            sb.println(msg.toString());
                        }
                    }
                    return NaruStmtResult.ofSuccess(sb.toString());
                }
            });
            register(new AbstractSubCommand("clear", NText.ofPlain("remove the current task's plan")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    boolean removed = task.session().planManager().removePlan(task.id());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC(removed ? "plan removed" : "no active plan"));
                    return NaruStmtResult.ofSuccess(null);
                }
            });
        }
    }
}