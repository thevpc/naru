# NARU Desktop: Swing cockpit (plan v2, agent prompt)

You are implementing a desktop front-end for NARU. Work in phases. **Stop at every gate and report.** Do not start a phase until the previous gate has passed. Never change agent behaviour, and never break the terminal REPL.

## 0. First task: verify before building

This plan was written from a reading of the codebase that has not been checked. Before writing any code, produce a short verification report (file paths + line refs) confirming or correcting each claim below. If a claim is wrong, say so and propose the adjusted plan. Do not silently adapt.

1. `NaruInteraction` threading contract: `write` and `requestInput` may be called from any worker thread and must not block; answers arrive via `NaruInputRequest.deliver()`; first answer wins (`AtomicBoolean` in `NaruSessionImpl.TaskInputRequest`).
2. `NaruOutput` is `(sequence, instant, mode, message)`, plain text, no ANSI. Check whether it carries any block/turn/task identifier and any token counts. Expectation: it does not.
3. `NaruSessionHandleImpl` is package-private; `NaruTurnEvent.payload` is untyped `Object`; delivery is `ArrayBlockingQueue(128)` that clears on overflow with no `replay(fromSeq)`.
4. `NaruSessionBuilderImpl.enableRichTerm()` mutates process-global terminal state.
5. `handleSessionCommand` in `NaruSessionImpl` is an empty stub.
6. Nothing emits typed tool-call events today (name, args, duration, result, exit code).
7. How a permission/approval request currently reaches the user. Is it `requestInput` text, or a distinct mechanism? Report which.
8. `NaruNArgCompleteResolver` is terminal-bound; `NaruDirective.resolveCandidates` needs a parsed `NCmdLine`.
9. Toolchain: JDK 25 + toolchains entry, Maven 3.10.0, artifacts in `~/.m2` (`thevpc-common-swing` 2.5.0, `thevpc-common-plaf` 1.1.0, FlatLaf 3.7.1, `nuts-swing` 1.1.0.0).

## 1. Goal and non-goals

Goal: a desktop client that is better than the REPL at the things the REPL does badly: nine log modes shown as ANSI gutters, tool results truncated to 300 chars, no status surface, no view of parallel tasks, no approval prompt, input silently dropped when no task is in the foreground.

Non-goals: replacing the terminal UI (it stays, feature-comparable), changing agent behaviour, a web front-end.

## 2. Settled decisions

| Question | Decision |
| --- | --- |
| Binding | `InteractionHost` (in-process `NaruInteraction`) now; `TurnApiHost` (`NaruSessionHandle`) later, behind the same seam. |
| Toolkit | `thevpc-common-swing` 2.5.0 + FlatLaf 3.7.1 + `thevpc-common-plaf` 1.1.0 |
| Packaging | New `app/naru-desktop` module (`nuts.gui=true`, `nuts.application=true`); `app/naru` stays Swing-free and dispatches by coordinate at runtime (ndb pattern). Root pom gains the module. |
| Scope | Full cockpit is the destination, **but v1 is the cut line in §6.** |
| Rendering | Block-per-component transcript (see §4.3), not a single `JEditorPane`. |
| Engine work | Interleaved: each engine item lands immediately before the first widget that needs it. No separate "engine phase". |
| Long-load evidence | **DECIDED (owner, at the Phase 1 gate): the deterministic fake-provider stream is the v1 evidence** for the 3-10 minute prefill criterion; real-model turns stay short. §6's "10+ minutes" is met with `-Dnaru.smoke.stream=600` at the v1 acceptance run. |
| Markdown for agent responses | **DECIDED (owner, at the Phase 1 gate): a separate Phase 2 change** that flips `BlockStyle.markdown` for `AGENT_RESPONSE`, not part of the gate. Until then v1 renders agent text as plain text (deviation D1). |
| 50 000-line budget | **DECIDED (owner, at the Phase 1 gate): a headless module test first**, as the opening Phase 2 commit, before any Phase 2 feature work (gap G3). |

House patterns to copy: ndb (reflective dispatch via `NSearch`), kifkif (`-c/--console`, `UIPlafManager`, `JPlafMenu`), pangaea-note (`@NAppInstall` launchers), `NSwingUtils.prepareUI(args)` + `setSharedWorkspaceInstance()` before any frame, `SwingUtilities3.invokeLater/invokeAndWait` as the EDT bridge.

## 3. Resolve these before Phase 1 (defaults given; override if the owner disagrees)

1. **Project per window.** **DECIDED (owner): v1 is one window with a project switcher**, so
   `setSharedWorkspaceInstance()` stays single-valued per process and no widget may resolve
   through the shared instance — every action takes an explicit `NaruSession`/`NaruWorkspace`.
   Phase 5 (packaging/lifecycle) owns adding true multi-window, and the only forward-compatible
   requirement now is that widgets never read global workspace state.
