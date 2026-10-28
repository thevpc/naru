package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruLogMode;

import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NBlankable;
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
            noCommand("list");
            register(new AbstractSubCommand("show", NText.ofPlain("show a plan or active one")) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    String ref = cmdLine.isEmpty() ? null : cmdLine.next().get().image();
                    NaruPlan currentPlan=null;
                    if(NBlankable.isBlank(ref)){
                        currentPlan = NaruPlanExtension.plans(task.session()).activePlan().orNull();
                        if(currentPlan==null){
                            // No active plan: fall back to the only unfinished one so
                            // "/plan show" works right after a planning round without the
                            // user having to remember an id. With several, ask rather than
                            // guess which plan to run.
                            List<NaruPlan> unfinished = NaruPlanExtension.unfinishedPlans(task.session());
                            if(unfinished.isEmpty()){
                                logInfo(context, NMsg.ofStyledError("no active plan"));
                                return NaruStmtResult.ofError();
                            }else if(unfinished.size()==1){
                                currentPlan = unfinished.get(0);
                            }else{
                                logInfo(context, NMsg.ofStyledError("multiple active plans exist; please specify which plan to show\n" + logListing(context)));
                                return NaruStmtResult.ofError();
                            }
                        }
                    }else{
                        currentPlan=NaruPlanExtension.plans(task.session()).findPlan(ref).orNull();
                        if(currentPlan==null){
                            logInfo(context, NMsg.ofStyledError("no such plan "+ref));
                            return NaruStmtResult.ofError();
                        }
                    }

                    logInfo(context,
                            NMsg.ofC("plan %s [%s]:\n" + currentPlan.render(),
                                    NMsg.ofStyledPrimary1(currentPlan.id()),
                                    NaruPlanExtension.colorizePlanStatus(currentPlan.status())
                                    )
                            );
                    return NaruStmtResult.ofSuccess();
                }
            });
            register(new AbstractSubCommand("list", NText.ofPlain("list every plan")) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    logAll(context);
                    return NaruStmtResult.ofSuccess(NElement.of(context.out().filteredText()));
                }
            });
            register(new AbstractSubCommand("activate", NText.ofPlain("activate a plan so its ready items start executing")) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    // Check if we are in planning mode
                    if ("plan".equals(context.task().promptMode().name())) {
                        context.errln("cannot activate plan while in planning mode; switch to another mode first");
                        return NaruStmtResult.ofError();
                    }
                    NaruTask task = context.task();
                    if (cmdLine.isEmpty()) {
                        // No argument: activate the only non-finished plan if exactly one exists
                        List<NaruPlan> plans = NaruPlanExtension.unfinishedPlans(task.session());
                        if (plans.isEmpty()) {
                            context.errln("no non-finished plan to activate");
                            return NaruStmtResult.ofError();
                        }
                        if (plans.size() > 1) {
                            context.errln(NMsg.ofC("multiple non-finished plans exist; please specify which plan to activate\n%s", logListing(context)));
                            return NaruStmtResult.ofError();
                        }
                        NaruPlan p = plans.get(0);
                        NaruPlanExtension.plans(task.session()).setActivePlanId(p.id());
                        logInfo(context, NMsg.ofC("plan %s activated; ready items will be dispatched",NMsg.ofStyledPrimary1(p.id())));
                        return NaruStmtResult.ofSuccess();
                    } else {
                        // With argument: activate the plan with given id/prefix
                        String id = cmdLine.next().get().image();
                        NaruPlan p = NaruPlanExtension.plans(task.session()).findPlan(id).orNull();
                        if (p == null) {
                            context.errln("no plan '" + id + "'\n" + logListing(context));
                            return NaruStmtResult.ofError();
                        }
                        NaruPlanExtension.plans(task.session()).setActivePlanId(p.id());
                        logInfo(context, NMsg.ofC("plan %s activated; ready items will be dispatched",NMsg.ofStyledPrimary1(p.id())));
                        return NaruStmtResult.ofSuccess();
                    }
                }
            });
            register(new AbstractSubCommand("deactivate", NText.ofPlain("stop dispatching items without discarding progress")) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    if (NaruPlanExtension.plans(task.session()).activePlanId() == null) {
                        logInfo(context, NMsg.ofStyledError("no active plan"));
                        return NaruStmtResult.ofSuccess();
                    }
                    NaruPlanExtension.plans(task.session()).setActivePlanId(null);
                    logInfo(context, NMsg.ofStyledWarn("plan deactivated; a running item is not cancelled"));
                    return NaruStmtResult.ofSuccess();
                }
            });
            register(new AbstractSubCommand("reopen", NText.ofPlain("reopen a finished item, optionally cascading to its dependents")) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    if (cmdLine.isEmpty()) {
                        context.errln("usage: /plan reopen <item-id> [--cascade]");
                        return NaruStmtResult.ofError();
                    }
                    String itemRef = cmdLine.next().get().image();
                    boolean cascade = false;
                    while (!cmdLine.isEmpty()) {
                        String flag = cmdLine.next().get().image();
                        if ("--cascade".equals(flag) || "-c".equals(flag)) {
                            cascade = true;
                        } else {
                            context.errln("unknown option '" + flag + "'");
                            return NaruStmtResult.ofError();
                        }
                    }
                    NaruTask task = context.task();
                    NaruPlan p = NaruPlanExtension.plans(task.session()).activePlan().orNull();
                    if (p == null) {
                        context.errln("no active plan");
                        return NaruStmtResult.ofError();
                    }
                    NaruPlanItem item = p.findItemByPrefix(itemRef);
                    if (item == null) {
                        context.errln("no item matching '" + itemRef + "' in plan " + p.id());
                        return NaruStmtResult.ofError();
                    }
                    if (!item.status().isTerminal()) {
                        context.errln("item " + item.id() + " is " + item.status().name().toLowerCase()
                                + ", only finished items can be reopened");
                        return NaruStmtResult.ofError();
                    }
                    if (cascade) {
                        // destructive: validated downstream work is about to be undone
                        context.errln("reopening '" + item.id() + "' with --cascade also reopens every item "
                                + "that depends on it, discarding their validation. Re-run without --cascade to reopen "
                                + "only this item.");
                        return NaruStmtResult.ofError();
                    }
                    NOptional<List<NaruPlanItem>> demoted =
                            NaruPlanExtension.plans(task.session()).reopenItem(p.id(), item.id(), false);
                    if (demoted.isError() || !demoted.isPresent()) {
                        context.errln("could not reopen item " + item.id());
                        return NaruStmtResult.ofError();
                    }
                    logInfo(context, NMsg.ofC("reopened %s\n%s",item.id(), p.render()));
                    return NaruStmtResult.ofSuccess();
                }
            });
            register(new AbstractSubCommand("clear", NText.ofPlain("remove a plan entirely, or the active plan when no id is given")) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    NaruTask task = context.task();
                    String ref = cmdLine.isEmpty() ? NaruPlanExtension.plans(task.session()).activePlanId() : cmdLine.next().get().image();
                    boolean removed = ref != null && NaruPlanExtension.plans(task.session()).removePlan(ref);
                    logInfo(context, NMsg.ofP(removed ? "plan removed" : "no such plan"));
                    return NaruStmtResult.ofSuccess();
                }
            });
        }

        private void logInfo(SubCommandContext context, NMsg message) {
            context.outln( NMsg.ofC("%s", message));
        }

        private NaruStmtResult logAll(SubCommandContext context) {
            logInfo(context, logListing(context));
            return NaruStmtResult.ofSuccess();
        }

        private NMsg logListing(SubCommandContext context) {
            NaruTask task = context.task();
            String active = NaruPlanExtension.plans(task.session()).activePlanId();
            NTextBuilder sb = NTextBuilder.of();
            for (NaruPlan p : NaruPlanExtension.plans(task.session()).plans().values()) {
                int done = 0;
                for (NaruPlanItem i : p.items()) {
                    if (i.status() == NaruPlanItemStatus.DONE) {
                        done++;
                    }
                }
                sb.append(p.id(),NTextStyle.pale());
                if (p.id().equals(active)) {
                    sb.append(" (").append("active", NTextStyle.success()).append(")");
                }
                sb.append(' ').append(p.status().name().toLowerCase(),NTextStyle.primary1())
                        .append(' ').append(done).append('/').append(p.items().size(),NTextStyle.number())
                        .append(" - ").append(p.goal()).append('\n');
            }
            return sb.isEmpty() ?
                    NMsg.ofStyledError("no plans exist yet") :
                    NMsg.ofNtf(sb.build())
                    ;
        }
    }
}
