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

import java.util.ArrayList;
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
            register(new AbstractSubCommand("create", NText.ofPlain("create a new empty plan with a goal")
                    , new SubCommandHelp("<goal...>",
                    "create a plan holding only its goal; add items with /plan add")
            ) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    List<String> words = new ArrayList<>();
                    try {
                        cmdLine.matcher().whenNonOption().asArg(a -> words.add(a.image())).requireAll();
                    } catch (Exception e) {
                        return fail(context, String.valueOf(e.getMessage()));
                    }
                    String goal = String.join(" ", words).trim();
                    if (goal.isEmpty()) {
                        return fail(context, "usage: /plan create <goal...>");
                    }
                    NaruPlan plan = NaruPlanExtension.plans(context.task().session())
                            .createPlan(goal, new ArrayList<>());
                    logInfo(context, NMsg.ofC("created plan %s\n%s",
                            NMsg.ofStyledPrimary1(plan.id()), plan.render()));
                    return NaruStmtResult.ofSuccess(plan.toElement());
                }
            });
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
            register(new AbstractSubCommand("goal", NText.ofPlain("restate what the active plan is for")
                    , new SubCommandHelp("<text...>", "set the goal of the active plan; the shape of the "
                    + "plan is unchanged, only its purpose")
            ) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    List<String> words = new ArrayList<>();
                    String[] planRef = {null};
                    try {
                        cmdLine.matcher()
                                .when("--plan").asEntry(a -> planRef[0] = a.stringValue())
                                .whenNonOption().asArg(a -> words.add(a.image())).requireAll();
                    } catch (Exception e) {
                        return fail(context, String.valueOf(e.getMessage()));
                    }
                    String goal = String.join(" ", words).trim();
                    if (goal.isEmpty()) {
                        return fail(context, "usage: /plan goal [--plan=<id>] <text...>");
                    }
                    NaruPlanManager plans = NaruPlanExtension.plans(context.task().session());
                    NOptional<NaruPlan> planOpt = targetPlan(plans, planRef[0]);
                    if (planOpt.isError() || !planOpt.isPresent()) {
                        return fail(context, reason(planOpt, "no plan to edit"));
                    }
                    NaruPlan plan = planOpt.orNull();
                    NOptional<NaruPlan> result = plans.setGoal(plan.id(), goal);
                    if (result.isError() || !result.isPresent()) {
                        return fail(context, reason(result, "plan " + plan.id() + " kept its old goal"));
                    }
                    logInfo(context, NMsg.ofC("plan %s goal is now: %s",
                            NMsg.ofStyledPrimary1(plan.id()), goal));
                    return NaruStmtResult.ofSuccess(result.orNull().toElement());
                }
            });
            register(new AbstractSubCommand("add", NText.ofPlain("append an item to a plan")
                    , new SubCommandHelp("<text...> [--after=<item>] [--key=<k>] "
                            + "[--validator=<none|model-review|user-approval>] [--plan=<id>]",
                    "append one item; --after may be repeated and names an item id, prefix or key; "
                            + "--plan targets a plan other than the active one")
            ) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    List<String> words = new ArrayList<>();
                    List<String> after = new ArrayList<>();
                    String[] key = {null};
                    String[] validator = {null};
                    String[] planRef = {null};
                    try {
                        cmdLine.matcher()
                                .when("--after").asEntry(a -> {
                                    String v = a.stringValue();
                                    if (v != null) {
                                        after.add(v);
                                    }
                                })
                                .when("--key").asEntry(a -> key[0] = a.stringValue())
                                .when("--validator").asEntry(a -> validator[0] = a.stringValue())
                                .when("--plan").asEntry(a -> planRef[0] = a.stringValue())
                                .whenNonOption().asArg(a -> words.add(a.image()))
                                .requireAll();
                    } catch (Exception e) {
                        return fail(context, String.valueOf(e.getMessage()));
                    }
                    String description = String.join(" ", words).trim();
                    if (description.isEmpty()) {
                        return fail(context, "usage: /plan add [--plan=<id>] <text...> [--after=<item>]");
                    }
                    NaruPlanManager plans = NaruPlanExtension.plans(context.task().session());
                    NOptional<NaruPlan> planOpt = targetPlan(plans, planRef[0]);
                    if (planOpt.isError() || !planOpt.isPresent()) {
                        return fail(context, reason(planOpt, "no plan to edit"));
                    }
                    NaruPlan plan = planOpt.orNull();
                    // --after takes an id, a unique id prefix or the declaration key the board
                    // prints, so resolve here and pass ids down: addItems speaks keys of the same
                    // batch, and a human quoting 8 characters from the panel should not have to
                    // know which of the three forms they were looking at
                    List<String> dependencyIds = new ArrayList<>();
                    for (String ref : after) {
                        NaruPlanItem target = resolveItem(plan, ref);
                        if (target == null) {
                            return fail(context, "no item matching '" + ref + "' in plan " + plan.id());
                        }
                        dependencyIds.add(target.id());
                    }
                    NOptional<NaruPlanValidatorKind> vk = NaruPlanValidatorKind.parse(validator[0]);
                    if (vk.isError()) {
                        return fail(context, "unknown validator '" + validator[0]
                                + "': use none, model-review or user-approval");
                    }
                    NaruPlanItemSpec spec = NBlankable.isBlank(key[0])
                            ? NaruPlanItemSpec.of(description)
                            : NaruPlanItemSpec.of(key[0].trim(), description);
                    spec.validator(vk.orNull());
                    for (String id : dependencyIds) {
                        spec.dependsOn(id);
                    }
                    NOptional<NaruPlan> result = plans.addItems(plan.id(), List.of(spec));
                    if (result.isError() || !result.isPresent()) {
                        return fail(context, reason(result, "the plan rejected that item"));
                    }
                    logInfo(context, NMsg.ofC("item added to plan %s\n%s",
                            NMsg.ofStyledPrimary1(plan.id()), result.orNull().render()));
                    return NaruStmtResult.ofSuccess(result.orNull().toElement());
                }
            });
            register(new AbstractSubCommand("edit", NText.ofPlain("change an item's text or validator")
                    , new SubCommandHelp("<item> [<text...>] [--notes=<text>|--no-notes] "
                            + "[--validator=<none|model-review|user-approval>] [--plan=<id>]",
                    "edit one item of a plan (the active one unless --plan names another); positional "
                            + "text becomes the new description. Quote a multi-word value "
                            + "(--notes=\"asked the reviewer\") or use /plan note")
            ) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    List<String> words = new ArrayList<>();
                    List<String> positionals = new ArrayList<>();
                    NaruPlanItemPatch patch = NaruPlanItemPatch.of();
                    boolean[] clearNotes = {false};
                    String[] validator = {null};
                    String[] planRef = {null};
                    try {
                        cmdLine.matcher()
                                .when("--no-notes").asFlag(a -> {
                                    if (a.booleanValue()) {
                                        clearNotes[0] = true;
                                    }
                                })
                                .when("--notes").asEntry(a -> patch.notes(a.stringValue()))
                                .when("--validator").asEntry(a -> validator[0] = a.stringValue())
                                .when("--plan").asEntry(a -> planRef[0] = a.stringValue())
                                .whenNonOption().asArg(a -> positionals.add(a.image()))
                                .requireAll();
                    } catch (Exception e) {
                        return fail(context, String.valueOf(e.getMessage()));
                    }
                    if (positionals.isEmpty()) {
                        return fail(context, "usage: /plan edit [--plan=<id>] <item> [<text...>] "
                                + "[--notes=<text>|--no-notes] [--validator=<k>]");
                    }
                    String itemRef = positionals.remove(0);
                    for (String w : positionals) {
                        words.add(w);
                    }
                    String description = String.join(" ", words).trim();
                    if (!description.isEmpty()) {
                        patch.description(description);
                    }
                    if (clearNotes[0]) {
                        patch.clearNotes();
                    }
                    if (validator[0] != null) {
                        NOptional<NaruPlanValidatorKind> vk = NaruPlanValidatorKind.parse(validator[0]);
                        if (vk.isError()) {
                            return fail(context, "unknown validator '" + validator[0]
                                    + "': use none, model-review or user-approval");
                        }
                        patch.validator(vk.orNull());
                    }
                    NaruPlanManager plans = NaruPlanExtension.plans(context.task().session());
                    NOptional<NaruPlan> planOpt = targetPlan(plans, planRef[0]);
                    if (planOpt.isError() || !planOpt.isPresent()) {
                        return fail(context, reason(planOpt, "no plan to edit"));
                    }
                    NaruPlan plan = planOpt.orNull();
                    NaruPlanItem item = resolveItem(plan, itemRef);
                    if (item == null) {
                        return fail(context, "no item matching '" + itemRef + "' in plan " + plan.id());
                    }
                    NOptional<NaruPlanItem> result = plans.editItem(plan.id(), item.id(), patch);
                    if (result.isError() || !result.isPresent()) {
                        return fail(context, reason(result, "item " + item.id() + " could not be edited"));
                    }
                    logInfo(context, NMsg.ofC("item %s updated\n%s", result.orNull().id(),
                            plans.findPlan(plan.id()).orNull().render()));
                    return NaruStmtResult.ofSuccess(result.orNull().toElement());
                }
            });
            register(new AbstractSubCommand("note", NText.ofPlain("write an item's note")
                    , new SubCommandHelp("<item> <text...> [--plan=<id>]",
                    "replace the free-text note an item carries; clear it with /plan edit <item> --no-notes"
                            + ". --plan targets a plan other than the active one")
            ) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    List<String> positionals = new ArrayList<>();
                    String[] planRef = {null};
                    try {
                        cmdLine.matcher()
                                .when("--plan").asEntry(a -> planRef[0] = a.stringValue())
                                .whenNonOption().asArg(a -> positionals.add(a.image()))
                                .requireAll();
                    } catch (Exception e) {
                        return fail(context, String.valueOf(e.getMessage()));
                    }
                    if (positionals.size() < 2) {
                        return fail(context, "usage: /plan note [--plan=<id>] <item> <text...>");
                    }
                    String itemRef = positionals.remove(0);
                    String text = String.join(" ", positionals).trim();
                    if (text.isEmpty()) {
                        return fail(context, "the note is empty; clear it with /plan edit " + itemRef + " --no-notes");
                    }
                    NaruPlanManager plans = NaruPlanExtension.plans(context.task().session());
                    NOptional<NaruPlan> planOpt = targetPlan(plans, planRef[0]);
                    if (planOpt.isError() || !planOpt.isPresent()) {
                        return fail(context, reason(planOpt, "no plan to edit"));
                    }
                    NaruPlan plan = planOpt.orNull();
                    NaruPlanItem item = resolveItem(plan, itemRef);
                    if (item == null) {
                        return fail(context, "no item matching '" + itemRef + "' in plan " + plan.id());
                    }
                    NOptional<NaruPlanItem> result = plans.editItem(plan.id(), item.id(),
                            NaruPlanItemPatch.of().notes(text));
                    if (result.isError() || !result.isPresent()) {
                        return fail(context, reason(result, "item " + item.id() + " kept its old note"));
                    }
                    logInfo(context, NMsg.ofC("item %s noted\n%s", result.orNull().id(),
                            plans.findPlan(plan.id()).orNull().render()));
                    return NaruStmtResult.ofSuccess(result.orNull().toElement());
                }
            });
            register(new AbstractSubCommand("remove", NText.ofPlain("drop an item from a plan")
                    , new SubCommandHelp("<item> [--plan=<id>]",
                    "remove an item and the edges pointing at it; refused while a task is running it. "
                            + "--plan targets a plan other than the active one")
            ) {
                @Override
                public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                    List<String> positionals = new ArrayList<>();
                    String[] planRef = {null};
                    try {
                        cmdLine.matcher()
                                .when("--plan").asEntry(a -> planRef[0] = a.stringValue())
                                .whenNonOption().asArg(a -> positionals.add(a.image()))
                                .requireAll();
                    } catch (Exception e) {
                        return fail(context, String.valueOf(e.getMessage()));
                    }
                    if (positionals.size() != 1) {
                        return fail(context, "usage: /plan remove [--plan=<id>] <item>");
                    }
                    NaruPlanManager plans = NaruPlanExtension.plans(context.task().session());
                    NOptional<NaruPlan> planOpt = targetPlan(plans, planRef[0]);
                    if (planOpt.isError() || !planOpt.isPresent()) {
                        return fail(context, reason(planOpt, "no plan to edit"));
                    }
                    NaruPlan plan = planOpt.orNull();
                    NaruPlanItem item = resolveItem(plan, positionals.get(0));
                    if (item == null) {
                        return fail(context, "no item matching '" + positionals.get(0) + "' in plan " + plan.id());
                    }
                    NOptional<NaruPlan> result = plans.removeItem(plan.id(), item.id());
                    if (result.isError() || !result.isPresent()) {
                        return fail(context, reason(result, "item " + item.id() + " is still in the plan"));
                    }
                    logInfo(context, NMsg.ofC("item removed from plan %s\n%s",
                            NMsg.ofStyledPrimary1(plan.id()), result.orNull().render()));
                    return NaruStmtResult.ofSuccess(result.orNull().toElement());
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

        /**
         * The edit verbs' shared failure shape: the words go to the transcript and come back
         * as the statement's error value, so a desktop dialog and a terminal reader see the
         * same complaint.
         */
        private NaruStmtResult fail(SubCommandContext context, String message) {
            NMsg msg = NMsg.ofC("%s", message).asError();
            context.errln(msg);
            return NaruStmtResult.ofError(msg);
        }

        /**
         * The manager's reason, when it has one.
         *
         * <p>{@code NOptional.message()} is a {@code Supplier<NMsg>}, so printing the optional
         * itself yields "ErrorOptional@1b2c3" — a stack of hashes in the user's dialog. Only the
         * words the manager chose are shown; the fallback covers an optional built without a
         * message, which still must not come out as an address.
         */
        private static String reason(NOptional<?> result, String fallback) {
            java.util.function.Supplier<NMsg> message = result.message();
            NMsg msg = message == null ? null : message.get();
            return msg == null ? fallback : msg.toString();
        }

        /**
         * The plan a write verb should act on: the one named by {@code --plan <id>} when given,
         * else the active plan. Naming another plan edits it in place without changing which
         * plan is active, so a dormant plan can be shaped before it is ever run.
         */
        private static NOptional<NaruPlan> targetPlan(NaruPlanManager plans, String planRef) {
            if (NBlankable.isBlank(planRef)) {
                NaruPlan active = plans.activePlan().orNull();
                if (active == null) {
                    return NOptional.ofError(NMsg.ofC("no active plan; select one with /plan activate <id> "
                            + "or name one with --plan=<id>"));
                }
                return NOptional.of(active);
            }
            NaruPlan p = plans.findPlan(planRef).orNull();
            if (p == null) {
                return NOptional.ofError(NMsg.ofC("no plan matching '%s'", planRef));
            }
            return NOptional.of(p);
        }

        /**
         * An item addressed by full id, unique id prefix, or the declaration key it was authored
         * with. The board and {@code render()} print keys when the item has one, so a human
         * pointing back at what they can see has to be able to name it that way.
         */
        private static NaruPlanItem resolveItem(NaruPlan plan, String ref) {
            if (ref == null || ref.isBlank()) {
                return null;
            }
            NaruPlanItem byId = plan.findItemByPrefix(ref);
            if (byId != null) {
                return byId;
            }
            for (NaruPlanItem i : plan.items()) {
                if (ref.trim().equals(plan.keyOf(i.id()))) {
                    return i;
                }
            }
            return null;
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
