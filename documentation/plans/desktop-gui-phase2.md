# Phase 2 — tools and tasks (v1 completion)

Started after the Phase 1 gate passed (see [desktop-gui-phase1.md](desktop-gui-phase1.md)) and the
three decisions the owner gave at that gate, recorded in plan §2.

## G3 closed: the 50 000-line budget is now a module test — and it found a real violation

`app/naru-desktop/src/test/java/.../model/TranscriptLoadBudgetTest` reproduces Phase 0 spike 3
headlessly: four threads publish 50 000 whole messages across the nine `NaruLogMode` values as fast
as they can through the real `InteractionHost`, and the test thread plays the 30 Hz timer, timing
each `flushOnce()` end to end on the real EDT. It asserts the plan's budget instead of reporting a
number for a human to nod at: no dropped events, one drain-plus-apply inside 16 ms, the transcript
inside its trim budget, exact character arithmetic (`retained == published + lines`), and every
publisher's last line present.

The first run failed, and it failed for a reason worth writing down:

```
one drain-plus-apply cost 39 ms on the EDT, the budget is 16 ms:
published=99999 flushedBatches=3 flushedEvents=99999 dropped=0 maxFlushMs=9 ...
```

`flushOnce()` bounded its drain by the **queue capacity**, so a flood that arrives inside one tick
was applied in one EDT turn — 33 000 events, 39 ms. Phase 0 had recorded `maxFlushMs=0` for the
same shape of load and called it a pass, because `InteractionHost.maxFlushMillis()` stops its timer
*before* the listener runs: the drain was always inside budget, the apply never was measured. The
widget apply number spike 3 did report (26 ms with a plain-text cell) was the same effect, seen
from the other side.

Fix: `InteractionHost.MAX_EVENTS_PER_FLUSH = 4_000`, applied as `min(capacity, 4000)` in
`flushOnce()`. Whatever is left waits for the next tick, in order; `drainAllOnEdt()` loops until the
queue is empty so shutdown still drains everything. 4 000 events is about 5 ms of model work and
120 000 events a second at 30 Hz — two orders of magnitude above what the engine produced in the
Phase 1 gate run (17 332 events over 302 s).

After the change, same test, verbatim:

```
TranscriptLoadBudgetTest lines=50000 blocks~2000 threads=4 wall-ms=865 drains=26
  max-drain-ms=4 published=99999 flushedBatches=27 flushedEvents=99999 dropped=0
  droppedChars=0 maxFlushMs=0 maxPublishUs=315 queueDepth=0
  transcript blocks=50000 retained-chars=3369000/3319000 trimmed-blocks=0
```

Two things that measurement says plainly. The interleaving of nine modes across four threads means
the block-boundary heuristic opens **one block per line** here — 50 000 blocks, which is a harsher
case than the ~2 000 blocks spike 3 assumed, and the model still applies a tick in 4 ms. And
`retained-chars` is exactly published characters plus one newline per line, so nothing was lost or
double counted.

Phase 1 gate harness re-run after the change (`/tmp/phase1-postflush.log`): **PASS** — the real
`ask_user` question with two numbered choices was answered again, the parked task resumed,
`dropped=0`, `maxFlushMs=0`, worst EDT ping 154 ms with zero over 250 ms. Desktop module: 29 tests
green, including the new one.

## E1 landed: typed tool-call lifecycle, and output stamped with its task

Engine PR, twelve files, no GUI dependency: `core/naru-api,core/naru-impl` → **228 tests green**
(220 before plus 8 new in `NaruToolCallLifecycleTest`).

`NaruToolCallStmt` now reports a structured lifecycle through the interaction instead of leaving a
host to parse the console line: `STARTED` (name + arguments as the model sent them), `PARKED` (an
`NaruInteractiveTool` returned `WAITING_FOR_INPUT`), then exactly one final `RESULT` or `FAILED`
with the **whole** result and the wall-clock duration. New API surface, all of it default methods so
every existing implementation compiles and behaves identically:

- `NaruInteraction.reportToolCall(NaruToolCallEvent)` — no-op by default.
- `NaruInteraction.write(mode, msg, taskId)` / `writeStream(mode, frag, end, taskId)` — delegate to
  the two-arg forms by default, "which is what keeps a terminal unchanged".
- `NaruOutput.taskId()` (`NaruOutput.NO_TASK` when unstamped) — plan §0 check 2, block/task
  attribution is no longer host-guessed.
- `NaruTurnEvent.Kind.TOOL_CALL`, so a headless `TurnApiHost` sees the same lifecycle.

Two decisions worth recording because they change what the plan asked for.

**There is no `exitCode`.** Plan §4.2 lists one in the `ToolCall` row. A `NaruTool` returns a
`String`; nothing in the engine has an exit code, and inventing one would mean a host displaying a
number that no layer produces. `NaruToolCallEvent` has `kind`, `taskId`, `callId`, `toolName`,
`arguments`, `result`, `durationMillis` and documents why it has no exit code. `FAILED` is derived
from the convention the engine already uses: `dispatch` returns a string beginning with `ERROR` for
an unknown tool, a permission denial, or a tool that threw. That is a real signal, not a guess, and
it is stated at the call site.

**The console line is untouched.** The PROGRESS `Result:` line is still emitted, still abbreviated
to 300 characters by `ImplNaruUtils.abbreviate`; the test asserts exactly that. The desktop gains
the untruncated text from the event, the terminal loses nothing. That is the §7 rule — each engine
PR carries a terminal-REPL regression check — met by construction rather than by inspection: a real
`file_read` turn in a temp project printed `🔧 Tool: file_read{path:"notes.txt"}` / `📤 Result:` /
`🤖DONE` with the patched classes and the identical three lines against the installed jars.

## E1 on the desktop: tool cards in the transcript, a Tools panel, no 300-character cap

Second commit of the same item, entirely inside `app/naru-desktop`. Desktop module: **54 tests green**
(25 of them new or rewritten for this).