2. **Session restore.** Default: rebind-in-place if `NaruSessionStore` supports it; otherwise create-new with history copied. Report which the store allows.
3. **Permission prompt.** **RESOLVED (owner, after §0).** Approval prompts are **out of v1**.
   §0 check 7 found there is no ask-the-user path in the engine at all: `NaruAccessEffect` has
   only `ALLOW, DENY`, the tool gate returns `"ERROR: permission denied: ..."` as a *string*
   straight into model history, and `requestInput` has exactly one production caller — the REPL
   readline. Building a real prompt means new policy semantics (tri-state ASK, a typed approval
   request at the gates, a park state, allow-once/always/deny, fail-closed default), which is a
   change to agent behaviour and therefore a §1 non-goal. v1 instead shows denials as full
   read-only cards (from E1, untruncated) and pulls the permissions editor forward from Phase 4
   — it needs no engine work. E9 is split off as its own proposal, outside this plan.
4. **Input with no foreground task (E8).** Default for v1: input field visibly disabled with the reason shown. Implementing `handleSessionCommand` is out of v1.
5. **Session restore.** **RESOLVED (by §0):** rebind-in-place is supported — `NaruSession.load(uuid)` (`NaruSessionImpl.java:1515-1527`) stops the world, reloads state, and fires `onSessionReloaded`; the builder also takes `.store(...)`/`.id(...)`. Phase 3 binds in place, no create-new compromise.

## 4. Architecture

### 4.1 Layers

```
Swing widgets            observe typed models only; never import naru-impl
      ↑
NaruSessionModel         per-session view-model, EDT-confined, coalesced updates
      ↑
NaruHost (seam)          emits the typed vocabulary in §4.2
  ├─ InteractionHost     NaruInteraction + NaruSessionListener (+ E1/E4 listeners as they land)
  └─ TurnApiHost         later, after the turn API is finished
```

### 4.2 Host event vocabulary (write this first, as code in `host/`)

Design it from what the widgets need, then map each backend to it. Each backend declares which events it can supply; widgets degrade gracefully when one is absent.

| Event | Fields | Needed by | InteractionHost source |
| --- | --- | --- |  --- |
| `TextChunk` | blockId, taskId?, mode, text, final? | transcript | `write` / `writeStream` |
| `BlockOpened/Closed` | blockId, mode, taskId? | collapsible blocks | derived: mode change or task change closes a block (heuristic) until the engine marks boundaries |
| `ToolCall{Started,Result,Failed}` | callId, taskId, tool, args, duration, result, exitCode | tools panel | **E1** (none today) |
| `TaskStateChanged` | taskId, parentId?, status, stack | task monitor | `session.tasks()` poll → listener |
| `InputRequested` / `InputAnswered` | requestId, prompt, kind | prompt dialog | `requestInput` / `deliver` — **in v1 this is a first-class surface**: `AskUserTool` (`ask_user`, commits `0be1fe5`/`c70ebce`) parks a model tool call with a question and up to 12 numbered choices, and `NaruToolCallStmt.awaitingInput` makes the wait durable across save/restore. Render choices as buttons plus free text. |
| `PermissionRequested` | requestId, subject, detail | approval dialog | **not in v1** — no engine source (§3.3). Keep the event type in the vocabulary so E9 can supply it later. |
| `UsageUpdated` | model, tokens in/out, cost | status bar, budget | `NaruBudgetExtension` |
| `SessionSaved` / `Hibernated` / `Resumed` | sessionId | status bar | `onHibernate/onResume`, store |
| `Lagged(fromSeq)` | | all | `TurnApiHost` only; widgets must handle resync |

Rule: if a widget needs data the vocabulary lacks, extend the vocabulary first, then the backends.

### 4.3 Transcript rendering

- Block-per-component list in a virtualized scroll container (only visible blocks are laid out). Each `BlockOpened` creates one component; collapse/expand is a component concern.
- Block types: `MODEL_THINKING` (collapsed by default, summary shows elapsed time and tokens when known), `AGENT_RESPONSE` (markdown), tool-call card (from E1), and `PROGRESS/TRACE/SCHEDULER` (hidden behind a verbose toggle).
- Markdown: render via `thevpc-common-md`/`nmarkdown` → HTML **only on closed blocks, lazily, for blocks inside the viewport, off the EDT, cached on the row model**. Phase 0 settled this: the same 50 000-row load that costs 26 ms per apply with a plain-text cell costs **14.8 s** with a heavyweight HTML cell per row (EDT ping 2.5 s average, 23 s backlog). A live streaming row is plain text, always. See `desktop-gui-phase0.md` spike 3.
- Until E1 lands, tool output appears as plain `SCRIPT` text blocks, untruncated and collapsible. That alone beats the REPL.

