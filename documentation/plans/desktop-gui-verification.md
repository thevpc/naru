# NARU Desktop — §0 verification report

Answers plan v2 §0. Every claim checked against the code; corrections and consequences
called out rather than absorbed. Companion to
[desktop-gui.md](/home/vpc/xprojects/nuts-world/nuts-productivity/naru/documentation/plans/desktop-gui.md).

Verdict: **8 of 9 claims confirmed** (two stronger than stated), **one partially wrong**
(Maven version). Plus two findings the plan does not yet account for: **approval prompts
do not exist in the engine at all**, and **directive completion coverage is partial**.

---

## Claim-by-claim

### 1. `NaruInteraction` threading + first-answer-wins — CONFIRMED

`core/naru-api/.../agent/NaruInteraction.java:14-18` — "`write` may be called from any worker
thread, including several at once, and must not block. `requestInput` may also arrive on a
worker thread and must not block — the answer comes later, through
`NaruInputRequest.deliver(String)`". Lifecycle bracket `open`/`close` at `:20-24`, and
`NaruInteraction.java:26` documents the hibernate/resume path.

First-answer-wins at `NaruSessionImpl.java:1684` — `if (!answered.compareAndSet(false, true))
return;`. Three further guards the plan should know about: a `stopped` check (`:1687`), an
optional idempotency-key claim (`:1690`), and removal from `pendingInputRequests` (`:1692`).
`NaruInputRequest.java:13-17` states "exactly one terminal call" and "implementations are
expected to be callable from any thread".

**Consequence:** `InteractionHost` answering from the EDT is contractually legal. Phase 0
check 2 is expected to pass.

### 2. `NaruOutput` carries no id/token data — CONFIRMED, and it bites

Fields are exactly `sequence, instant, mode, message` (`NaruOutput.java:32-36`), documented
as plain text with styling left to the terminal (`:20-24`). No `blockId`, no `taskId`, no
token counts.

**Consequence for §4.2:** `TextChunk.blockId` and `taskId` are **host-invented**, not
received. The heuristic in row 2 of the vocabulary table is therefore load-bearing, not
temporary. `taskId` is only recoverable indirectly (there is one interaction per session, but
concurrent tasks interleave output — so a mode/task change heuristic can misattribute). Also:
`UsageUpdated` cannot come from the output stream at all; it must be polled from
`NaruBudgetExtension`. Both are already listed as engine work, but E1 should now explicitly
include "stamp output with taskId", or the transcript keeps guessing.

### 3. Turn API is unusable as a host today — CONFIRMED

`NaruSessionHandleImpl.java:26` — `final class`, package-private (no public constructor or
factory; `NaruSessionHandle.java` is the only public face, exposing just `id/submit/close`).
`NaruTurnEvent.java:4` — `record NaruTurnEvent(long seq, Kind kind, Object payload)`. Delivery
is `ArrayBlockingQueue<NaruTurnEvent> queue = new ArrayBlockingQueue<>(128)`
(`NaruSessionHandleImpl.java:337`), and on overflow: `lagged.set(true); queue.clear()` at
`:356` (and again in the consumer at `:349`). `grep replay` over that file: **zero matches** —
no resynchronisation entry point. `NaruTurnResult` only reports the boolean `lagged`
(`:200,217,266,327`).

**Consequence:** §2 and Phase 6 stand. `InteractionHost` is the only viable v1 backend.

### 4. `enableRichTerm` is process-global — CONFIRMED, STRONGER THAN STATED

The javadoc at `NaruSessionBuilderImpl.java:340-352` says it outright: "**This is
process-global and cannot be made per-session.** Nuts exposes `commandHighlighter` and
`commandAutoCompleteResolver` only on the system terminal singleton … two interactive
sessions in one JVM necessarily share them and the last one configured wins."

Additional: the Ctrl+C interrupt key handler installed there is **also** process-global
(`:363-368`), and `NSystemTerminal.enableRichTerm()` is called unconditionally at `:354`
before the terminal-interaction guard.

**Consequence:** the §9 rule "never route any session through `richTerm(true)`" is correct and
now has a documented source. Worth adding: a desktop session that *also* starts a console
session (e.g. an embedded terminal panel, if ever wanted) inherits this fight.

### 5. `handleSessionCommand` is an empty stub — CONFIRMED

`NaruSessionImpl.java:1796-1799`:
```java
private void handleSessionCommand(String line) {
    ensureNotStopped();
    //
}
```
Reached from `NaruSessionImpl.java:1626`: when there is no foreground task, the delivered line
goes to this stub and `return`s — the input is discarded with no log and no feedback.

**Consequence:** §3.4 (disable the field with a visible reason) is right for v1, and E8 stays
out of v1. The GUI must not rely on `/`-commands working while idle.

