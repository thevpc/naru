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

## Open in Phase 2

- **E1** typed tool-call lifecycle from `NaruToolCallStmt` — the tools panel and the untruncated
  tool card both need it (plan §4.3, "until E1 lands tool output is plain `SCRIPT`").
- **D1** flip `BlockStyle.markdown` for `AGENT_RESPONSE`, as its own commit, against the existing
  `render/MarkdownHtml` + `MarkdownRenderer`.
- **G1** a robot-driven scroll on a loaded transcript, next to this test.
- Task monitor: interrupt / kill / hold, which the task pane says it does not have yet.