### 4.4 Streaming and threading

- Host callbacks never block and never touch widgets. They enqueue into a bounded coalescing buffer; a Swing timer flushes at ≤30 Hz and applies one batched model update per flush on the EDT.
- Markdown re-render never happens on a streaming block: live rows are plain text (see §4.3, Phase 0 spike 3). Closed blocks render once.
- Add an EDT-assertion helper that throws in tests and logs in dev if a widget is mutated off-thread.
- Process-global state: never route any session through `richTerm(true)` in the desktop host.

## 5. Phases and gates

### Phase 0: spike (throwaway, ~1-2 days)

Prove four things, each with a pass/fail result in the report:

1. **Seam:** `InteractionHost` wired via `workspace.newSession().interaction(host)...build().start()`, streaming from a real local Ollama model. Text arrives incrementally; EDT stays responsive during a 60 s call (measure max EDT stall).
2. **Parking:** an `INPUT_REQUESTED` prompt is answered from a dialog off the worker; `close()` unblocks a parked task (no worker left `BLOCKED_ON_INPUT`). *Kill criterion: if `requestInput` cannot be answered off-thread, stop and fix the engine first.*
3. **Block list under load:** feed 50,000 lines across ~2,000 blocks while streaming. Budget: scroll stays smooth, streaming flush never exceeds one frame budget (target <16 ms EDT time per flush), memory bounded.
4. **Docking:** `JDockPane` with 4 panels, drag/resize/persist layout. If it fails, adopt `JTabbedPane` + `JSplitPane` now and say so.

**Phase 0 is done.** Measurements, verdicts and the two engine traps it found are in
[`desktop-gui-phase0.md`](desktop-gui-phase0.md): seam and parking pass (kill criterion satisfied,
`deliver` from the EDT works, `close()` releases the parked worker), 30 s of streaming at 50 000
lines costs at most 26 ms per EDT apply with plain-text cells and 14.8 s with HTML cells, and
`JDockPane` failed on three counts — `setWindowAnchor` is an empty stub, `remove()` throws on the
currently visible window (`JDockTools:140`, `add(null, CENTER)`), and horizontal divider positions
do not stick. **v1 is `JTabbedPane` + `JSplitPane` with an app-owned, serialisable layout model.**

Gate: report with measurements. Then answer §3 items 1-3 from findings.

### Phase 1: chat core (v1)

Host + vocabulary + `NaruSessionModel`, block transcript, input area (multi-line, history, LINE/BLOC indicator replacing `/buffer`, input-disabled-with-reason per E8), status bar (model, project dir, task state, tokens/cost, save state), input dialogs (approval dialogs deferred per §3.3), FlatLaf + `JPlafMenu` + persisted theme.

**E2 lands here** (raw-line `complete(session, rawLine, caret)`; also fixes the `/path` vs `/directive` ambiguity). Until E2 lands, ship no completion rather than a hacked copy of the console's logic.

Gate: a real task (multi-step, tool calls, an approval, a 3-10 minute CPU prefill) runs end-to-end without freezing; terminal REPL regression pass clean.

**Phase 1 is done.** Measurements, the six failure→fix items and the plan deviations are in
[`desktop-gui-phase1.md`](desktop-gui-phase1.md): the chat core ran a real multi-step task — a
directive, tool-using turns, an `ask_user` question answered through the choice buttons, every
parked task resumed — and a 302 s streamed turn (16 666 fragments, 333 KB) produced **zero** EDT
pings over the 16 ms frame budget, with `maxFlushMs=0`, `dropped=0` and worst worker-side publish
132 µs. Two plan assumptions turned out false: `thevpc-common-md`/`nmarkdown` have no HTML writer
(D1, so `AGENT_RESPONSE` is still plain text and `render/MarkdownHtml` waits for Phase 2), and the
input "dialog" is an inline panel rather than a modal (D2). The long-prefill criterion is proven
with a deterministic provider because the local 14B coder refuses to generate for minutes (G4).
Terminal REPL regression: clean, 220 engine tests and 28 desktop tests green.
The three questions it raised are answered in §2: the fake-provider stream is v1's load evidence,
markdown becomes its own Phase 2 change, and the 50 000-line budget becomes a headless module test
as the opening Phase 2 commit.

### Phase 2: tools + tasks (completes v1)

**E1 first** (typed tool-call lifecycle fired from `NaruToolCallStmt`; removes the 300-char truncation). Then: tools panel, task monitor (`tasks()`, `NaruTaskStatus`, `stackFrames()` call tree, `findTaskIdsByParent()` spawn tree, interrupt/kill/hold).

Gate: the v1 acceptance list in §6.

### Phase 3: sessions and observability