`TranscriptEvent.ToolCall(NaruToolCallEvent)` joins the vocabulary. `InteractionHost.reportToolCall`
publishes it under the same `publishLock` as text, so a card lands in stream order; it is never
merged and never the event a saturated queue drops (`saturationDropsChunksButNeverAToolCall`, a flood
of 50 000 lines leaves all 40 cards intact). `NaruOutput.taskId()` is now consumed rather than
guessed: `BlockBoundaryHeuristic` closes a block when the task changes as well as when the mode does,
and the coalescer only merges a run that stays on one task.

Cards are keyed by `(taskId, callId)` and live in the existing block machinery, not a new widget: a
`STARTED` opens a `TranscriptBlock` with a `BlockStyle.TOOL` style, `PARKED` annotates it, the final
event writes the header plus the **whole** result and closes it — so scroll, collapse, the 4 MiB trim
budget and the off-EDT `<pre>` HTML cache all come for free. A card longer than 1 200 characters
opens collapsed. `ToolCallModel` keeps the same lifecycle as indexed rows for the Tools tab
(`ToolCallModel.MAX_ROWS` 500, 4 MiB of results), and `ToolsPane` is the side-tab `MainWindow` had
been stubbing; its detail view prints the result whole.

Two properties the model layer, not the host, is responsible for. A host never invents engine
vocabulary, so when a session closes with a call still open, `TranscriptModel`/`ToolCallModel` settle
it from `HostClosed` with an explicit `✗ no result: …` row instead of a fabricated `FAILED` event. And
`STARTED` always opens a fresh card — an unnamed tool called twice on one task without a `callId` must
not merge into the previous open card, which is a bug this pass caught and pinned with a test.

Measured, not asserted: a real turn against `qwen2.5-coder:14b` in `/tmp/naru-e1-tools` with
`-Dnaru.smoke.prefill=200` produced 17 calls, `longest-result=719 chars`,
`longer-than-300=4 shown-whole=4`, `unsettled=0 open-cards=0`, `VERDICT=PASS`. The dump shows the
contrast in one place: the card body is the complete 719-character
`ERROR: Unknown tool 'say_hello'. Available tools: …` string, while the PROGRESS line under it is the
same text cut at 300 — the console behaviour `NaruTerminalInteraction` keeps, untouched. The harness
now fails a run when cards are empty while panel rows exist, when anything is unsettled, or when
`longer-than-300 != shown-whole` with nothing trimmed.

`ToolCallModel` also drops whole rows under memory pressure rather than truncating one, and the
eviction never takes the newest row (`anOversizedResultEvictsHistoryButNeverTheCurrentCall`).

## E2 follow-up, E3, and the line nobody caught: three session-level engine changes

Owner decision after the E1 gate: do the E2 API shape, do E3 in full, and fix
`NaruSessionImpl.handleSessionCommand` — which plan §3.4 had left out of v1.

**`NaruSession.complete(rawLine, caret)`** is a default method that delegates to
`NaruLineCompletion.complete(this, rawLine, caret)`. Both shapes stay: the instance method is what a
GUI calls on every keystroke, the static is what a caller with no session-as-receiver calls (a
completion preview for a project that is not open, a parser test). A test pins that the two cannot
drift: `theSessionMethodAndTheStaticAgree`.

**E3, with one substitution.** `pendingInputs()`, `deliverPendingInput(id, text)`,
`deliverPendingInput(id, text, idempotencyKey)`, `isHibernateEligible()` and `hasLiveTasks()` are now
on `NaruSession` — they were already public on the impl, and `NaruSessionHandleImpl` has been calling
them through the concrete class. `rawSessionStore()` is **not** promoted, and that is a deliberate
deviation from plan §Phase 3: its javadoc says package-visible *on purpose*, because every method on
`NaruSessionStore` takes a `(uuid, scope)` pair, so handing it out gives a host write access to every
saved session in the project, not just its own. What Phase 3's versions/diff panel actually needs is
its own history, so the API gained `versionStore()` — the same `NaruSessionVersionStore` (list, head,
find, label, restore, diff, gc) bound to this session's uuid, with the scope resolved on every call so
a `setVisibility` move cannot leave the handle reading the folder it left. A session that has never
been saved reports no versions rather than throwing `IllegalArgumentException: no session <uuid>`; a
write against it says why.

**`handleSessionCommand` was an empty method, and `deliverInput` called it.** That is §0 check 5, and
the mechanism behind §1's "input silently dropped when no task is in the foreground": a headless host
that pushed a line with no foreground task got nothing back — no error, no log, no task. A terminal
cannot reach that path because its readline thread parks a task before a human can type, which is why
it survived this long. A line now goes through the same parser and the same task mode a readline line
would: `task.parseStatement(line)`, so a `/directive` runs as a directive and prose becomes a model
call, on an `INTERACTIVE` task that parks on its own readline when the turn ends — which is what gives
the *next* line a foreground. That task becomes the foreground, deliberately; `run(spec)` refuses to
hijack it because a spawned run has a host already talking to something, and here nothing is.

A line that does not parse leaves the task running rather than killing it. A task reaching a terminal
state is deregistered, and the last task leaving a session stops the session — so cleaning up a typo
by killing the task would turn a typo into a shutdown. `anUnparseableLineLeavesTheSessionRunning`
pins that.

**Terminal-REPL regression, §7.** By construction rather than inspection: the only production caller
of `NaruSession.deliverInput` in the repository is the engine's own dispatch inside
`NaruSessionImpl`, plus one test. `NaruTerminalInteraction` reads lines on its own thread and feeds
the task through `NaruTaskSchedulerView`, never through the session door, so none of the three changes
is on the console's path. `NaruLineCompletion` was already new-code-only, and the E3 methods were
already public.

**Measured.** `core/naru-api,core/naru-impl`: 238 tests (+10 new), and the failure set is identical
to the baseline taken before this work — 16 failures + 3 errors in five classes
(`NaruToolCallLifecycleTest`, `NaruRequestTailValidationTest`, `NaruGoDirectiveTest`,
`NaruLazyModelResolutionTest`, `NaruSessionInteractionTest`), all of them from the owner's in-flight
model-resolution edits, none from these. The 10 new tests pass.

## §4.4 is now a test: the desktop cannot take the console

