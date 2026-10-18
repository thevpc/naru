# Phase 1 gate report — chat core

Run on 2026-10-18, `DISPLAY=:99` (Xvfb 1400x900), JDK 25, Maven offline reactor, `HOME=/home/vpc`,
real local model (`ollama/qwen2.5-coder:14b`, 9 GB) for the conversation and a deterministic
provider for the five-minute load (why: G4). Everything below is output of
`net.thevpc.naru.desktop.smoke.Phase1Smoke` and `mvn -o -pl app/naru-desktop test`; the commands
are at the bottom. Numbers are from the run, not impressions.

## Verdict

**Phase 1 gate: PASS.** A multi-step real task — a directive, two tool-using turns, an agent
question answered mid-turn — and a five-minute streamed turn ran end-to-end through the actual
widgets, the EDT stayed inside the Phase 0 budget, the parked task resumed on every answer, and
closing the window released the parked worker. The terminal REPL is unchanged.

| Gate item (plan §5 Phase 1) | Result |
| --- | --- |
| host + vocabulary + `NaruSessionModel` | **PASS** |
| block transcript (every `NaruLogMode` defined, thinking collapsed) | **PASS** |
| input area: multi-line, history, LINE/BLOC, disabled-with-reason (E8) | **PASS** |
| status bar: model, project, task state, tokens, save state | **PASS** |
| input dialog with choice buttons | **PASS** (inline panel, not modal — deviation D2) |
| FlatLaf + `JPlafMenu` + persisted theme, one window + project switcher | **PASS** |
| **E2**: raw-line completion, shipped in the desktop, terminal untouched | **PASS** |
| real task multi-step with tool calls runs without freezing | **PASS** |
| an approval in the run | **not applicable** — approvals are out of v1 (§3.3, owner decision) |
| 3-10 minute CPU prefill without freezing | **PASS at 5 min 02 s**: 333 KB streamed, zero EDT pings over 16 ms — but from a deterministic provider, not a real model (G4) |
| terminal REPL regression pass clean | **PASS** (see §"REPL regression") |

## Measurements

Headline run (`/tmp/phase1-gate-final2.log`): the scripted real-model conversation followed by
`-Dnaru.smoke.stream=300`, verbatim:

```
project=/tmp/naru-smoke-gate2  model=qwen2.5-coder:14b
transcript-blocks=72 retained-chars=339916
edt-pings=3276 max-ping-ms=117 stalls-over-16ms=2 over-250ms=0 over-1s=0
host-stats=published=17332 flushedBatches=9916 flushedEvents=9714 dropped=0
           droppedChars=0 maxFlushMs=0 maxPublishUs=132 queueDepth=0
session booted in 2 s (off the EDT)
sent /model use qwen2.5-coder:14b            turn took  3 s; blocks=2  chars=79
sent List the files … which one is largest.  turn took 16 s; blocks=53 chars=5264
sent Read alpha.txt and count its lines.     turn took  2 s; blocks=62 chars=6116
sent Call the ask_user tool … choices …      turn took  1 s; blocks=66 chars=6520
agent question from a real tool call: Which colour should I use? with 2 choices [red, blue]
the parked task resumed on the answer and parked again after 0 s
totals: requests=6 turns=4 agent questions answered=1 choices on the last one=2
last block mode=MODEL_RESPONSE closed=true text=You chose red.
stream load: fake provider, 8333 chunks x 20 chars every 18 ms, emitted twice
             (thinking then answer) = about 300 s, 325 KiB
  stream so far: +2220 chars, blocks=71 after 2 s
  stream so far: +168340 chars, blocks=72 after 152 s
  stream so far: +333321 chars, blocks=72 after 302 s
stream load ok in 302 s; blocks=70->72 chars=6595->339916 added=333321
stream EDT: pings=3018 max-ping-before=117ms max-ping-now=117ms stalls>16ms=+0
            stalls>250ms=+0
closing the window released every parked task
VERDICT=PASS — Phase 1 ran end-to-end
```

What the numbers mean against the Phase 0 budget (a flush must fit one 16 ms frame):

- **The long turn.** 333 321 characters arrived in 16 666 fragments over 302 s — 55 fragments a
  second, continuously — and during those 3018 EDT pings **not one** exceeded 16 ms, none exceeded
  250 ms, and the run-wide worst stayed 117 ms (recorded *before* the stream, while Swing painted a
  freshly attached split pane). `maxFlushMs=0`: the coalescer never spent a millisecond on the EDT.
  `dropped=0 droppedChars=0` with the queue ending at depth 0.
