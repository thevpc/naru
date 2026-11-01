# naru-desktop todos
T1 (header chrome) and T2 (rail tab glyphs + tooltips) are done — see `../done/todo-ui-t1-header.md`, `../done/todo-ui-t2-rail-tabs.md`.
please for each of the following, take one, define a plan (replace kthe content with more details), tehn implement, test and finally move this todo item to done folder

## T3 plans

must have a way to edit plans (and/edit plan items)
see/select current plan

### The fork found while researching

`NaruPlanReader` (naru-api) is deliberately read-only — its javadoc says a host that edited
plans through that seam "would bypass the dependency and validator rules that keep a plan
honest", and `PlanPane` repeats the rule. So "edit plans" has two possible homes:

1. widen `naru-api` with a writer seam the desktop calls directly — breaks the stated rule and
   splits graph validation across two modules;
2. give `/plan` the edit verbs it does not have today (it only does show/list/activate/
   deactivate/reopen/clear) and let the desktop speak that directive — validation stays inside
   `naru-plan`, and a UI edit is the same statement a human would type.

**Choice: 2.** It is also what the extension already promises: `PROGRESS_RULES` tells the model
"if the plan turns out to be wrong, say so and let the user re-plan" — and today the user has no
way to. No `naru-api` change; no second write path.

### Grammar (active-plan scope)

Every edit acts on the **active** plan, so "see/select current plan" is not a separate feature
but the gate in front of editing: you point at a plan, then you change it. `/plan show`-style
prefix lookup is reused for item refs. Bash-style parsing, `cmdLine.matcher()`, positional text
joined with a space — the conventions `NaruProjectDirective` established.

- `/plan goal <text...>` — restate what the plan is for.
- `/plan add <text...> [--after=<item>]... [--key=<k>] [--validator=<none|model-review|user-approval>]`
  — append one item; `--after` repeats, readiness recomputed after.
- `/plan edit <item> [<text...>] [--notes=<text>|--no-notes] [--validator=<k>]` — patch the item;
  text with no `--` option changes only the description.
- `/plan remove <item>` — drop the item and every edge pointing at it, then recompute.

Guards, all enforced in the manager rather than in the UI: a `RUNNING`/`VALIDATING` item cannot
have its description changed or be removed (a live task is doing the work that text describes —
stop the task first, same reasoning as `reopenItem`'s non-terminal branch); readiness stays
derived, so no verb sets `READY`; completion still goes through the validator, so no verb sets
`DONE`. Cycle checks reuse `NaruPlan.assertAcyclic()`.

### Desktop

`PlanPane` grows affordances that compose those lines and hand them to
`NaruSessionModel.submitDirective` — the composer's own path, so a click lands in the transcript
as a statement and the panel stays a reader of `NaruPlanReader`, never a writer. Free text is
quoted for the parser (a description containing `--x` or a quote must survive the round trip).
Active plan gets an "active" marker plus deactivate; every other plan gets "activate" — that is
the "select" half.

### Cut from this pass, on purpose

Dependency *edges* are editable by text through `/plan add --after` and `/plan edit`'s validator,
but adding/removing an edge on an existing item (`depend`/`undepend`) has no verb yet and no UI:
an honest affordance for a DAG is a graph editor, not two more buttons on a row, and the row
already shows `after <keys>`. Recorded here rather than left as a silent gap.

## T4 tasks
we need to restore a panel that shows current session's tasks and marks current taskk (hence current conversation) and allows swapping to other tasks

## T5 ui
when selecting one session all the window is redrawn? why ? i expected only models change, so why the menus are redrawn etc....

## T6 settings, Workspace environment
there is no trivial way to "add" a new config param (perhaps a new button right with other remove etc...)

## T7 settings, Custom Model Providers
Need a way to configurer custom model providers and models

## T8 conversation
i dont know why
addHistory(NaruMessage.engine(NMsg.ofC("[%s] invoke : %s", id(), op.toElement()).asFiner().withIntent(NMsgIntent.PROGRESS)));
ends up showing in the conversation by default, its 'finer' so it should not unless we raise the shown level (by the perhaps add a why to select that in the conversation when debug mode is on)

## T9 render markdown

`````
\\\\\\## Target: `NaruSession` (interface) \\\\\\\```java /**  * The execution context for a single conversation or batch run.  * <p>  * A session is the primary unit of work in Naru. It holds the project directory,  * the message history, the access policy, the active model configuration, and the  * set of tasks that have been started within it. A session is created through  * {@link NaruWorkspace#newSession()} and configured via {@link NaruSessionBuilder},  * then started with {@link #start()} or {@link #run(NaruTaskSpec)}.  * <p>  * A session has a lifecycle:

`````
is displayed verbatim, must support markdown in the conversation transcript, this must include the case of "streaming" aka all the text is not provided as one 
and should be implemented in the most efficient manner


## T10 input text disappears
sometimes when opening a session, the input text does not show up, the only way to make it appear is to select (or unselect) a tag. sresiwing the window does not help