**E3** (promote `rawSessionStore()`, `pendingInputs()`, `isHibernateEligible()`, `hasLiveTasks()`, `deliverPendingInput()` to the API) → sessions browser (`NaruSessionStore.list()`, `NaruSessionVersionStore` versions/diff) and pending-input panel. **E4** (`addEventLogListener` + replay-from-cursor) → event-log panel and resync.

### Phase 4: management panels

Permissions editor (`NaruAccessPolicy` CRUD/reorder/`onDecision`; needs no engine work, so schedule it as a cheap early win if there is slack), model picker (`NaruRegistry`, `NaruModelFilter`, `session.listedModels()` for `/model use <n>` parity), routine editor, budget dashboard, skills and plan panels. **E7** (change listeners) only if polling proves inadequate. **E6** (typed git/fs diff API) → diff viewer.

### Phase 5: packaging and lifecycle

ndb-style dispatch, `-c/--console` escape, `@NAppInstall/@NAppUpdate/@NAppUninstall` launchers, `prepareUI(args)` HiDPI, splash, single-instance guard, window-state persistence.

### Phase 6: parity and `TurnApiHost`

Walk all 38 directives in `HELP.md`: rendered / panel-ised / deliberately unchanged. Verify console behaviour against `README.md` and `examples/`. `TurnApiHost` only after **E5** (append idempotency, `INTERRUPT`, durable ordered delivery without clear-on-overflow, hibernate/wake tests). The acceptance test is that the same widget suite passes against both hosts.

## 6. v1 cut line

v1 = Phase 0 + Phase 1 + Phase 2: chat core, tools panel, task monitor, status bar. Everything in Phases 3-6 is explicitly deferred. Do not start deferred work without sign-off.

v1 acceptance:
- Streams a real task for 10+ minutes without EDT stalls over the Phase 0 budget.
- Every `NaruLogMode` has a defined rendering; thinking is collapsed with a summary.
- Tool calls show name, args, duration and full result (no 300-char truncation).
- Input prompts work, and closing the session releases every parked task.
- Permission denials are visible as full untruncated cards, with a jump to the permissions editor (no approval prompt — see §3.3).
- Task monitor shows live parallel tasks with interrupt/kill/hold.
- Terminal REPL unchanged after every engine PR.

## 7. Engine additions: rules

One small PR each, testable without a GUI, with unit tests in `core/naru-impl` and `test/naru-agent-test`, green under `mvn -pl core/naru-api,core/naru-impl test`. Each PR includes a terminal-REPL regression check, since `NaruTerminalInteraction` shares the emitter. Order: E2 (Ph1), E1 (Ph2), E3, E4 (Ph3), E6/E7 (Ph4), E5 (Ph6). E8 is resolved by the v1 default in §3.4. **E9 (approval prompts) is removed from this plan** — it needs engine policy semantics, is a §1 non-goal, and is proposed separately per §3.3. E2 scope grew after §0 check 8: only ~7 of 38 directives override `resolveCandidates` and it is a default returning blank, so E2 must own raw-line parsing *and* a fallback for the rest. E1 should also stamp output with `taskId` (§0 check 2: `NaruOutput` carries none, so block/task attribution is host-guessed until it does).

## 8. Verification

- Headless `NaruSwingInteractionTest` beside `NaruStreamInteractionTest`: no callback blocks, `close()` fails pending input, fragment joins.
- Host contract tests that run against any `NaruHost` implementation (this is what proves the seam).
- Coalescer unit tests (ordering preserved, no loss, bounded memory).
- A UI-level smoke test (e.g. AssertJ-Swing or equivalent) for prompt answering and collapse/expand.
- Performance budget from Phase 0 enforced as a regression test.
- Manual run on a real local model for the long-prefill case.

## 9. Risks (with owners of the mitigation)

- Turn API unfinished → default `InteractionHost`; vocabulary keeps widgets off that surface.
- Block boundaries are heuristic until the engine marks them → isolate the heuristic in one class; add engine boundary markers when convenient.
- Rich-term global state → forbid `richTerm(true)` in desktop sessions.
- `JDockPane` unproven → **resolved against docking**: stub `setWindowAnchor`, crashing `remove()`, non-sticky horizontal dividers. `JTabbedPane` + `JSplitPane`, app-owned layout.
- Markdown in Swing → **resolved**: plain-text live rows, HTML only for closed blocks in the viewport, rendered off the EDT.
- JDK 25 toolchain required on CI and contributor machines → document in the module README.

## 10. How to work

1. Deliver the §0 verification report first.
2. Then Phase 0 only. Report measurements, not impressions.
3. At each gate, list what passed, what failed, what changed in the plan, and what you need decided.
4. Prefer small, reviewable commits. Flag any place where this plan conflicts with the code instead of working around it.