### 6. No typed tool-call events — CONFIRMED

No `onToolCall` / `ToolCallListener` / tool-call lifecycle type anywhere in `core/` or
`extensions/` main sources. The only `toolCall` hits are model-side stream parsers
(`NoToolWrapHelper.java:254,278` and its tests) — those decode model output, they do not
notify a host. `NaruSessionListener` has exactly: `onEventAppended`, `sessionStarted`,
`sessionStopped`, `onSessionReloaded`, `onTaskStatusChanged`, `onMessageComplete`.

**Consequence:** E1 stands and is confirmed as the top engine priority.

### 7. Permission/approval prompt — **NOT AS THE PLAN ASSUMES. Approval is not implemented.**

The plan's §3.3 anticipated "maybe it is plain `requestInput` text". It is neither. There is
**no ask-the-user path in existence**:

- `NaruAccessEffect` has exactly two values, `ALLOW, DENY`
  (`core/naru-api/.../access/NaruAccessEffect.java:11-13`); `NaruAccessDecision` has only
  `allow(...)`/`deny(...)` factories (`:33-39`). No ASK state exists.
- The central gate **returns a string** instead of prompting:
  `NaruRegistryImpl.java:444-451` calls `NaruAccessGuard.check(...)` and on denial returns
  `denyMessage(...)` — which builds `"ERROR: permission denied: ..."`
  (`NaruAccessGuard.java:74-94`).
- That string is fed to the **model** as tool output:
  `NaruToolCallStmt.java:76-82` → `registry().dispatch(call, task)` →
  `task.addHistory(NaruMessage.tool(name, id, result))`. The user sees nothing typed as an
  approval; the agent reads an error and moves on.
- Domain guards do the same — `NaruFsGuard.java:51-58`, `RunShellTool.java:66-69`,
  `WebToolHelper.java:125-129`; directives get `NaruStmtResult.ofError`
  (`NaruTaskImpl.java:2311-2315`). Denied tools are not even prompted on — they are hidden
  from the model's tool list (`NaruTaskImpl.java:1378`).
- `requestInput` has **exactly one production caller**: `NaruReadlineStmt.java:34`, the REPL
  prompt. Zero access-policy callers.
- `/permission` is pure CRUD + evaluation
  (`NaruPermissionsDirectiveProvider.java:55-120`, `:272`); `PermissionCheckTool.java:60-75`
  is read-only. `NaruPermissionRule` has no ask/lazy field
  (`:41-52`, effect defaults to ALLOW at `:68`).
- Dead ends confirming intent-but-unbuilt: `NaruPlanValidatorKind.USER_APPROVAL`
  (`:24-29`) documents that it *would* use `NaruTask.requestInput` but nothing executes it;
  the `ASK`/`REVIEW` prompt modes in `NaruStandardPromptModeImpl.java:19-63` are commented
  out; `ModeIntent` is GENERAL/PLANNING/EXECUTING — visibility only
  (`NaruPromptMode.java:37-53`); config keys are only `naru.perm.enabled`,
  `naru.perm.defaultEffect`, `naru.perm.builtins`
  (`NaruPermConfigKeysProvider.java:23-29`). No `y/n`, `allow-once`, `askUser` anywhere.

**This is a plan conflict, not a detail.** §6 lists "Approval and input prompts work" as a **v1
acceptance criterion**, and §7 schedules E9 only "if §3.3 requires it". It does. So either E9
enters Phase 2, or approval drops out of v1 acceptance. E9 is genuinely additive engine work:
a tri-state effect/decision, a typed approval request raised at the three gates, a
`BLOCKED_ON_APPROVAL`-style park (or reuse of `BLOCKED_ON_INPUT`), a respond API beyond free
text (allow-once / always-with-lazily-minted rule / deny), and a fail-closed default when no
UI answers. That is a real engine PR with policy semantics — the kind of change that alters
agent behaviour, which §1 declares a non-goal.

**Recommendation:** take approval out of v1 (ship E1-based read-only denial cards, plus the
permissions editor from Phase 4 pulled forward as the cheap win it is — it needs no engine
work), and open E9 as its own proposal with the owner, separately from the GUI track. Say the
word and I will edit §3.3, §6 and §7 to match.

### 8. Directive completion is terminal-bound — CONFIRMED, plus a coverage problem

`NaruDirective.java:42-46`:
```java
default NArgCompleteResult resolveCandidates(NCmdLine cmdLine,
                                             NArgCompletePosition pos,
                                             NaruSession session) {
    return NArgCompleteResult.ofBlank();
}
```
It takes an already-parsed `NCmdLine`, and it is a **default that returns blank** — so callers
must construct a terminal-flavoured parse just to ask a question, and directives that never
override simply offer nothing. Only about 7 main-source directives override it
(`/model`, `/tags`, `/mode`, `/settings`, `/skill`, `/help`, plus the resolver itself), against
**38 directives** listed in `HELP.md`.

