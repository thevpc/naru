# Findings: session storage (step 0)

Read before any change. Facts, with the code that establishes each one.

## 1. `.naru/local/sessions/snapshot.tson` — NOT a pointer, and nothing reads it

It is a **whole older-format session state document**, not an index. Shape (2.7K in this
repo): `uuid`, `name`, `creationDate`, `modificationDate`, `model`, `projectDir`,
`workingDir`, and a `tasks` array holding **full inline task elements** (including
`history`, `frames` inline). Its `uuid` is `6442acf3-…`, different from
`.naru/local/sessions/snapshot/session.tson`'s `6c602730-…`.

No code reads or writes it. `grep -rn "snapshot.tson"` matches nothing. The loader
`NaruSessionImpl.loadFolder` only ever takes a *folder* and resolves `session.tson`
inside it; a bare `snapshot.tson` file is not a folder and is unreachable from there.

**Consequence for the brief:** the brief assumed it was "a pointer or index to the current
snapshot" and left the handling open ("fold into session.tson / replace by a pointer /
delete if redundant"). It is a *complete but self-contained* session state for a uuid that
is not otherwise present on disk. So it is redundant *as an index* but not redundant as
*data*: it is one more real session that would otherwise be lost. I fold it into the new
layout as the session it names, and keep the original in `local/sessions/.legacy/`.

## 2. `.naru/local/sessions/snapshot/` — also legacy, not the current snapshot path

The brief says the live snapshot is at `local/sessions/snapshot/`. It is not. Current
code writes scratch state to `.naru/local/snapshot/<uuid>/session.tson`
(`NaruSessionImpl.java:969` `snapshotFile()`). `local/sessions/snapshot/` is the *previous*
generation of that path, left on disk. It holds `session.tson` (metadata + `env`), an empty
`routines/`, and `tasks/1.tson` (one full task, inline `history` + `env` + `frames`).

The only code that mentions it is `NaruSessionStoreManagerImpl.NonSnapshotSessionFolder`
(:148), a filter whose whole job is to hide `snapshot` from `/session list`. So
`snapshot/` is genuinely dead data today, which is why the migration in section 1.1 is
worth doing: it is a real session that the current code cannot load.

**Path builders under `snapshot/` (all of them, per `grep -ri snapshot`):**
- `NaruSessionImpl.snapshotFile()` :969 — `.naru/local/snapshot/<uuid>/session.tson`
- `NaruSessionImpl.fireChangedTask(long)` :1612 — `<that folder>/tasks`, wipes all `*.tson`
  then writes one. Only safe because that folder is scratch owned by one session.
- `NaruSessionImpl.saveFolder(NPath)` :782-798 — `<folder>/session.tson`,
  `<folder>/routines/<name>.tson` (wiped first), `<folder>/tasks/<id>.tson` (wiped first)
- `NaruSessionImpl.saveSessionExtensions` :807-818 — `<folder>/ext/<extension>.tson`
- `NaruSessionImpl.loadFolder(NPath)` :575-625 — reads the same four shapes back
- `NaruSessionStoreManagerImpl.sessionDir/sessionFile` :82-138 — `.naru/sessions/<uuid>` and
  `.naru/local/sessions/<uuid>`

## 3. Audit: two writers' worth of history, one live writer

`.naru/local/logs/task-1-llm-audit.tson` is **not written by any live code**. The path is
present only as a commented-out line, `NaruModelUtils.java:201`. It is a leftover from an
older layout; the committed file is its output. Left alone, as the brief requires.

The live writer is `NaruModelUtils.logAudit` (:232), called from the `finally` of every
provider call attempt (`NaruModelProtocolBase.java:363` non-streaming, `:523` streaming),
so **one record per attempt**, including every retry and every failure.

Semantics of `.naru/local/sessions/<uuid>/audit/task-<id>.tson`:
- **Append-only.** `NPathOption.APPEND`, under a `synchronized (NaruModelUtils.class)`.
  Never rewritten, never truncated. Not per-call overwrite.
- **Delimited by a blank line:** `NElementWriter.ofTson().formatPlain(record) + "\n\n"`.
  Records are single-line compact TSON, so `"\n\n"` is an unambiguous separator.
- **Record shape** (`LinkedHashMap`, so key order is stable): `id` (UUID), `timestamp`
  (ISO instant), `taskId`, `taskName`?, `sessionUuid`, `provider`, `model`, `url`,
  `method`, `requestHeaders` (values masked by `maskHeaderValue`), `requestBody`,
  `attempt`, `durationMs`, `statusCode`, `statusMessage`, `responseHeaders`,
  `responseBody`, `error{type,message}`.
- Two override paths, both of which must survive: env `audit.dir` / `llm.audit.dir`
  replaces the location entirely, and with no session and no projectDir it falls back to
  the Nuts workspace log store.

**This is the quadratic term section 5 attacks:** `requestBody.messages` is the whole
conversation, re-serialized in full on every call. The committed 62-line file confirms it
— every record opens with the same two long system prompts.

## 4. When state is persisted today

One funnel: `NaruSessionImpl.saveFolder(NPath)` (:782) is the only writer of session state.
Two callers:

- `saveSnapshot()` (:752) — `stopTheWorldAndDo`, i.e. **fire and forget, posts onto the
  agent loop**. Called by `fireChanged()` (:569).
- `save()` (:762) — `stopTheWorldAndWait`, parking the current tick first. Writes to the
  visibility-chosen folder and **deletes the other one**.

`fireChanged()` has 36 call sites. The load-bearing ones:

- `NaruTaskImpl._exeRollableStmt` :1735 and :1740 — `fireChanged()` **before and after
  every statement**. So one turn is ≥2 full rewrites of all task files.
- `NaruTaskImpl.pc(int)` :1384 — every frame step.
- `NaruTaskImpl.addHistory(NaruMessage)` :1595, `setLastResult` :1599, statement pushes
  :1602-1607, pending incremental statement :1724, mode/input-mode :947/:961, status :494.
- `NaruPromptStmt` :82 and :107 add their own on top of the statement brackets.

`fireChangedTask(long)` :1609 is the per-task fast path: delete all `*.tson` in the
scratch `tasks/`, write exactly one. Correct only because that folder is scratch. This is
the hot path the new per-message item files replace.

## 5. How `history` is held and mutated

`NaruTaskImpl.history` is `List<NaruMessage>`, plain `ArrayList`, no ids, and
`NaruMessage` has no id field. Direct mutations:

- :849-852 `load(NElement)` — clear then repopulate (deserialization)
- :768 `reset()` — `clear()`
- :1365 `removeHistoryAt(int)` — `remove(index)`
- :1392 `clearHistory()` — `clear()`
- :1427 `trimHistory(int)` — `subList(0, size-count).clear()`
- :1593 `addHistory(NaruMessage)` — `add(...)`; system-role goes to `systemHistory` and
  still fires `fireChanged()` unconditionally

Indirect, via the `NaruTask` API (`NaruTask.java:261,267,273,310,312,314`), ~20 call sites:
`NaruTaskImpl` :1833/:2677/:2681, `NaruSessionImpl` :285, `NaruPromptStmt` :82/:107,
`NaruToolCallStmt` :61/:80, `NaruHistoryDirective` (clear/drop/trim),
`NaruModeDirective`/`NaruToolsDirective`, `NaruSkillDirective`,
`NaruFileDirective`/`NaruCatDirective`/`NaruLsDirective`/`NaruPwdDirective`,
`NaruSystemDirective`.

So a user can insert, delete, replace and truncate **at any index**. Any design that gives
messages stable ids in the caller is wrong; ids must be the store's, reconciled by content.

## 6. Conflicts with the brief, and the assumptions I took

1. **`snapshot.tson` is a full session, not a pointer** (§1). Assumption: migrate it as the
   session it names, keep the original under `.legacy/`.
2. **The live snapshot is at `local/snapshot/<uuid>/`, not `local/sessions/snapshot/`**
   (§2). Both are legacy relative to the target layout; the brief's target is
   `<uuid>/` in one of the two roots, so this only changes what migration has to read.
3. **The brief says "no snapshot concept in code, naming, or docs"**, but `NaruSession`
   exposes `saveSnapshot()` and `restoreSnapshot()` as public API
   (`NaruSession.java:64,72`), and `/session restore` and `/session reload` help text
   reference them. Renaming those is a public API break. I rename the *implementation*
   (methods, fields, log messages, test names) and keep the two interface methods as thin
   deprecated delegates, so `grep -ri snapshot` in main code only matches those delegates
   and the legacy migration. Flip: tell me to break the API and I will rename them
   outright.
4. **`NaruVisibility` already exists** with `PUBLIC|PRIVATE|MIXED` and is used across the
   env API (`NaruProjectEnv.put`, `NaruSession.setVisibility`). The brief asks for a new
   `NaruSessionScope {PUBLIC, PRIVATE}`. Assumption: add `NaruSessionScope` as the
   store-level type (it is what a store can act on) and keep `NaruVisibility` for the
   session/env surface, mapping `MIXED` → `PRIVATE` at the store boundary. One source of
   truth for *storage location* remains location itself; no scope is persisted.
5. **Build note:** the reactor needs `JAVA_HOME=/usr/lib64/jvm/java-17-openjdk-17` and
   `-Dnuts.args=-y` (or `nuts-dev -y`), otherwise `Nuts.openWorkspace()` in tests blocks
   forever on an interactive "import older config" prompt.

## 7. Pre-existing test failures (baseline, before my changes)

Recorded so they are not mistaken for regressions. All are in the current tree:

- `TaskSpawnConfigTest` — 4 errors, `missing mode 'PLANNING'` / `'IMPLEMENT'`
- `NaruSessionStoreManagerTest.aRunningSessionsSnapshotIsNotMistakenForASavedSession` —
  asserts `.naru/local/snapshot/<uuid>` exists right after `saveSnapshot()`, which is
  async; the assertion races. Directly about the code this change replaces.
- `NaruTerminalStreamOutputTest.anEmptyStreamDrawsNothing` — stray `▌`
- `NaruSessionDirectiveTest` — 3 × 60s timeouts (`save`/`restore`/`list` from inside a
  task)