The rule "never route any session through `richTerm(true)`" lived in a comment and in one
`.richTerm(false)` call, so `app/naru-desktop/src/test/java/net/thevpc/naru/desktop/model/RichTermInvariantTest.java`
replaces it with two tests: one boots through the real `NaruSessionModel` path — real EDT handoff, real
`InteractionHost`, real `NaruWorkspaceBuilder`, no mocks — and asserts the shared terminal is untouched;
one asks the engine for a rich terminal and asserts the same assertion *moves*, so the first test cannot
be passing because it watches something that never changes.

The channel needed choosing, and the obvious one is wrong. `isAutoCompleteSupported()` is the only
readable flag rich term sets (`DefaultNBootModel.enableRichTerm()` loads `nuts-term` and builds a
terminal from a spec with `autoComplete(true)`), and measured in this module's JVM it stays `false` even
after `richTerm(true)` — the answer depends on the terminal Nuts detected, not on the request. There is
no `isRichTerm()` getter and `commandAutoCompleteResolver` is setter-only. What does move is
**`NIO.of().systemTerminal().base()`**: the system terminal is an `NSystemTerminalRef` and enabling rich
term swaps the object behind it (`DefaultNSystemTerminalBase` → `DefaultSystemTerminal`), which is
precisely the process-global mutation §4.4 forbids. The test asserts on that identity, plus
`autoCompleteResolver()` and `commandHighlighter()`, which stay null because a desktop interaction is
not a `NaruTerminalInteraction`.

**The hazard the control found on the first run.** Undoing rich term is `NIO.systemTerminal(base)`, and
`NIO.systemTerminal()` returns the *ref*, not its base. Passing the ref back makes `base()` delegate to
itself; the next `isAutoCompleteSupported()` call recursed until the JVM threw `StackOverflowError`. So
the control snapshots `ref.base()` and restores that, and runs `@Order`-last with a `finally` that
re-checks the restore took — Nuts has no "disable rich term".

**Measured.** `app/naru-desktop`: 56 tests (+2), 0 failures, 0 errors; the new class costs 3.4 s, almost
all of it the real workspace boot and the `nuts-term` resolution the control triggers. The engine module
set was not touched by this commit.

## §6 landed: the permissions editor, and the store API underneath it

**The correction first.** The first cut of this panel carried its own copy of the project policy file
in `app/naru-desktop` — a 131-line `PermissionsFile` reader/writer, justified by the note in the
desktop pom that the desktop must not compile against an extension. The owner rejected that: the
duplicated format was the bug, not the constraint. This commit deletes `PermissionsFile` and its
tests and puts the shared surface where the constraint allows it, in `naru-api`.

**`NaruPermissionStore` (`core/naru-api/.../access/`).** `projectFile()`, `readProjectRules()`,
`readProjectDefaultEffect(fallback)`, `reloadProjectRules()`, `writeProjectRules(rules,
defaultEffect)` — the last two return `null` or a message, following the module's String-or-null
convention rather than `NResult` — plus the four `SOURCE_*` tags, the `.naru/permissions.tson` name,
and a static `of(NaruSession)` that resolves through
`session.registry().extension("permissions", NaruPermissionStore.class)`. It extends
`NaruSessionExtension` for exactly that lookup and nothing else: the registry's typed `extension()`
is the one door a host may use across the classloader boundary, which is why the interface has to
live in the shared module rather than in `naru-permissions`.

**`naru-permissions` implements it.** `NaruPermissionsRuleStore` becomes the single owner of the
format — read, write, load — and `NaruPermissionsExtension implements … , NaruPermissionStore`
delegates to that same store the `/permission` directive and the `permission_*` tools use, so a
desktop Save and a terminal reload cannot disagree about the bytes. The extension's `SOURCE_*`
constants now alias the API's, which is what keeps the pane's Source column and
`/permission list` describing the same thing.

**The desktop binds, and degrades honestly.** `NaruSessionModel` calls
`permissions.attach(session.accessPolicy(), NaruPermissionStore.of(session))` after boot;
`PermissionsModel.reloadFromProject()` / `saveToProject()` go through the bound store. A profile
without `naru-permissions` gets *"the naru-permissions extension is not installed, so there is no
project policy file to read; the live rules below are still editable"* and no write attempt — the
panel never falls back to parsing the file alone.

**Tests moved with the format.** The four file-format tests went to
`NaruPermissionsRuleStoreTest` (what the writer writes is what the loader reads, an id-less rule
gets a stable id, one malformed rule costs nothing else, an unparseable default falls back), and
`NaruPermissionsExtensionTest` gained `aHostThatNeverSeesThisClassReachesTheStoreThroughTheApi` —
resolve by type, write, read back in order, reload, and check the live policy tags the rule
`perm:project`. The desktop keeps behaviour: `PermissionsModelTest` drives a `FakeStore`, and
`withoutTheExtensionTheFileIsNamedUnavailableAndNotWritten` covers the missing-extension branch.

**The live gate run (harness `PermissionsSmoke`, §6 end to end).** A denial committed to the project
file before the session boots, in text, not through NARU:

```
project=/tmp/naru-perm-smoke8/proj  model=qwen2.5-coder:14b
edt-pings=292 max-ping-ms=9 stalls-over-16ms=0 stalls-over-250ms=0
transcript-retained-chars=3709 published=272 dropped=0 droppedChars=0 maxFlushMs=0 queueDepth=0
session booted in 2 s
permissions tab: attached=true rows=4 default-effect=ALLOW tab-selectable=true
rule smoke-deny-one-file: source=perm:project effect=DENY patterns=[data-1.txt] actions=[read]
project store: name=permissions rules-read=1 first-id=smoke-deny-one-file default-effect=ALLOW
denial after 12 s: tool=file_read status=FAILED result-chars=161 calls-this-turn=1
denial cards: chars=246 longest-line=90 result-shown-whole=true matched-rule=smoke-deny-one-file
              pane-hits=1 pane-blocks=1 policy-decisions=54
jump: tab=Permissions selected-row=smoke-deny-one-file on-screen=true
      geometry=x=1012 width=364 showing=true screen-width=1400 landed=true
save: 1 rule(s) written -> file rules=1 enabled=false
reload: rule enabled again=true
control turn (read notes.txt): allowed, status=OK chars=156
VERDICT=PASS
```

