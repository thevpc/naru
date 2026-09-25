package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruLogMode;

import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * Session-scoped plan management.
 *
 * <p>Activation lives here and only here: it is a human directive with no tool
 * equivalent, which is what keeps a model that holds the plan tag from starting
 * execution on its own. No model-callable mode switch exists, so this boundary holds.
 */
public class NaruPlanDirectiveProvider extends NaruDirectiveProviderBase {

    public NaruPlanDirectiveProvider() {
        super("plan");
        this.registerDirective(new NaruPlanDirective());
    }

    public static class NaruPlanDirective extends NaruDirectiveBase {
        public NaruPlanDirective() {
            super("plan", "plan", "show, activate or clear execution plans");
            noCommand("show");
            register(new AbstractSubCommand("show", NText.ofPlain("show a plan, or list every plan when no id is given")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    String ref = cmdLine.isEmpty() ? null : cmdLine.next().get().image();
                    if (ref != null && "all".equalsIgnoreCase(ref)) {
                        return logAll(context);
                    }
                    NOptional<NaruPlan> planOpt = ref == null
                            ? NaruPlanExtension.plans(task.session()).activePlan()
                            : NaruPlanExtension.plans(task.session()).findPlan(ref);
                    if (planOpt.isError()) {
                        return NaruStmtResult.ofError(planOpt.toString());
                    }
                    NaruPlan p = planOpt.orNull();
                    if (p == null) {
                        logInfo(context, "no active plan");
                        return NaruStmtResult.ofSuccess(logListing(context));
                    }
                    logInfo(context, "plan " + p.id() + " [" + p.status().name().toLowerCase() + "]:\n" + p.render());
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("activate", NText.ofPlain("activate a plan so its ready items start executing")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    if (cmdLine.isEmpty()) {
                        return NaruStmtResult.ofError("usage: /plan activate <plan-id>");
                    }
                    String id = cmdLine.next().get().image();
                    NaruTask task = context.task();
                    NaruPlan p = NaruPlanExtension.plans(task.session()).findPlan(id).orNull();
                    if (p == null) {
                        return NaruStmtResult.ofError("no plan '" + id + "'\n" + logListing(context));
                    }
                    NaruPlanExtension.plans(task.session()).setActivePlanId(p.id());
                    logInfo(context, "plan " + p.id() + " activated; ready items will be dispatched");
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("deactivate", NText.ofPlain("stop dispatching items without discarding progress")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    if (NaruPlanExtension.plans(task.session()).activePlanId() == null) {
                        logInfo(context, "no active plan");
                        return NaruStmtResult.ofSuccess(null);
                    }
                    NaruPlanExtension.plans(task.session()).setActivePlanId(null);
                    logInfo(context, "plan deactivated; a running item is not cancelled");
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("reopen", NText.ofPlain("reopen a finished item, optionally cascading to its dependents")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    if (cmdLine.isEmpty()) {
                        return NaruStmtResult.ofError("usage: /plan reopen <item-id> [--cascade]");
                    }
                    String itemRef = cmdLine.next().get().image();
                    boolean cascade = false;
                    while (!cmdLine.isEmpty()) {
                        String flag = cmdLine.next().get().image();
                        if ("--cascade".equals(flag) || "-c".equals(flag)) {
                            cascade = true;
                        } else {
                            return NaruStmtResult.ofError("unknown option '" + flag + "'");
                        }
                    }
                    NaruTask task = context.task();
                    NaruPlan p = NaruPlanExtension.plans(task.session()).activePlan().orNull();
                    if (p == null) {
                        return NaruStmtResult.ofError("no active plan");
                    }
                    NaruPlanItem item = p.findItemByPrefix(itemRef);
                    if (item == null) {
                        return NaruStmtResult.ofError("no item matching '" + itemRef + "' in plan " + p.id());
                    }
                    if (!item.status().isTerminal()) {
                        return NaruStmtResult.ofError("item " + item.id() + " is " + item.status().name().toLowerCase()
                                + ", only finished items can be reopened");
                    }
                    if (cascade) {
                        // destructive: validated downstream work is about to be undone
                        return NaruStmtResult.ofError("reopening '" + item.id() + "' with --cascade also reopens every item "
                                + "that depends on it, discarding their validation. Re-run without --cascade to reopen "
                                + "only this item.");
                    }
                    NOptional<List<NaruPlanItem>> demoted =
                            NaruPlanExtension.plans(task.session()).reopenItem(p.id(), item.id(), false);
                    if (demoted.isError() || !demoted.isPresent()) {
                        return NaruStmtResult.ofError("could not reopen item " + item.id());
                    }
                    logInfo(context, "reopened " + item.id() + "\n" + p.render());
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("clear", NText.ofPlain("remove a plan entirely, or the active plan when no id is given")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    String ref = cmdLine.isEmpty() ? NaruPlanExtension.plans(task.session()).activePlanId() : cmdLine.next().get().image();
                    boolean removed = ref != null && NaruPlanExtension.plans(task.session()).removePlan(ref);
                    logInfo(context, removed ? "plan removed" : "no such plan");
                    return NaruStmtResult.ofSuccess(null);
                }
            });
        }

        private void logInfo(NaruDirectiveCallContext context, String message) {
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", message));
        }

        private NaruStmtResult logAll(NaruDirectiveCallContext context) {
            logInfo(context, logListing(context));
            return NaruStmtResult.ofSuccess(null);
        }

        private String logListing(NaruDirectiveCallContext context) {
            NaruTask task = context.task();
            String active = NaruPlanExtension.plans(task.session()).activePlanId();
            StringBuilder sb = new StringBuilder();
            for (NaruPlan p : NaruPlanExtension.plans(task.session()).plans().values()) {
                int done = 0;
                for (NaruPlanItem i : p.items()) {
                    if (i.status() == NaruPlanItemStatus.DONE) {
                        done++;
                    }
                }
                sb.append(p.id());
                if (p.id().equals(active)) {
                    sb.append(" (active)");
                }
                sb.append(' ').append(p.status().name().toLowerCase())
                        .append(' ').append(done).append('/').append(p.items().size())
                        .append(" - ").append(p.goal()).append('\n');
            }
            return sb.length() == 0 ? "no plans exist yet" : sb.toString();
        }
    }
}
