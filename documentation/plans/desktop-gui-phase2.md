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

## Open in Phase 2

- **E1** typed tool-call lifecycle from `NaruToolCallStmt` — engine half and desktop half both
  landed above. Nothing outstanding.
- **D1** flip `BlockStyle.markdown` for `AGENT_RESPONSE`, as its own commit, against the existing
  `render/MarkdownHtml` + `MarkdownRenderer`.
- **G1** a robot-driven scroll on a loaded transcript, next to this test.
- Task monitor: interrupt / kill / hold, which the task pane says it does not have yet.