The pane at that moment is committed as `desktop-gui-phase2-permissions.png`: row 4 selected, `id
smoke-deny-one-file`, `position 4 of 4 (first match wins)`, `source perm:project`,
`fired 1 time(s) · allowed 0 · blocked 1`, footer `4 rules · 1 blocked by a rule · 54 decisions`.

Every hop is the real thing: the engine's loader reads the committed file, a real `file_read` is
refused by the real policy, the untruncated card is the one the desktop paints, and Save and Reload
travel through `NaruPermissionStore`. The control turn is what proves the rule is narrow.

**Three things this harness caught before it passed.**

*The model, not the desktop.* Runs 2 and 3 timed out at 597 s and 297 s with no denial at all. The
post-mortem showed the transcript: `I'm sorry, but I can't assist with that request.` The prompt
asked the agent to read `secret.txt`, and a safety-tuned local model refuses that sentence rather
than calling a tool. The denied file is now `data-1.txt`, and the turn has a second phrasing and a
retry. Nothing in the desktop was wrong.

*The Permissions panel was off the screen.* `TranscriptPane`'s `JList` is as wide as its widest cell,
and since the untruncated tool cards landed that is a whole tool result. The split pane gave the
transcript its preferred width and laid the tab panel past the right edge — so the jump selected the
right row in a panel nobody could see, and the run's first screenshot showed only `Verbose`. Fixed
by bounding the scroll pane's minimum size and clamping the restored divider to
`width - MIN_SIDE_WIDTH (380)`; the harness now measures the geometry as part of the jump claim
(`x=… width=… showing=…`), and paints the pane itself for the evidence file.

**A harness bug, not a product one.** Run 7 failed on `pane-hits=0 pane-blocks=0` while
`policy-decisions=54`. The counters are recorded on the worker that asked the policy and carried to
the screen by `NaruSessionModel`'s 2 Hz poll — one repaint per tick rather than one per check, which
is the design — so reading `model.row(id)` in the same millisecond as the denial is a race the pane
never loses for a human. The harness now waits one poll plus slack (`awaitCounted`, 5 s ceiling)
before it claims the counter is visible.

**Terminal path unchanged (§7).** Against the installed reactor
(`mvn -o -pl core/naru-api,extensions/naru-permissions install`): `naru --help` exit 0;
`naru -d /tmp/perm-repl -t "/permission list"` prints 4 rules including
`repl-deny-data [deny] type=file patterns=data-1.txt … source=perm:project`, i.e. the committed file
still loads through the refactored store; `/permission check` and `/permission explain` answer and
exit 0. `extensions/naru-permissions`: **42 tests, 0 failures**. `app/naru-desktop`: **69 tests
(+13), 0 failures, 0 errors**.

