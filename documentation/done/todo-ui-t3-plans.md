# T3 plans — edit and select a plan — done

Moved out of `todo/todo-ui.md` on 2026-11-01.

## The question the todo asked

"Must have a way to edit plans (and/or edit plan items) / see/select current plan."

## The fork, and why it went the way it did

`NaruPlanReader` (naru-api) is deliberately read-only — its javadoc says a host editing plans
through that seam "would bypass the dependency and validator rules that keep a plan honest".
Two homes for "edit":

1. widen `naru-api` with a writer seam the desktop calls — breaks that rule and splits graph
   validation across two modules;
2. give `/plan` the edit verbs it lacked (it had only show/list/activate/deactivate/reopen/clear)
   and let the desktop speak the directive.

**First shipped: 2.** No `naru-api` change, one write path, and validation stayed where the graph
lives. It is also what the extension already promised: `PROGRESS_RULES` tells the model "if the plan
turns out to be wrong, say so and let the user re-plan" — until now the user had no way to.

**Later correction (2026-11-03): the desktop moved to option 1 without splitting validation.** A
`NaruPlanService` writer seam was added to `naru-api`, implemented by the same extension instance
that implements `NaruPlanReader`; the desktop calls it for every board action instead of composing a
`/plan` line. Validation still lives only in the extension — the seam exposes verbs, not the graph.
The directives remain for humans and models. See the last follow-up section.

The two halves of the todo turned out to be one feature with a gate: every edit verb acts on the
**active** plan, so "see/select current plan" is the door in front of "edit", not a separate panel.

## Shipped — extension (`extensions/naru-plan`)

- `/plan goal <text...>` · `/plan add <text...> [--after=<item>]… [--key=<k>] [--validator=<none|model-review|user-approval>]`
  · `/plan edit <item> [<text...>] [--no-notes] [--validator=<k>]` · `/plan note <item> <text...>`
  · `/plan remove <item>`. Bash-style `cmdLine.matcher()`, positional free text joined with a space,
  `SubCommandHelp` per verb — the conventions `NaruProjectDirective` set.
- New `NaruPlanItemPatch` — a `NaruSetting`-based patch (description / notes / clearNotes /
  validator), the same idiom as `NaruProjectPatch`. It carries **no status field on purpose**:
  readiness is derived and completion needs a validator, so no verb can fake either.
- `NaruPlanManager` gains `setGoal`, `editItem`, `removeItem`; the guards live in the manager, not
  the UI: a `RUNNING`/`VALIDATING` item cannot have its description rewritten or be removed
  ("its text is the instruction a task is working from"), while its note and validator remain
  editable; every mutation `touch()`es the plan and runs `recomputeAndFill`, so `--after` gating
  and `BLOCKED → READY` unblocking happen without the caller asking; empty patches and blank goals
  are errors, not silent no-ops.
- Item refs resolve by id, unique prefix, or declaration key (`resolveItem`). `--after=a` in a
  model-written plan is a key, and the prefix path alone rejected it.

## Shipped — desktop (`app/naru-desktop`)

- `PlanPane` is now a board you click: the active plan carries `⏸` deactivate, `＋` add and a
  click-to-edit goal; every dormant plan carries `▶` activate; each item carries click-to-edit text
  and `×` remove, except a running one, which renders a muted `…` so the reader never clicks a
  refusal. Editing replaces the label with a field + `✓`/`×` in the row — no dialog, and a repaint
  fired by a streaming turn never paints over a draft.
- Writes leave through `NaruPlanService`, implemented by the plan extension beside the graph. A
  first version composed `/plan` lines (`ui/PlanDirectives.java`) and submitted them as directives;
  that path and its quoting tests were removed when the service seam landed. The pane reads and
  writes the same extension instance, and a session without the plan feature shows no Plan tab.

## Gate

| Run | Result |
| --- | --- |
| `mvn -o -pl extensions/naru-plan test` | **101 tests, 87 green** |
| clean-`HEAD` baseline for the same module | 89 tests, 14 failures |
| new `NaruPlanEditDirectiveTest` | **12/12 green** |
| `mvn -o -pl core/naru-api,core/naru-impl,app/naru-desktop test` | **289 tests, 283 green** |
| desktop baseline before this work | 272 tests, 266 green |
| new `PlanPaneTest` + `PlanDirectivesTest` at that time | **11 + 6 green** |

Both suites' remaining reds are the pre-existing set, unchanged in class and count:
`NaruPlanGraphTest` 2, `NaruPlanModeSwitchTest` 1, `NaruPlanWorkflowTest` 11 (14 total, verified
against a worktree of clean `HEAD` at `/tmp/naru-baseline`), and on the desktop
`SplitPanesTest.zeroingTheFloorLetsTheDividerReachTheEnd` plus 5 `BudgetTextTest` errors
(`NDetachedEmptyOptional missing workspace`). +17 desktop tests, +11 extension tests, zero new reds.

