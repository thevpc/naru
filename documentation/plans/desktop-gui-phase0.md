# Phase 0 gate report — spike measurements

Run on 2026-10-18, headless-Xvfb (`DISPLAY=:99`) so nothing popped on the owner's desktop,
JDK 25 (openjdk-25), Maven offline reactor, `HOME=/home/vpc`.
Everything below is output of the four spike mains in
`app/naru-desktop/src/main/java/net/thevpc/naru/desktop/spike/`; the commands are at the bottom.
Numbers are from the run, not impressions. Reproducibility caveat: the spike mains read no model,
no network — `FakeModel` streams deterministically.

## Verdicts

| Spike | Question | Result |
| --- | --- | --- |
| 1 seam + streaming | can `InteractionHost` carry a real session, and does the EDT stay alive? | **PASS** |
| 2 parking (kill criterion) | can `requestInput` be answered off-thread, does close release the worker? | **PASS** — engine is fine, Phase 1 may proceed |
| 3 block list under load | 50 000 lines while streaming | **PASS with LABEL / FAIL with HTML** — markdown cannot be rendered per live row |
| 4 docking | is `JDockPane` fit for a cockpit? | **FAIL** — take the planned `JTabbedPane` + `JSplitPane` fallback |

## Spike 1 — seam and streaming

`NaruWorkspace.of()` → session with `interaction(host)`, `configureDefaults(false)`, a registered
fake provider, then `task.chat(...)` streams through the real engine loop.

Scenario `slow` = 3000 chunks × 20 chars, 18 ms gap, 5 s tail (113 s wall clock):

```
host published=6004 flushedBatches=3487 flushedEvents=3284 dropped=0 droppedChars=0
     maxFlushMs=0 maxPublishUs=248 queueDepth=0
transcript blocks=2 chars=120000 appliedEvents=3284 maxApplyMs=0
edt-lag samples=22722 medianUs=2542 p99Us=5011 maxUs=8800
heapUsedMb=39
PASS-criteria: incremental=true publishNeverBlocks=true noDrops=true
```

- 3487 flushes in 113 s ≈ 30.8 Hz, i.e. the coalescer ran at exactly the configured ceiling.
- Worst single flush: 0 ms (rounded), worst EDT lateness over 22 722 samples: **8.8 ms**. The
  frame budget is 16 ms, so a long streaming turn never cost the user a dropped frame.
- Worker-side publish cost peaked at 248 µs — `write`/`writeStream` never blocked the engine.
- `blocks=2` (one thinking block, one answer block) is the block-boundary heuristic doing its job:
  6004 chunks merged into 2 transcript components, 3284 model updates.

Scenario `burst` = 2000 chunks, no gap: 2002 events, 58 batches, max flush 2 ms, max EDT lag 5.6 ms.

Scenario `flood` = 120 000 chunks as fast as the engine will emit them (buffer capacity 50 000):

```
host published=240004 flushedBatches=58 flushedEvents=8 dropped=58860 droppedChars=1765770
     maxFlushMs=11 maxPublishUs=9140 queueDepth=0
edt-lag samples=336 medianUs=2134 p99Us=5451 maxUs=15794
heapUsedMb=86
```

The saturated path behaves as designed: only `Chunk` events are dropped, one `Lagged` event tells
the widget a resync is needed, `InputRequested` is never dropped, the worker is never blocked
(publish still returned, 9.1 ms worst case is enqueue under contention, not a wait on the EDT),
and memory stayed bounded (86 MB). Nothing in v1 generates 120k chunks in one turn; this is the
guard rail, not the expected load.

## Spike 2 — parking (the kill criterion)

An INTERACTIVE task with nothing to run gets `NaruReadlineStmt` injected (`NaruTaskImpl:2228-2238`),
which calls `task.requestInput` — the same path a tool awaiting an answer uses. This is the real
production sequence, and it needs no model.

```
request-reached-host=true prompt-text=なる›
task-was-blocked-before-answer=true
deliver-from-edt-ms=0
task-resumed-after-answer=true (sameTaskAskedAgain=true) statusAfterAnswer=BLOCKED_ON_INPUT
second-request-seen=true requestsSeen=2 chunksSeen=2
close-released-parked-task=true statusAfterClose=FAILED
no-worker-left-BLOCKED_ON_INPUT=true
VERDICT=PASS — the seam works, Phase 1 may proceed
```

- `deliver()` called **from the EDT** returns in 0 ms and unblocks the worker; the proof of the
  resume is that the same task id asks a second question (a readline task re-parks the instant it
  consumes an answer, so a status snapshot right after the answer reads `BLOCKED_ON_INPUT` — that
  is correct behaviour, and the first version of this spike mis-scored it as a failure).
- `host.close()` cancels the outstanding request → `TaskInputRequest.cancel` → task `FAILED`, and
  no worker is left parked. Closing the window cannot leak a blocked thread.
- A side effect worth knowing in Phase 1: when the last task fails the **session stops itself**, so
  `session.tasks()` afterwards throws `IllegalStateException: session is stopped`. The status bar
  and the sessions panel must treat "session stopped" as a normal state, not an error.