**Consequence:** E2 is not just a signature wrapper. It must (a) own raw-line→token parsing
independent of `NCmdLine`, (b) resolve the `/path` vs `/directive` ambiguity, and (c) define a
fallback for the ~31 directives with no argument completion, or the GUI's autocomplete looks
broken on most commands. Phase 1's "ship no completion rather than a hacked copy" still
applies, but budget E2 larger than a one-liner.

### 9. Toolchain — MOSTLY CONFIRMED, one correction

- JDK `openjdk 25.0.4 2026-07-21` (SUSE build) — present.
- `~/.m2/toolchains.xml` has a `<version>25</version>` →
  `/usr/lib64/jvm/java-25-openjdk-25` entry, matching `app/naru/pom.xml:125-131`'s pin
  (jdk 25 / openjdk). Correct.
- **Maven is `3.10.0-rc-1`, not `3.10.0`.** Cosmetic, but the plan states a release version
  that is not what is installed.
- Artifacts in `~/.m2`: `thevpc-common-swing` **both 2.4.0 and 2.5.0** (2.5.0 has a
  `-sources.jar` — readable for spiking), `thevpc-common-plaf/1.1.0`,
  `thevpc-common-swing-prs/2.5.0` (needed for kifkif-style theme/icon hot-swap), FlatLaf up to
  **3.7.1**, `nuts-swing/1.1.0.0`, `thevpc-common-md/1.2.0`, `nmarkdown`.
- Build entry: root `pom.xml` modules are `core/naru-api`, `core/naru-impl`, `extensions`,
  `profiles`, `test`, `app/naru`; Java 25 source/target at `pom.xml:13-14`.

**Consequence:** §2's toolkit line should add `thevpc-common-swing-prs:2.5.0` if theme hot
swap is wanted, and §9's JDK note can name the exact toolchain version.

---

## Bonus: §3.2 answered — rebind-in-place IS supported

Better news than the plan assumed. `NaruSession.load(String otherUuid)`
(`NaruSessionImpl.java:1515-1527`) does `stopTheWorldAndWait { loadFromStore(uuid, scope);
scheduler.reloadState() }`, then fires `onSessionReloaded` to every session listener. There is
also `NaruSession.restoreFromStore()` (`NaruSession.java:114`), and the builder can bind an
existing store or stable id: `NaruSessionBuilder.store(NaruSessionStore)` (`:79`) and
`.id(String)` (`:75`). Version history is public:
`NaruSessionVersionStore.list()/head()/restore(seqOrLabel, Sink)/diff(from,to)`
(`:25,41,55,57`).

**Consequence:** the sessions browser in Phase 3 can rebind a live session in place and get a
`onSessionReloaded` callback to rebuild its transcript — no create-new-with-copied-history
compromise. §3.2 default resolves to "rebind-in-place".

## Bonus: `NaruAccessPolicy` is the best-ready panel surface

Full CRUD plus ordering plus live notification: `rules()`, `rule(id)`,
`findRulesByPattern(glob)`, `putRule`, `removeRule(id)`, `removeRules(source)`, `reorder`,
`moveRuleBefore/After`, `defaultEffect()`, `check`, `explain`, and
`addListener(NaruAccessPolicyListener)` (`NaruAccessPolicy.java:84,112`) with
`onRuleAdded/Removed/Updated`, `onRulesReordered`, `onDefaultEffectChanged`, `onDecision`.
Confirms Phase 4's "cheap early win" is genuinely cheap.

---

## What this changes in the plan

1. **§6 v1 acceptance must drop or defer "approval prompts work"** (claim 7). Needs a decision.
2. **E2 scope grows**: raw-line parsing + 31 directives without completion (claim 8).
3. **`TextChunk.taskId/blockId` are host-derived for all of v1**; E1 should add task stamping
   to output or the transcript heuristic stays permanent (claim 2).
4. **§3.2 resolved**: rebind-in-place available (bonus).
5. **§2 toolkit line**: add `thevpc-common-swing-prs` (claim 9).
6. Everything else — the seam, the queue-drop hazard, the global rich-term, the idle-input
   stub, the missing tool events — holds as written.

## Not verified (needs execution, belongs to Phase 0)

Nothing here was run. The claims verified are static facts. Phase 0's four measurements —
EDT stall under a 60 s call, parking/release behaviour, 50k-line block-list smoothness,
`JDockPane` viability — are the first empirical evidence, and claims 1/3/5 say all four are
likely to pass on the `InteractionHost` path with `JDockPane` the main risk.