**Reds that are not this commit's.** `core/naru-impl` is red at HEAD — 238 tests, 16 failures + 3
errors — and reproduces exactly in a clean worktree at `9005174`, so it is not the working tree's
doing; this commit changes no `naru-impl` file. Bisected: `NaruToolCallLifecycleTest` +
`NaruLazyModelResolutionTest` are **14/14 green at `a9a115f`** and **9/14 red at `3720c87` ("[FIXED]
fixed prompt")**. That commit removed the best-fit tools-capable fallback from `NaruTaskImpl`
("resolve only a model the user or caller explicitly selected"), and those tests register a
`FakeProvider` without a `/model use`, so they now fail with `No model is selected` or
`NaruTask.model() == null`. One further red, `NaruTaskCompletionTest.cancelSettlesTheRunAsKilled`,
appears only with the uncommitted scheduler edits. Resolved below: the owner ruled the fallback gone,
the nine tests were adapted, and the module is green again.

## Explicit-model-only: the engine tests choose a model, they do not wait for one

Answer to that decision, from the owner: *"yes user must define a model to use either at session
creation time or when calling '/model use...'"*. The best-fit fallback stays deleted, so the tests
moved to the new rule rather than the other way round — `0319e8c`, five files under
`core/naru-impl/src/test`, **no main source touched**, which is what keeps §7 (never change agent
behaviour, never break the terminal REPL) true by construction for this commit.

Three harnesses (`NaruToolCallLifecycleTest`, `NaruRequestTailValidationTest`,
`NaruGoDirectiveTest`) now call `task.setModel(new NaruModelConfig("fake","fake-model"))` before
letting a scripted protocol or a tail rule be reached — the same door `/model use` opens.
`NaruLazyModelResolutionTest.theBestFitPicksTheOnlyToolsCapableModel` became
`nothingIsGuessedWhenTheUserHasNotChosenAModel` and asserts `assertNull(task.model())`: one
tools-capable model in the registry is not the user's choice.
`NaruSessionInteractionTest` had to change shape: with nothing selected, resolution reports nothing
at all, so asking a task for its model can no longer produce a warning. The test now runs a turn and
asserts the "No model is selected" complaint reaches the session's own interaction instead of the
process console — the channel it was always written to prove.

Measured in a clean worktree at `3af2095` with **only** these five files changed: the five classes
are **35/35**, and the whole module run (`mvn -o -pl core/naru-api,core/naru-impl test`) is
**238 tests, 0 failures, 0 errors** — `core/naru-impl` is green at HEAD for the first time since
`3720c87`. In the shared working tree the same suite reports **238 / 1**, the single red being
`NaruTaskCompletionTest.cancelSettlesTheRunAsKilled:574` ("session stopped"), which is **34/34 green**
in that same clean worktree; it belongs to the uncommitted scheduler edits in `NaruTaskImpl` /
`NaruPoisonTask`, and guessing at that behaviour was deliberately not done here.

Consequence for the desktop, worth a decision: no product code selects a model — only the smoke
harnesses, by typing `/model use <key>` into the input field like a user would. A project opened with
no chosen model therefore reads the engine's honest "No model is selected. Choose one yourself with
/model list and /model use <key>…" turn, which is correct but is the first thing a new user hits. The
model picker is planned for Phase 4.

## §6 landed: the task monitor, and the three defects only a live run could see

**What is on screen.** The Tasks tab is a tree of the session's live tasks with the terminal's own
flag letters, and four buttons that are the GUI form of `/task` subcommands: Hold/Resume (one control,
two directions), Interrupt, Kill, Foreground/Background. Four pieces, none of them reaching past
`naru-api`: `model/TaskTree` arranges engine order into spawn order with depths, `util/TaskText`
formats a row and the detail block in the terminal's wording (`#id STATUS [H] ←#parent age name —
error`, frames as `[1] 3 <routine> instruction`), `ui/TaskPane` is the widget, and the rows come from
the 2 Hz poll that already lives in `NaruSessionModel` — the one place the desktop reads the engine,
so nothing on screen can disagree with anything else about which tasks exist.

**The gate run is `smoke/TaskSmoke`**, and it earned its keep three times over. It opens the real
window, spawns a parent with two children and a fourth task, then walks the five hops of §6 —
tree, hold/resume, two live parallel turns, kill/interrupt, frames — reading every claim off the
widget rather than off the engine, and freezing the EDT stack when a ping goes over a second.

1. *A held task could not be un-held.* `setEnabled(live && !held)` left the button reading "Resume"
   while disabling it, so the only way back from a hold was the keyboard. The model-level unit test
   could not see this: only the widget has a label and an enabled flag at the same time.
2. *"Resume" performed a hold.* The listener called `TaskAction.HOLD` unconditionally, so fixing (1)
   alone produced a button that said Resume and re-held the task. Both directions now read the row's
   `held` flag, the same source the word is chosen from, and `TaskMonitorTest` order 5 pins the pair
   by clicking a real button and polling the engine: `Hold/enabled → Resume/enabled → Hold/enabled`.
3. *The monitor froze the window for eight seconds at a time.* This is the one worth reading twice.
   The poll itself is innocent — timed inside the tick, **worst 2 ms** — but the pinger measured
   **max-ping 8666 ms** with the EDT parked in an HTTPS read. The stack named the path:
   `TaskPane.showSelected → NaruSessionModel.taskDetail → NaruTask.model()`, and `model()` for a task
   whose model came from a hint asks the registry to enumerate every provider, including the one that
   answers over the network. The fix stays inside the desktop: the label is resolved on a daemon
   worker, asked once per task id, and `taskDetail` reads that cache — returning `resolving…` on the
   first look, with the pane re-reading on the next tick. `TaskMonitorTest` order 6 asserts the
   caller's clock (first read back in well under a second) and that the marker settles. Measured
   after the change: **max-ping 108 ms, stalls over 250 ms: 6 → 0**, and the frame budget is now part
   of the gate's verdict rather than a footnote, so the defect cannot come back as a passing run.

**Four engine facts, measured rather than assumed, and no engine change was needed.** Every API #23
asks for already exists, so §7's regression obligation is not triggered by this piece — nothing under
`core/` is in this commit.

- `session.newTask(spec)` parks the task **held and READY**, which is what makes a spawn tree
  observable instead of racing it to completion; and a task with nothing to run **ends and
  deregisters the moment it is un-held**. So the monitor's rows are live tasks only — the hold/resume
  round trip has to be taken on the session's own foreground task, the one task in a live session
  that is un-held and never deregisters. History for terminal tasks is Phase 3's event log, and the
  run says so in its own log line.
- A spawned **root** task does not inherit a `/model use` choice: resolution is hint → parent →
  session, and with none of them set `NaruPromptStmt` logs "No model is selected" and ends the task
  in a single tick. The harness therefore pins the model on the spec (`.model(key)`), which is the
  engine's explicit-selection path — the first version of hop 3 "passed the resume and showed nothing"
  because it expected a hint-less root task to run a turn.
- **The scheduler serialises ticks.** Two real model turns were live at the same instant
  (`max-live-at-once=2`, `both-live-at-once=true`, first seen together after 3 s) but never both
  `RUNNING` (`max-RUNNING-at-once=1`, sampled every 150 ms). §6's "live parallel tasks" is therefore
  verified as *simultaneously live, one tick at a time* — reported, not assumed, and the run prints
  both numbers so the distinction is on the record.
- **A kill's on-screen effect has two shapes**, and which one appears depends on what the scheduler
  swept first: the row is briefly drawn `KILLED`, or it is simply gone by the next poll. The harness
  accepts either and says which happened (`saw-KILLED-row=false row-gone=true in-3s`), because a
  monitor that promised a brown row that never arrives would be worse than no monitor. The engine's
  reason string survives the kill and is asserted (`stopped from the desktop task monitor`).

**Numbers.** Desktop suite **99 tests, 0 failures, 0 errors** (new: `TaskTreeTest` 7, `TaskTextTest`
12, `TaskMonitorTest` 6). Gate runs, both `VERDICT=PASS`: model-free (`-Dnaru.smoke.model=false`) and
model-driven against local Ollama with `qwen2.5-coder:14b` — 5 rows, `findTaskIdsByParent=2`, pane
geometry `x=1008 width=364 height=643` on a 1600-px screen, `detail: frames=1 stacktrace-items=1`,
`task-poll cost worst=2ms`, `edt-pings=117 max-ping-ms=108 stalls-over-16ms=5 stalls-over-250ms=0`.
Evidence: `/tmp/naru-task-tree.png`, `/tmp/naru-task-parallel.png`, `/tmp/naru-task-final.png`.

## §6/§7 re-landed: the owner's session-centric shell, then D1 and G1

The shell changed under the gate: `MainWindow` is now an undecorated session-centric window with the
task rail on the left (`JSplitPane.VERTICAL_SPLIT`, preferred 210x100), four side tabs ("Task tools",
"Task permissions", "Inspect" → `InspectorPane`, "Workspace" → `WorkbenchPane`) and two conversation
tabs ("Conversation", "Session log"), and `TaskPane` defaults to **interactive tasks only** with an
"All tasks" toggle that opens the whole tree. The work below is verification and fixes on top of that
design, not a redesign of it: nothing in `core/` and nothing in the owner's panes was edited.

Re-running `TaskSmoke` against the new layout is what made it worth doing, because the harness reads
claims off widgets and two of them were lying.

1. *Selecting a conversation tab reported the Workspace pane.* The window has two `JTabbedPane`s but
   one getter pair, so `selectSideTab("Conversation")` switched one pane while `selectedSideComponent()`
   read the other. `MainWindow` now resolves the pane that actually holds the title (`tabsHolding`) and
   remembers it (`lastTabbedPane`) so the reads follow the selection. The sweep that found it — every
   side tab selected, its component class and on-screen bounds asserted, one PNG each — is now part of
   `TaskSmoke` and feeds hop 1's gate (`side-tabs-laid-out=6` problems `=0`).
2. *Closing a session threw from a pane.* `NaruSessionModel.shutdown()` ends with one more
   `fireChanged()`, and `InspectorPane.sessionModelChanged()` answered that paint by reading
   `s.projectDir()` off a session the engine had already stopped: `IllegalStateException: session is
   stopped`. The fix is one line in the model — `session()` returns null once closed, documented as
   "no engine left to inspect" — and `TaskMonitorTest` order 7 pins it by closing a session while a
   `TaskPane`, `InspectorPane` and `WorkbenchPane` are all listening, with no try/catch to swallow the
   throw. A unit test of the panes alone could not have seen this: only the model knows it is closed.

**The interactive-only default is a real behaviour change, measured rather than argued.** §6 says the
monitor shows live parallel tasks; in a live session only the foreground task is a conversation
(`NaruTask.isConversation()` is true only for `INTERACTIVE` tasks — every `session.newTask(spec)` probe,
root or child, with or without statements, came back false), so the rail's default view is **one row**
and every spawned job is hidden until "All tasks". `TaskMonitorTest` order 4 was rewritten to that
reality instead of asserting past it: it derives the expected visible set from `TaskRow.conversation()`,
asserts the filtered view equals it, asserts a non-interactive row really was hidden, then clicks the
toggle and asserts the tree, the indent and the selection callback. A premise check caught my own first
attempt at that test: promoting a probe with `taskMode(INTERACTIVE)` made the children conversations
too ("3 interactive tasks: [4,5,6,7]"), so the test reads the model's flags instead of inventing them.
Whether one-row-by-default is the v1 answer is the owner's call and is listed below.

**D1 — markdown for agent answers.** One flag: `BlockStyle` gives `AGENT_RESPONSE` `markdown = true`.
`MODEL_RESPONSE` deliberately stays plain — the raw model turn is the transcript's verbatim record, and
rendering it would put the desktop's own interpretation between the user and what the model said. The
§4.3 assumption still does not hold: neither `thevpc-common-md` nor `nmarkdown` exposes an HTML writer,
so the in-house `render/MarkdownHtml` subset remains the v1 answer, and that is recorded in the
`BlockStyle` comment rather than only here. New `render/MarkdownRendererTest` (4 tests) pins the
pipeline rather than the flag: the mode that renders is the mode that answers, a closed agent block
comes back as `<b>start</b>` / `<li>first</li>` with no `## Plan` left in it and `htmlIsCurrent()`, a
streaming block is never rendered and its text is never cached early, and a second request for the same
block is dropped.

**G1 — scrolling under load.** `smoke/TranscriptSmoke` boots the real window, writes a deterministic
corpus (a heading/bold/inline-code/list/fenced-code answer plus a 21-line script block) 120 times
through `host().write(...)` — the engine's own seam, so the load arrives exactly as a live turn would
arrive — then drives the wheel with `java.awt.Robot` to the top and back to the tail, sampling the
scrollbar value and the visible row range at every step, with a 5 ms-sleeping pinger counting EDT
stalls per phase. Reproducible run (`-Dnaru.smoke.model=false`, project `/tmp/naru-transcript-smoke`):

- `load: wrote 120 turns (240 blocks, 4200 lines) in 14ms; pane shows 240 rows`, and the host lost
  nothing: `published=479 flushedBatches=96 flushedEvents=480 dropped=0 droppedChars=0 maxFlushMs=0
  maxPublishUs=369 queueDepth=0`.
- `scroll up: 5701/6304 -> 0/6304 rows 213-239 -> 0-2 in 25 steps / 1315ms (52ms/step)`;
  `scroll down: 0/6304 -> 5701/6304 rows 213-239 in 24 steps / 1437ms (59ms/step)` — the wheel moves
  the whole 240-row transcript, both directions, and reaches both ends.
- `markdown: rendered agent row=0 cell-chars=627 tags=[b,tt,pre,ul,li] literal-asterisks=false
  heading-marker-left-in-text=false` — the proof reads the cell the renderer produced, and only within
  the visible range, so an off-screen cached string cannot make the claim.
- Frames: run total `edt-pings=60 max-ping-ms=138 stalls-over-16ms=20 stalls-over-250ms=0`; during the
  two scrolls specifically `pings=20 stalls-over-16ms=16 stalls-over-250ms=0`. **That is the honest
  caveat of this gate: 16 of 20 pings missed one 16 ms frame while scrolling, none came close to a
  perceptible freeze.** The cause is visible in `/tmp/naru-scroll-top.png`: cells do not wrap, so each
  row is taller than the viewport's floor and a scroll repaints a wide `<pre>`. That is the transcript
  wrapping item below, not a markdown-renderer cost — the renderer only ever parses a closed block once.
- `VERDICT=PASS — markdown reaches the cell, the wheel moves a loaded transcript, the EDT kept its
  frame budget`. Screenshots: `/tmp/naru-scroll-top.png`, `/tmp/naru-scroll-bottom.png`,
  `/tmp/naru-markdown-row-0.png`, `/tmp/naru-transcript-smoke.png`.

**Numbers, re-measured on the new shell.** Desktop suite **99 tests, 0 failures, 0 errors** (new since
the §6 section: `MarkdownRendererTest` 4, `TaskMonitorTest` 6 → 7). Model-free `TaskSmoke` on the
owner's layout: `edt-pings=66 max-ping-ms=97 stalls-over-16ms=5 stalls-over-250ms=0`, tree 5 rows,
`findTaskIdsByParent=2`, hold/resume `Hold/enabled → Resume/enabled → Hold/enabled`, the killed row
left the monitor, all six side tabs laid out with the widget each one claims, rail
`x=3 width=333 height=465`. Model-driven gate against local Ollama `qwen2.5-coder:14b`, 120 s:
`VERDICT=PASS`, `edt-pings=152 max-ping-ms=92 stalls-over-16ms=7 stalls-over-250ms=0`, spawn tree 5
rows, two parallel turns `max-live-at-once=2` / `max-RUNNING-at-once=1` (serialised ticks, as recorded
above), kill `engine-status=KILLED reason=stopped from the desktop task monitor row-gone=true in-3s`,
interrupt `engine-flag=true rendered-in-detail=true status-after=RUNNING`, `task-poll cost worst=81ms`
(the 2 Hz poll now also feeds `InspectorPane`, so this is a different cost from the §6 run's 2 ms).
No §7 engine PR is owed by this piece: nothing under `core/` changed, and the terminal REPL path is
untouched. The engine suite still reports **238 tests, 1 failure** — `NaruTaskCompletionTest.
cancelSettlesTheRunAsKilled:574` ("session stopped") — and that failure is **now at HEAD, not in
somebody's uncommitted edits**: `git diff HEAD -- core` is empty, so the earlier note that pinned it to
"the uncommitted scheduler edits in `NaruTaskImpl` / `NaruPoisonTask`" is superseded, and it arrived
with the committed scheduler work (`77da0ca` / `a4d7f6f`). Proof it is not the desktop's: a clean
worktree at `a4d7f6f` with **no** desktop changes runs the same class **34 / 1**, same assertion, same
line. Reading the scheduler to make it green would be changing agent behaviour, so it is reported as a
decision rather than fixed here.

## The composer: a real editor, the engine's completion, and the defect only a window could show

The owner's instruction was to replace the single-line field with a multiline component capable
enough to hold Ctrl+Space completion, reusing the engine's implementation rather than a host-side
guess, and then reuse that editor in the rest of the panes. Route chosen from the two options put
back to him: **RSyntaxTextArea 3.6.2 plus a popup driven by the engine's own replacement span** —
deliberately *not* `com.fifesoft:autocomplete`, whose word-boundary model would re-derive the span
`NaruLineCompletion` already computed and disagree with it.

Four files, and `InputPane` lost its key handling entirely:

- `ui/TextAreas` — the module's one text factory. `readOnly()` for detail blocks, `monospace(r,c)`
  for the routine editor, `plain(r,c)` under the composer. `apply()` pins the shared behaviour:
  `SYNTAX_STYLE_NONE` (no invented grammar — the engine's log modes are the only formats here), word
  wrap, no folding, no current-line highlight, no tab lines, and the PLAF's `TextArea.background` /
  `foreground` pushed onto the RSTA so FlatLaf does not show a white island.
- `ui/CompletionPopup` — the candidate list. Shows `insert`, `display`, and the `Kind` only when the
  engine says the set is `ambiguous()`, so `/help (command)` and `/home/ (directory)` are readable
  side by side instead of one of them silently winning.
- `ui/ComposerArea` — `RSyntaxTextArea` + the bindings. Ctrl+Space **and** Tab ask; with the list
  open Up/Down move and Enter takes the highlighted row, with it closed Up/Down are history at the
  ends of a draft and Enter sends; Shift+Enter is a newline; Escape dismisses; Ctrl+Backspace undoes.
  Every one is an `InputMap` binding, not a `KeyListener`, because the text component binds Enter and
  Tab itself and a listener would run after the widget had eaten them.
- `ui/InputPane` — the field is now `new ComposerArea(session::complete)`; the old `JPopupMenu`,
  `KeyListener`, `HistoryAction` and hand-rolled caret arithmetic are gone. §3.4's enable rule and the
  prompt/choice UI were not touched.

**The reuse, measured in the window the user opens** (`ComposerSmoke`): `TaskPane` 1 monospace area,
`InspectorPane` 5, `WorkbenchPane` 2 (diff + routine editor), 10 `RSyntaxTextArea` instances in the
live frame. `ToolsPane` and `PermissionsPane` detail blocks went through the same factory. A probe
settled the risk in the plain panes before taking it: `JTextArea` and `RSyntaxTextArea` both bind
`TAB → insert-tab` and `ENTER → insert-break`, so the swap changed no keystroke behaviour outside the
composer. `WorkbenchPane.fileView` stays a `JTextPane` on purpose — its syntax colouring is
`StyledDocument`-based and does not port.

**What the real window caught and the component test could not.** The first `ComposerSmoke` run
passed Ctrl+Space, saw the candidates, and then lost every keystroke: Enter did not accept,
Shift+Enter did not newline, the send did not fire — four failures. The measurement that named it:
`focus after ctrl+space: composer=false global=JRootPane in MainWindow`. A `JPopupMenu` is only a
lightweight child of the window when it fits inside that window's bounds; the composer sits at the
bottom of the frame and the list is up to ten rows tall, so Swing promoted the popup to its own
top-level window and on X11 that window took the keyboard. The unit test never saw it because its
frame was small enough for the list to fit.

The fix is structural, not a geometry tweak: the list is now a non-focusable card in the frame's
`JLayeredPane` (`POPUP_LAYER`), positioned in layered-pane coordinates, hung above the caret when it
would not clear the bottom edge, and sized to the widest candidate so it needs no horizontal
scrollbar. It cannot become a window, cannot outlive the frame's moves, and cannot take focus.
`ComposerAreaTest` order 9 pins exactly that: a composer at the SOUTH of a 520x400 frame with 12
candidates, asserting the card is inside the layered pane, that its host window is the frame, and
that `Window.getWindows().length` is **unchanged** by opening the list.

**Gate numbers.** `DISPLAY=:98 … ComposerSmoke /tmp/naru-composer-smoke`, FlatLaf Dark, real
`MainWindow`, real engine:

- `ctrl+space: 2 candidates, 82ms from the keystroke to the list being visible, engine work 1852µs`
  (a repeat run measured 3 ms to visible — the 82 ms is the first paint of the card, not the engine).
- `candidate[0] = /mode  —  /mode — manage AI modes`, `candidate[1] = /model  —  /model — manage AI
  models`; `popup: Rectangle[x=50,y=773,width=356,height=44] inside MainWindow`;
  `focus after ctrl+space: composer=true global=ComposerArea in MainWindow (main)`.
- `accept: "/mode" (was 4 chars, insert "/mode")` — the engine's span, not a word boundary.
- `shift+enter: line count 2, text "/mode\nx"`; then
  `send: "/mode x" answered the request task #1 was waiting on (yes), composer emptied=true`.
- `0 EDT stalls over 16ms since the first keystroke` (run total 3 of 76 pings, `max-ping-ms=828`, all
  of it at session boot as before). `VERDICT=PASS`. Screenshots `/tmp/naru-composer-open.png`,
  `/tmp/naru-composer-accepted.png`.

**The rail's parked-task rule, which the owner ruled on.** His correction — "session can spawn
multiple tasks, the first one usually is interactive, the result might be not, but don't take that for
granted" — withdrew my earlier claim that the rail shows one row by default; that was an artefact of a
harness that never promoted a task. What is actually true, and what the filter now handles:
`requestInput` does **not** require `INTERACTIVE` (`NaruTaskImpl:319-322`), and a pending input
register is keyed off the task's status rather than its mode (`NaruSessionImpl:1192-1195`). So a batch
task can park on a question, and an interactive-only filter would hide a task that is waiting for the
user — an unanswerable prompt. `TaskPane.current()` therefore shows rows that are conversations **or**
`BLOCKED_ON_INPUT`; `BLOCKED_ON_EVENT` stays out, since nothing is being asked of the user. Chosen
over the alternative (a badge on "All tasks") because the failure mode of hiding a parked task is a
stuck session. `TaskMonitorTest` order 4 now measures it: it picks a non-conversation row from the
model, parks it with `requestInput`, and asserts it appears in the default view while the other hidden
rows stay hidden.

**Suite: 109 tests, 0 failures, 0 errors** (was 99: `ComposerAreaTest` 10 new, the parked-task hop in
`TaskMonitorTest`). No §7 engine PR is owed — nothing under `core/` changed, the terminal REPL path is
untouched, and the only new dependency is on the desktop module's own classpath.

## Open in Phase 2

- **`core/naru-impl` is red at HEAD again**: `NaruTaskCompletionTest.cancelSettlesTheRunAsKilled:574`
  ("session stopped"), 238 tests / 1 failure, reproduced in a clean worktree at `a4d7f6f` with no
  desktop changes. It is the committed scheduler work, it is the owner's area, and a fix that reads the
  scheduler is an agent-behaviour change. Needs a ruling: adapt the test, or land the behaviour it
  asserts.
- ~~**Task rail: is interactive-only the v1 default?**~~ — closed by the owner's ruling: parked tasks
  always show. See "The composer" above for the measurement and `TaskPane.current()` for the rule.
- **The completion card is clipped, not scrolled, when the window is shorter than the list.** Ten rows
  is 204 px; a window dragged below that height cuts the rows instead of offering a scrollbar. The
  trade was deliberate (a scrollbar inside a layered-pane card competes with the composer for keys), but
  it is untested at that size.
- **The routine editor now word-wraps.** It went through the shared factory, so a long routine line
  folds rather than scrolling sideways. Right for reading, untested for editing; if it annoys anyone
  writing routines, that pane wants `setLineWrap(false)` rather than a different widget.
- **The "Conversation" tab is empty until a task row is clicked.** `MainWindow` sets its
  `TranscriptPane` filter to `-1` at activation, which matches nothing; the rail's selection callback is
  what fills it. A first-run user who never clicks the rail sees no answer pane.
- `MainWindow.openPermissions` / `selectedPermissionRule` are still stubs, so the denial card's §6 jump
  lands on the tab but cannot highlight the rule yet.
- **E1** typed tool-call lifecycle from `NaruToolCallStmt` — engine half and desktop half both
  landed above. Nothing outstanding.
- ~~**D1** flip `BlockStyle.markdown` for `AGENT_RESPONSE`~~ — closed: landed in `## §6/§7 re-landed`
  below, with a renderer pipeline test and a live proof read off the cell.
- ~~**G1** a robot-driven scroll on a loaded transcript~~ — closed: `smoke/TranscriptSmoke` above;
  it reports the frame budget honestly, and 16 of 20 pings during scrolling still exceed one 16 ms
  frame. The wrap fix below is why.
- ~~Task monitor: interrupt / kill / hold, which the task pane says it does not have yet.~~ — closed:
  landed above with its gate run. Two things it leaves open by design: the monitor keeps **no history**
  (a terminal task's row is gone, measured both ways), and a task's model line can lag one tick because
  the catalogue read moved off the EDT. Both belong to the Phase 3 event log and to the model picker.
- The engine's own cost is a separate question worth a decision: `NaruTask.model()` fans out to every
  provider — including an HTTPS call to OpenRouter — for each hinted task. The desktop now avoids it
  on the EDT; the terminal's `/task` view walks the same path, and any host that shows a task list at
  any refresh rate inherits it.
- Transcript wrapping: a long untruncated tool result still sets the list's preferred width, so the
  panel scrolls sideways. The split now clamps the divider and the tab panel is reachable, but the
  horizontal bar is the symptom of cells that do not wrap — the same work as D1's renderer pass.
- Permissions rule rows are cut at the pane's 380-pixel floor; a two-line row (id + patterns) would
  read better than a horizontal scroll.
- ~~`core/naru-impl` is red at HEAD~~ — closed: the owner ruled explicit-model-only permanent, the
  nine tests were adapted (`0319e8c`), and the module runs **238 / 0 failures / 0 errors** in a clean
  worktree at `3af2095`. See the section above.
- **Model picker: pull from Phase 4 into Phase 2?** With the fallback gone, a fresh desktop project
  with no selected model opens on the engine's "No model is selected" turn. The path exists today
  (type `/model use <key>`), but there is no button.