**Trap found (cost a full run to discover).** `NaruSchedulerImpl.requeue` starts with
`if (task.isHeld()) return;`, so a task that parks while held never reaches
`session.onInputRequested` and the host never learns a question exists. The task sits in
`BLOCKED_ON_INPUT` silently. A desktop host must not create-hold-ask; questions only surface for
scheduled tasks. Worth a one-line engine test later (E-track), but it does not block v1: the GUI
will answer questions the engine asks, not fabricate them.

## Spike 3 — 50 000 lines while streaming

`LoadSpike <lines> <blocks> <threads> <label|html> [paceMicros]`: a visible frame, a `JList`
transcript, N publisher threads calling `host.write`, an EDT probe, and a separate thread timing
`SwingUtilities.invokeAndWait` round-trips while the load runs (that ping is the honest measure of
"user can click something").

LABEL renderer, paced to last 30.8 s (≈ the plan's real-world case):

```
publish-ms=30779 drain-ms=21
host published=99999 flushedBatches=938 flushedEvents=99999 dropped=0 maxFlushMs=0 maxPublishUs=7365
transcript blocks=50000 chars=3319000 appliedEvents=99999 maxApplyMs=26
EDT ping during load: n=306 avgMs=0 maxMs=25
edt-lag samples=854 medianUs=1668 p99Us=6504 maxUs=27663
heap-after-gc-mb=71
BUDGET flush<=16ms=true ping<=100ms=true noDrops=true
```

Same load, unpaced (everything arrives inside 200 ms, 7 flushes, i.e. the worst batch the coalescer
can hand the EDT): max apply 115 ms, ping max 57 ms, heap 57 MB. Still inside budget.

HTML renderer (one heavyweight `JTextPane`-style component per row), same paced load:

```
publish-ms=31073 drain-ms=22992
host published=99999 flushedBatches=26 flushedEvents=99999 dropped=0 maxFlushMs=0
transcript blocks=50000 appliedEvents=99999 maxApplyMs=14845
EDT ping during load: n=21 avgMs=2478 maxMs=14826
edt-lag maxUs=4613819
BUDGET ping<=100ms=false
```

**That is a 14.8-second EDT stall and a 23-second backlog.** The decision it forces: markdown must
not be rendered per live row. Per §4.3, the streaming block stays plain text; markdown renders only
on a **closed** block, lazily, only for blocks in the viewport, and off the EDT with the result
cached on the row model. This was a spike goal and it is now settled: the `thevpc-common-md` → HTML
path is fine for a handful of closed blocks per screen and catastrophic for 50 000 live rows.

Caveats to carry into Phase 1: rows in this run were 50 000 (one per line), which is pessimistic
versus the plan's "2 000 blocks"; the real host merges a stream into one row per block. The
`stressScroll` measurement returned 0 ms and is worthless — `ensureIndexVisible` does not force a
layout outside a real viewport. Scroll smoothness must be checked by hand in Phase 1, not claimed
from this spike.

## Spike 4 — docking

Each operation isolated so one crash cannot hide the rest.

```
frame+dock visible                        71 ms  ok
re-anchor (bottom -> right)                0 ms  ok   <- but see FINDING-1
re-anchor (right -> bottom)                0 ms  ok
remove from an edge anchor                 4 ms  ok
re-add to a different anchor               5 ms  ok
probe: fresh TOP group, two windows        9 ms  ok
probe: remove UNSELECTED window            2 ms  ok
probe: remove SELECTED window              0 ms  FAIL NullPointerException: Cannot read field "parent" because "comp" is null
probe: workaround, activate sibling then remove   3 ms  ok
probe: sibling really became visible       0 ms  ok
remove tools (as-is)                       0 ms  FAIL NullPointerException (same)
remove from CENTER                         1 ms  ok
re-add to CENTER                           1 ms  ok
churn 50 add/remove                       79 ms  ok
re-set dividers on a laid-out pane         0 ms  ok
divider re-read after layout + 400 ms: 29,109,109,109  v=300,302,302,302
re-anchor check: tools is still BOTTOM (asked for RIGHT)
```

1. **`JDockPane.setWindowAnchor(id, anchor)` is an empty stub** — the body is
   `if (a.getDockAnchor() != anchor) { //??? }`. It reports success and does nothing, so panels
   cannot be moved programmatically and a saved layout cannot be restored on startup.
2. **`remove(id)` throws** when the id is the group's currently visible window:
   `JDockTools.remove` (`JDockTools.java:136-141`) calls `add(null, BorderLayout.CENTER)` in that
   branch, and `Container.add(null, …)` is what dereferences the null. A cockpit closes and reopens
   panels constantly; this is the operation it needs most. (Activating a sibling first avoids the
   crash, but every close path in the app would then have to know that, and a user closing the
   visible tab directly still hits it.)
3. **Horizontal divider control is broken**: `setHorizontalDividerLocation(0..2, 400/440/480)`
   reads back 29/109/109 both before and after layout; the vertical call stuck. Layout persistence
   over the horizontal splits is not implementable on this API.

Mouse-drag docking was not automated and is now moot: with `setWindowAnchor` a no-op, a user's drag
could not be saved or restored anyway.

**Decision (plan §9 anticipated it): v1 uses `JTabbedPane` + `JSplitPane`, and owns its layout model**
— per-panel dock side (left/right/bottom/center), split proportions, collapsed state — persisted by
the app. That is less code than working around three library defects and it makes the layout
serialisable, which the plan needs for window-state persistence in Phase 5.
The `BlockBoundaryHeuristic` + `InteractionHost` do not care which container holds them, so this
changes nothing in the host seam.

## Answers to the plan's open items (§3)

1. **Project per window** — *needs your decision, this is the one item I cannot settle by
   measurement.* `NSwingUtils.setSharedWorkspaceInstance()` and
   `NScopedWorkspace.setSharedWorkspaceInstance(...)` are process-global (the setter returns the
   previous instance and `AbstractNWorkspace` installs itself on creation), pangaea-note/nops/ntexup
   all call it once at startup before the first frame. One JVM with several windows therefore has
   one shared workspace, and "one project per window" only works if each window's `NaruSession`
   carries its own workspace and no widget resolves through the shared instance.
   Two viable shapes: (a) **one window per JVM** — launch a process per project, which is the safe
   house pattern and costs project-switching speed; (b) **one window, many sessions**, with a
   project switcher — keeps v1 small, matches `NaruWorkspace` being the root object, and sidesteps
   the global entirely. I recommend (b) for v1.
2. **Session restore** — settled by code: rebind-in-place is supported (`NaruSessionImpl:1515-1527`,
   recorded as §3.5 in the plan). Nothing in Phase 0 contradicted it.
3. **Permission prompt** — still no engine source, so approval prompts stay out of v1 (§3.3).
   **But the tree moved under this phase:** commits `0be1fe5`/`c70ebce` add `AskUserTool`
   (`ask_user`, a model tool that pauses for a user answer with up to 12 choices),
   `NaruInteractiveTool` in naru-api, and an `awaitingInput` flag persisted on
   `NaruToolCallStmt`. That gives v1 a real, engine-supported input moment to design around: the
   prompt dialog should render question + choice buttons + free text, and the `awaitingInput`
   state is durable across save/restore, so a reopened session can show "waiting for your answer to
   ask_user". Plan §4.2/§6 updated to say so.

## What changed in the plan

- §2/§4.3/§9: docking resolved to `JTabbedPane` + `JSplitPane` with an app-owned layout model.
- §4.3: markdown rendering is closed-blocks-only, lazy, viewport-scoped, off-EDT — never on a live row.
- §4.2: `InputRequested` becomes a first-class v1 surface (choice buttons) because of `ask_user`;
  `PermissionRequested` stays out of v1.
- New constraint for Phase 1: a task parked while `held()` does not notify the interaction; and a
  session that loses its last task stops itself, so "session stopped" must be a normal UI state.
- Root pom already lists `app/naru-desktop`; `mvn -o -pl app/naru-desktop -am compile` is clean.

## Environment notes

- Around 12:00 every spike and `core/naru-impl`'s own `NaruStreamInputTest` failed with
  `AbstractMethodError: … InternalNVersionFilterRPI … NVersionFilter.byPredicate`. Cause was not the
  desktop module: the nuts artifacts in `~/.m2` were being rebuilt while tests ran, so the interface
  jar and the runtime jar were momentarily from different builds (`nuts-runtime` under
  `~/.nuts/ws/protos-prod/lib` still lacked `byPredicate`). Re-running the same command later passed.
  Consequence for CI: pin one nuts build, and do not assume a shared `~/.m2` is stable while an IDE
  is compiling the nuts tree.
- Spikes need `DISPLAY` (Xvfb `:99` was used here) because `prepareUI`/frame creation is AWT.

## Commands

```
cd app/naru-desktop && mvn -o -q compile
CP="target/classes:$(mvn -o -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && cat /tmp/cp.txt)"
DISPLAY=:99 java -cp "$CP" net.thevpc.naru.desktop.spike.StreamingSpike slow
DISPLAY=:99 java -cp "$CP" net.thevpc.naru.desktop.spike.StreamingSpike flood
DISPLAY=:99 java -cp "$CP" net.thevpc.naru.desktop.spike.ParkingSpike
DISPLAY=:99 java -cp "$CP" net.thevpc.naru.desktop.spike.LoadSpike 50000 2000 4 label 2400
DISPLAY=:99 java -cp "$CP" net.thevpc.naru.desktop.spike.LoadSpike 50000 2000 4 html 2400
DISPLAY=:99 java -cp "$CP" net.thevpc.naru.desktop.spike.DockSpike
```

## Gate conclusion

Spikes 1-3 pass their budgets and the kill criterion is satisfied, so the seam is real and Phase 1
can be built on `InteractionHost` as designed. Spike 4 failed and removes `JDockPane` from the
design in favour of the plan's own fallback. Nothing found requires an engine fix before Phase 1.

Blocked on one decision: **one window per project (one JVM each) versus one window with a project
switcher**. Everything else in Phase 1 I can start without asking again.