- **EDT.** 3276 pings for the whole run (one `invokeAndWait` round-trip every 100 ms), 2 over
  16 ms, both in the first seconds of the session, 0 over 250 ms, 0 over 1 s.
- **Throughput without loss.** 17 332 published events coalesced into 9 714 flushed events across
  9 916 batches — the merger collapsing same-block fragments — and the whole run stayed inside one
  4 MiB trim budget without trimming (`trimmed=0` at 6.5 KB, and the streamed blocks are the two
  166 660-char rows visible in the screenshot).
- **Worker side.** Worst `write`/`writeStream`/`requestInput` cost 132 µs including the style
  strip, so the engine was never waiting on the UI. This is the §4.4 invariant holding under load.
- **Parking, both directions.** 6 input requests reached the host; 5 were answered by the harness
  through `NaruSessionModel.submit` (the Send button's own call) and every parked task resumed —
  including the one parked by a real `ask_user` tool call with two numbered choices. `close()` then
  released the last parked worker: the task list never showed `BLOCKED_ON_INPUT` after shutdown.
- **Memory.** The transcript's 4 MiB trim budget never engaged on the scripted part; the streamed
  part put 333 KB into two rows and stayed under it. Phase 0's 50 000-line synthetic remains the
  real memory test, and it is still a spike, not a module test (gap G3).
- **Off-the-EDT boot.** `openProject()` returns immediately and the frame paints; the workspace
  and session boot on a `naru-session-boot` thread and take 2 s. Before this fix the same boot was
  a 1.9 s EDT stall — the only >1 s stall the first gate run recorded.

Screenshot of the finished run: [desktop-gui-phase1-evidence.png](desktop-gui-phase1-evidence.png)
(Xvfb capture at the end of the 302 s stream: collapsed rows with elapsed time and character
counts, the two streamed 2 min 30 s blocks at the bottom, the echoed `you> 1` choice answer, the
status bar reading `model call 5mn 985ms … · model fake/fake-model`, and the task pane showing the
foreground task parked while a second task is ready).

## What failed on the way, and what each failure turned into

Several gate runs failed before the first PASS; each cause became either a product fix or a rule
in the harness.

1. **Style markup in the transcript.** `Selected model : ##{p9:ollama}####{separator:/}####{p6:qwe…`
   appeared verbatim. `NMsg.toString()` returns its markup unrendered — rendering is the terminal
   formatter's job — and neither `toString(true)` nor `NText.of(msg).filteredText()` recovers text
   that arrived through a `%s` parameter (measured both). Fix: `util/MsgText.plain()` applied at the
   one place the host converts a message to text, plus the prompt path; five unit tests, including
   the exact leaked string. It keeps prose that merely contains a colon (`##{see note:here}##`) and
   leaves unterminated tokens alone rather than guessing.
2. **Engine boot on the EDT** — see the off-the-EDT bullet above. `NaruSessionModel.start()` now
   schedules the boot itself; only the poll timer and the status come back to the EDT.
3. **The harness could not see the second park.** `awaitRequest` compared request *objects*, so a
   park that followed an answer looked identical to the one already answered and the script
   silently stalled. Fix: `NaruSessionModel.inputRequestsSeen()`, a monotonic count of requests the
   host delivered; the harness advances a generation counter. The counter is also what the tools
   panel will need in Phase 2, so it is product state, not test scaffolding.
4. **`NoSuchMethodError: NaruSession.agent()` killed a worker thread** in the first real-model run,
   which then looked like a UI freeze. Root cause was packaging, not the engine: `app/naru-desktop`
   declared `naru-llm-models` and `naru-llm` as compile dependencies, so the desktop ran a
   provider jar built against an older `naru-api` (jar dated Oct 15, api rebuilt Oct 18) on its
   application classpath, while the console — which declares no extensions and lets Nuts resolve
   the profile at runtime — was fine. Fix: drop both dependencies (comment in the pom says why).
   **This is the shape of bug §4.4 warns about**, and it is worth keeping in mind for packaging in
   Phase 5: a desktop host that pins engine extensions ships the engine's build skew as its own
   crash.
5. **A parked task looked un-released at close.** `shutdown()` stopped the poll timer and froze the
   task list at its pre-close value, so `BLOCKED_ON_INPUT` was still displayed after the worker had
   actually been released. Fix: `shutdown()` performs one last refresh. The check now reports the
   specific task that stays blocked instead of a boolean.
6. **The transcript header lied during a long stream.** After the first 300 s run the header read
   `35 blocks · 171842 chars` while the model held 338 482: `TranscriptPane.syncRows()`, which owns
   the counter, only runs when the row *set* changes, and a five-minute stream grows one block
   without ever adding a row. Fix: the non-structural change path calls `updateCounter()` too
   (`retainedChars()` is an O(1) field, so this costs nothing per flush). Verified by the headline
   run above, whose screenshot reads `72 blocks · 339916 chars` against a model total of 339 916.

## Plan deviations (flagged, not worked around)

- **D1 — §4.3 markdown.** The plan assumes `thevpc-common-md` / `nmarkdown` produce HTML. They do
  not: the only writers are `DefaultMdWriter` (markdown out), `AsciiDoctorWriter`,
  `DocusaurusWriter`. Nothing in the Nuts ecosystem turns markdown into HTML for a Swing
  `JEditorPane`. Implemented: `render/MarkdownHtml`, a self-contained ~150-line markdown→HTML
  fragment renderer (fences, headings, lists, blockquotes, inline code/bold/italic/links, full
  escaping), with six unit tests, driven by `render/MarkdownRenderer` off the EDT and cached per
  block. It is wired but **not enabled** for `AGENT_RESPONSE` yet: the flag sits in
  `BlockStyle` with this reason recorded, and Phase 2 turns it on as its own reviewable change
  rather than inside a gate fix.
- **D2 — input dialog.** §5 says "input dialogs"; the implementation is an inline panel above the
  input area (question, one button per numbered choice, Cancel), not a modal `JDialog`. A modal
  steals focus while the agent may be asking several things in parallel tasks, and the plan's own
  §3.4 disabled-with-reason rule needs the field to stay reachable. Behaviour is identical:
  clicking a choice calls `echoAndDeliver(number, request)`, the same path as the Send button.
- **D3 — approvals.** Unchanged from the §3.3 owner decision: not in v1, so the gate line "an
  approval in the run" is recorded as not applicable rather than passed.

## Known gaps and what they cost

- **G1 — the manual scroll check Phase 0 could not do is still not automated.** The renderer is
  viewport-driven (`TranscriptPane` → `MarkdownRenderer.requestVisible(first,last)` from the
  vertical scrollbar's `AdjustmentListener`) and clicking a closed row toggles collapse, but no
  test drags a real scrollbar. `Phase1Smoke.stressScroll`-equivalent remains Phase 0's spike
  method; a robot-driven scroll on a loaded transcript belongs with the performance regression
  test in §8.
- **G2 — completion is exercised only by its unit tests.** `InputPane` calls
  `session.complete(rawLine, caret)` → `NaruLineCompletion`, and `NaruLineCompletionTest` (8) plus
  `NaruNArgCompleteResolverTest` (13) cover the engine side; the gate harness never presses Tab.
- **G3 — the 50 000-line budget is not yet a module test.** It lives in the Phase 0 spike main,
  which nothing runs on `mvn test`. §8 lists this as required; it should land as a headless test
  on `TranscriptModel` + `InteractionHost` (no widgets) before v1 ships.
- **G4 — the long prefill: satisfied in duration, not in source.** The gate asks for a 3-10
  minute CPU prefill. Four ways of coaxing `qwen2.5-coder:14b` into minutes of output all failed:
  a 2500-word essay and a 3000-word essay both refused, and a turn built to make the volume come
  from tool output — write `inventory.txt` with 6000 lines, then "read it and write a paragraph per
  hundred lines" — refused too ("I don't have the ability to directly read files"), and even with
  the tool named explicitly (`file_read`, the way `ask_user` is named in the script) it still
  refused. So the sustained load now comes from `FakeModel`, registered as a provider on the
  *running* session and streamed through the real engine loop, the real host, the real coalescer
  and the real widgets: 302 s, 16 666 fragments, 333 KB, zero pings over 16 ms, zero drops.
  **What that proves**: the transcript pipeline holds for five minutes of continuous output.
  **What it does not prove**: a real model's token pacing, or a real tool loop that runs for
  minutes — the real-model turns in the same run are 1-16 s each. §6's v1 acceptance asks for
  "10+ minutes", which is now one flag away (`-Dnaru.smoke.stream=600`), and the model-quality
  question is an owner decision (question 1 below).
- **G5 — the real model is not reliable turn to turn.** In one gate run every scripted turn came
  back as "I'm sorry, but I can't assist with that request", including the `ask_user` instruction,
  so the choice-button path was not exercised at all; in the headline run it was. The harness
  reports this honestly (`agent questions answered=0` plus a note) rather than retrying until the
  model behaves, but it means a green run is not proof the model cooperated — only that the
  widgets coped with whatever it did.

## REPL regression (§7, for the E2 commit)

E2 (`fb96df3`, `NaruLineCompletion` + the raw-line parser + the completer's use of it) touches the
terminal path only through `NaruNArgCompleteResolver`. Checks run against the installed reactor:

- `naru --help` → prints the manual, exit 0.
- `naru -d <dir> -t "/model list"` → 678 models listed, exit 0 (directive registry intact).
- `naru -d <dir> -t "/model use …" -t "List the files …"` → real multi-tool turn through the
  terminal interaction, exit 0, transcript rendered as before.
- `naru -i` with a piped script (`/model use`, a prompt, `/stop`) → directive executed, prompt ran,
  readline ended with the engine's own `input cancelled: terminal read failed: No line found`
  because stdin had no line — pre-existing behaviour for a non-tty stdin, unchanged by E2.
- `mvn -o -pl core/naru-api,core/naru-impl test` → **220 tests, 0 failures** (includes E2's
  `NaruLineCompletionTest` 8 and `NaruNArgCompleteResolverTest` 13). Re-run after the owner's
  later engine commits (`75b25b2` /call completion, `155a094` bench cleanup), still 220 and green.
- `mvn -o -pl app/naru-desktop test` → **28 tests, 0 failures**.

Not covered by these: an actual interactive TTY session where Tab is pressed (needs a real
terminal; the completion logic it calls is the unit-tested one), and the §7 item still open from
E2 — a `resolveCandidates` default so the ~31 directives that do not override it still complete
something other than nothing. E2 currently ships completion for what the registry answers; the
fallback is a Phase 2 follow-up, not a Phase 1 blocker, because the widget only shows what comes
back.

## Commands to reproduce

```bash
# unit tests
mvn -o -pl app/naru-desktop test
mvn -o -pl core/naru-api,core/naru-impl test

# classpath
mvn -o -q -pl app/naru-desktop dependency:build-classpath -Dmdep.outputFile=/tmp/naru-desktop-cp.txt

# gate run (short: scripted conversation only)
rm -rf /tmp/naru-smoke-proj && mkdir -p /tmp/naru-smoke-proj
DISPLAY=:99 java -Dnaru.smoke.dump=/tmp/naru-smoke-transcript.txt \
  -cp "app/naru-desktop/target/classes:$(cat /tmp/naru-desktop-cp.txt)" \
  net.thevpc.naru.desktop.smoke.Phase1Smoke /tmp/naru-smoke-proj qwen2.5-coder:14b 420

# gate run (headline: the same conversation plus the five-minute streamed turn)
DISPLAY=:99 java -Dnaru.smoke.stream=300 -Dnaru.smoke.dump=/tmp/naru-smoke-gate2-transcript.txt \
  -cp "app/naru-desktop/target/classes:$(cat /tmp/naru-desktop-cp.txt)" \
  net.thevpc.naru.desktop.smoke.Phase1Smoke /tmp/naru-smoke-gate2 qwen2.5-coder:14b 900

# -Dnaru.smoke.prefill=<lines> additionally appends a tool-reading turn built on a generated
# inventory.txt; kept in the harness because it is the closest thing to a long real-model turn,
# even though the local model refuses it (see G4)
```

The harness exits 0 only when every gate check passed, so it is usable as a smoke test once the
manual pieces around it are automated.

## Owner answers, recorded at this gate (also in plan §2)

1. **G4 — accepted.** The deterministic fake-provider stream is the Phase 1 evidence for the long
   load; the real-model turns stay short and are not held to the minute criterion. §6's "10+
   minutes" is settled the same way, with `-Dnaru.smoke.stream=600` at the v1 acceptance run.
2. **D1 — separate Phase 2 change.** Flipping `BlockStyle.markdown` for `AGENT_RESPONSE` is its own
   reviewable Phase 2 commit against the already-built and tested `render/MarkdownHtml`; v1 ships
   plain-text agent rows until then.
3. **G3 — now, before Phase 2 features.** The opening Phase 2 commit is a headless module test on
   `TranscriptModel` + `InteractionHost` that reproduces Phase 0 spike 3's 50 000-line load and
   asserts the budget (flush inside one frame, bounded memory), so every later Phase 2 feature is
   checked against it.