## Deviations from the plan

- **`--notes=<text>` was planned, briefly became `/plan note`, and is now back.** `NCmdLine`'s BASH
  tokenizer rejected a quote anywhere inside a word (`--notes="a b"` → `illegal char "`), so during
  this todo the note moved to a positional verb. The defect was then fixed upstream in nuts
  (`PosixNShellHelper.parseCmdLineArr`, see `upstream-ncmdline-quoting.md`), so
  `/plan edit <item> --notes="multi word"` works as originally specified and the option is restored.
  The `note` verb stays: a sentence typed as prose is friendlier than one wrapped in an option, and
  clearing stays `--no-notes`.
- **`depend` / `undepend` cut.** Adding or dropping an edge on an *existing* item has no verb and no
  UI. An honest affordance for that is a graph editor, not two more buttons on a row, and the row
  already prints `after <keys>`; `/plan add --after` covers the case a model or a reader actually
  hits. Recorded rather than left as a silent gap.
- **No confirmation on `×`.** A remove sends immediately: it is one visible statement in the
  transcript, reversible by `/plan add` (its text is in the history), and the dangerous case — a
  live task's item — is refused by the manager. A modal would be the desktop re-inventing a safety
  rule the extension owns. Easy to reverse if the owner disagrees.
- **A seam the plan did not name.** `PlanPane` takes a `Supplier<NaruPlanService>` (and an optional
  write executor for tests), because the plan extension is not on the desktop test classpath. The
  earlier `Director`/`PlanDirectives` pair was removed with the directive write path.
- **`NOptional` error strings needed a helper.** `result.message()` is a `Supplier<NMsg>` (must be
  `.get()`) and `NOptional.toString()` prints `ErrorOptional@hash`, so a naive
  `"…" + result.message()` reached the reader as a lambda. `reason(result, fallback)` unwraps it and
  each verb carries a hand-written fallback. The same wart still exists, untouched and out of scope,
  in `PlanUpdateTool` (`"ERROR: " + planOpt.toString()`).
- **No live-window driving**, same as T1/T2: clicking the real board means moving a window over the
  owner's session on a shared display, so the click contract is proven through the EDT harness with
  an injected `Director` instead.
- One combined run reported `NoClassDefFoundError: PlanPane$Column` in a single test. It is a stale
  artifact race in the double `compiler:compile` execution of this build, not a code fault: the same
  class isolated run is 11/11 green and the full suite after it is at the table above.

## Follow-up — the board becomes a registered-plan list

The first board drew every plan fully expanded, which made a session with several plans a wall of
items and did not say clearly which plan was "current". It now renders one row per registered plan
(active first) and opens exactly one body at a time:

- a closed row shows the goal, `done/total · state` and active/dormant wording, and opens on click;
- every row carries `▶`/`⏸` (activate/deactivate), `＋` (add an item, opening the plan first) and
  `🗑` (delete the whole plan);
- the open plan keeps click-to-edit goal/items, `×` remove, and the add-item hint;
- the active plan is opened by default, and the selection survives reloads while the plan exists;
- the Plan tab is present only when the session exposes `NaruPlanService`, so a profile without
  `naru-plan` does not show an empty tab.

## Follow-up — one write implementation, in the extension

The real-desktop probe showed a plan created by the panel could be missing from the reader. The
installed extension jar was older than the source and did not know `/plan create`, so the directive
was silently dropped. That exposed the deeper problem: two copies of "what a plan write means" — the
parser and the panel — could drift, and a panel click could not report a version mismatch.

The fix removed the second path rather than teaching the panel to quote better:

- `core/naru-api` gained `NaruPlanService` (extends `NaruPlanReader`) and `NaruPlanException`.
  `NaruPlanService.of(session)` resolves the same extension instance the reader resolves.
- `NaruPlanExtension` implements the writer methods on top of `NaruPlanManager`; refused writes carry
  the manager's own reason.
- `PlanPane` calls `createPlan`, `setGoal`, `addItem`, `editItem`, `removeItem`, `activate`,
  `deactivate` and `deletePlan` directly. The write runs off the EDT; the board reloads after the
  call and shows the extension's message on refusal.
- `MainWindow` adds the Plan tab when the service is available and removes it when it is not.
- `PlanDirectives` and `PlanDirectivesTest` are gone; `PlanPaneTest`'s `RecordingPlanService`
  records service calls instead of directive lines.

The create path also selects the new plan, so creating one while another is active no longer looks
like nothing happened. `PlanPaneTest` is 18/18 green, `NaruPlanServiceTest` proves create-then-read
on the real extension, and the temporary desktop probe confirmed the service and reader are the same
instance under the real dev-profile classloader.
