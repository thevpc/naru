# Project concept — Phase 0 discovery

Discovery pass for the "introduce the Project concept" plan. Facts first, each with the
code that establishes it; deviations from the plan document at the end, with the decision
taken for each.

Conventions this document follows: `Naru*` type names, Nuts SPI
(`META-INF/services/net.thevpc.nuts.spi.NComponent`), TSON persistence via
`net.thevpc.nuts.elem`, exceptions rooted in Nuts exception classes, JUnit 5 tests beside
each module.

## 1. Real names and places

### 1.1 Session store SPI — `core/naru-api`, package `net.thevpc.naru.api.store`

- `NaruSessionStore` (interface, `extends AutoCloseable`) — the central SPI. Catalog:
  `List<NaruSessionRef> list(NaruSessionScope)` / `list()`; metadata
  `create / createIfAbsent / loadData / saveData / compareAndSetData(data, scope,
  expectedVersion)`; tasks `saveTask / saveTaskSkeleton / loadTask / loadTaskSkeleton /
  taskIds / deleteTask`; history `saveHistory / loadHistory`; routines and extension
  state; lifecycle `move(uuid, from, to)`, `delete(uuid, scope)`, `purge(scope)`;
  `versions(uuid, scope)` and `audit(uuid, scope)` sub-stores.
- **There is no `findByQuery` and no `NaruSessionSummary`.** The catalog type is
  `NaruSessionRef` (uuid, name, scope, creation/modification instants, taskCount,
  historyCount) — deliberately lighter than `NaruSessionData`.
- Version tokens: `NaruSessionData.version` (long, "absent legacy values interpreted as
  zero"), bumped only through `compareAndSetData` (CAS; loser gets
  `NaruSessionConflictException(uuid, expected, actual)` — extends
  `NIllegalStateException`).
- Keyset pagination exists at **message level only**: `NaruMessageQuery(cursor, direction,
  limit, roles, createdFrom, createdTo)` / `NaruMessagePage(items, nextCursor,
  previousCursor, hasMore)`, cursor = last item id, "opaque to callers". There is **no
  generic `NaruPage<T>`**; one will be added.
- Scope: `NaruSessionScope { PUBLIC, PRIVATE }`; the scope **is** the location
  (`.naru/sessions/<uuid>` vs `.naru/local/sessions/<uuid>`), never persisted as a field.
- Store factory SPI: `NaruSessionStoreFactory { name(); open(NaruStoreConfig);
  isDefault(); }` discovered via `NServiceLoader`; engine entry `NaruStoreFactory.open(...)`.
- `NaruStoreConfig { NPath projectDir; NPath storeDir; NaruSessionScope defaultScope;
  Map<String,String> properties; }`; `NaruStoreConfigs.forProject(dir, env)` (naru-impl)
  derives `storeDir = dir/.naru`, default scope from env key `session.defaultScope`,
  store name from sysprop `naru.session.store` / env key `session.store`.

### 1.2 File store — `extensions/naru-store-file`, package `net.thevpc.naru.impl.store`

- `NaruFileSessionStore` implements the SPI. Layout
  `<storeDir>/{sessions,local/sessions}/<uuid>/{session.tson,
  tasks/<id>/{task.tson,history/<n>.tson,next-id}, routines/, ext/, .versions/, audit/}`.
- `NaruFileIo` — the atomic-write utility: sibling temp `.<name>.tmp-<uuid>`, `FileChannel`
  `force(true)` (fsync), `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` with fallback, `syncDir`
  afterwards. **Reuse for the project store.**
- Metadata CAS is serialized by `withMetadataLock` (static synchronized + cross-process
  `FileChannel.lock()` on `<sessionDir>/.metadata.lock`).
- Corrupt `session.tson` → `describe()` returns null → the row is skipped in `list()`
  ("one unreadable session must not make /session list unusable").
- Task/message layering: `NaruTaskStore` / `NaruMessageStore` default adapters built on the
  flat session methods; `NaruFileSessionVersionStore`, `NaruContentAddressedStore`,
  `NaruFileAuditStore` per session. JDBC variant: `extensions/naru-store-jdbc`.

### 1.3 Session model and store root (important)

- `NaruSession` (`net.thevpc.naru.api.agent`): anchor directory is **already `sessionDir()`
  / `sessionDir(NPath)`** (WP7 rename — see deviation X1). `workingDir()`, `uuid()`,
  `name()`, `creationInstant()/modificationInstant()`, `user()` → **`NaruPrincipal`**
  (access-control owner, defaults to OS user; **not** the plan's opaque string — X5),
  `sessionStoreManager()`, `versionStore()`, `save()/persist()`, `start()/waitFor()/run()`.
- `session.tson` header keys (`NaruSessionData.toElement`): `uuid, name, creationDate,
  modificationDate, model, sessionDir, workingDir, version, env` — **no `projectDir` key**
  (deviation X1). Task skeletons (`task.tson`) *do* carry a `projectDir` key, and
  `NaruTask.projectDir()`/`setProjectDir()` is the per-task view of the **same** directory
  the session anchors to (seeded from `session.sessionDir()` at spawn; re-keyed when
  `sessionDir(...)` changes).
- **Store root is derived from the session's `sessionDir` at construction**
  (`NaruSessionImpl.storeConfig()` → `NaruStoreConfigs.forProject(sessionDir, env)`).
  The store handle is final; `sessionDir(NPath)` later does not migrate storage.
  `NaruSessionStoreManagerImpl` likewise roots `list()/purge()/delete()` at
  `adapter.sessionDir()/.naru/...`. This collides with the plan's glossary rule "never
  use `sessionDir` to build storage paths" and would scatter one tree across many stores
  — see decision X8.
- Live sessions: `NaruSessionImpl` *is* the in-memory state (`storeVersion` is its CAS
  token; `fireChanged()` → `persist()` async; `save()` stop-the-world). `writeStateInPlace`
  CASes `session.tson` on every checkpoint. `NaruWorkspace.sessions()` is the live view
  (sessions register on start via the workspace's `asSessionListener`);
  `NaruSessionHandleImpl` keeps a private static `Map<String, Live> LIVE` keyed by session
  uuid. Editing metadata of a live session must go through the live object
  (`NaruSessionStoreManagerImpl.rename/setVisibility` are the existing examples).
  `NaruSession` also has `isRunning()`, `hasLiveTasks()`, `pendingInputs()` for liveness.

### 1.4 Agent / workspace

- There is **no `NaruAgent` class**: the runtime type is `NaruWorkspace`
  (`net.thevpc.naru.api.agent`), implementation `NaruWorkspaceImpl`;
  `NaruWorkspace.of()` / `NaruWorkspaceBuilder.of()...build()` are the entry points.
  Only one implementation exists, so `NaruWorkspace` can grow a `projects()` method.
- Embedding shape: `workspace.newSession().statements(...).interactive().id(...).build()`,
  then `session.start().waitFor()` (see `NaruCmdLineProcessor`), or
  `agent.newSession().task(spec).build().run()`.
- `NaruWorkspace.projectDirectory()` / `projectDirectory(NPath)` **means more than seeding
  the session directory**: it (re)builds the registration store and `NaruProjectEnv` at
  `<dir>/.naru/{config,local/config}/*.tson`, anchors `dataStore(namespace)`, the lazy
  `sessionCatalog()`, and is the default `sessionDir` for new sessions
  (`defaultSessionDir()`: projectDirectory else user home). → per plan A4: **leave, report**
  (X2).
- `NaruSessionBuilder` (api) / `NaruSessionBuilderImpl`: setters include
  `directory(NPath sessionDir)` (X3 rename target), `user(NaruPrincipal)`,
  `userActor(NaruActor)`, `id(String)`, `store(...)`, `scope(...)`, `interaction(...)`,
  `task(...)`, `configureDefaults(boolean)`, filters, `build()` / `buildHandle()`.
- `NaruInteraction` = the output/input seam (`write/writeStream/requestInput/
  reportToolCall`, task-attributed overloads); terminal + stream implementations exist.
- CLI entry: `core/naru-impl/.../cmdline/NaruCmdLineProcessor` (used by `app/naru`'s
  `NaruApp`); flags `-d|--project <dir>` (workspace root!), `--session <uuid>`, `-t/-f`,
  `-e`, `--help`.

### 1.5 Directives

- Interface `NaruDirective { name(); group(); getAliases(); getDescription();
  execute(NaruDirectiveCallContext); resolveCandidates(...) }`; base class
  **`NaruDirectiveBase`** with `register(SubCommand)`, `noCommand(String)`,
  `AbstractSubCommand`, generated help, `NaruDirectiveCallContext { name(); argument();
  task(); }`.
- Providers: `NaruDirectiveProvider extends NComponent`, registered through
  `META-INF/services/net.thevpc.nuts.spi.NComponent`, loaded by `NaruRegistryImpl`
  (`NServiceLoader`); `NaruRegistry.registerDirective(...)`; builtin provider in naru-impl
  (`sessiondir`, `go`, `help`, ...). Sessions extension: `extensions/naru-sessions`,
  provider `NaruSessionsDirectiveProvider` ("sessions"), live directive `/session` with
  subcommands (`list, rename, public, private, delete, load, save, new, reset, copy,
  current, restore, reload`); `/session new` **resets the current session**, it does not
  create a separate one.
- Argument parsing: `net.thevpc.nuts.cmdline.NCmdLine` — bash-style
  `cmdLine.matcher().when("--opt").asEntry(a -> ...).whenNonOption().asArg(...).requireAll()`;
  `--opt=value` binds during parse, `--opt value` via helper.
- Output styling: semantic `NMsg` (`ofC`, `ofStyledString/Primary1..5/Keyword/Pale/
  Error/Path...`), never raw ANSI; `NaruStmtResult.ofSuccess/ofError`.
- **No `/project` directive exists.** The directory-changing directive is already
  `/sessiondir` (`NaruSessionDirDirective`, builtin) — the plan's "name hazard" is already
  resolved (X4).
- Extension wiring: modules listed in `extensions/pom.xml`; runtime availability comes from
  the Nuts profile `profiles/naru-profile-dev/pom.xml` (and `test/naru-agent-test/pom.xml`),
  **not** `app/naru/pom.xml` (which deliberately lists no extensions — resolved via the
  profile at runtime).

### 1.6 TSON I/O, exceptions, tests, docs, build

- TSON: `NElementReader.ofTson().ntf(false).read(...)`,
  `NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
  .formatPlain(...)` (see `NaruFileSessionStore.pretty`, synchronized for a shared
  format-cache reason).
- No NARU exception base class; domain exceptions extend Nuts ones
  (`NIllegalStateException`, `NIllegalArgumentException`). Follow `NaruSessionConflictException`.
- Tests: JUnit 5 (5.10.0; `test/naru-agent-test` pins 5.8.2+mockito, source 11). Directive
  tests live in the owning extension module (`NaruSessionDirectiveTest` pattern: temp
  folder project, `NaruWorkspace.of()`, `configureDefaults(false)`,
  `NaruStreamInteraction`, register providers offline, execute directives through
  `NaruDirectiveCallContextImpl`). Build: `mvn verify` (toolchain JDK 25 openjdk, present).
  `-Dnuts.args=-y` avoids the interactive workspace-config prompt.
- `documentation/` = `architecture/`, `user-guide/content/`, `plans/`, `done/`, `todo/`;
  root `HELP.md` is generated (`scripts/generate-help.sh`).
- Baseline compile of the current tree: green. Known baseline test failures before any
  change are listed in `FINDINGS-session-storage.md` §7.

## 2. `projectDir` / `projectDirectory` usage catalog and disposition

| Identifier / place | Meaning | Disposition |
|---|---|---|
| `NaruSession.sessionDir()` / `sessionDir(NPath)`; `session.tson` key `sessionDir` | session anchor dir | **already renamed** (X1) — leave |
| `NaruSessionBuilder.directory(NPath)` | seeds session anchor dir | rename → `sessionDir(NPath)` (Phase 1) |
| builtin `/sessiondir` | changes session anchor dir | leave (X4) |
| `NaruTask.projectDir()` / `setProjectDir()`, `task.tson` key `projectDir`, field in `NaruTaskImpl`, `_setProjectDir` | per-task view of the session anchor dir (seeded from `session.sessionDir()`, follows its changes) | rename → `sessionDir()` / `setSessionDir()` / key `sessionDir` (Phase 1) — same concept as the glossary's *sessionDir* |
| `NaruSpawnTargets` (uses `task.projectDir()` for `.naru/agent` skill roots) | anchor dir | rename call sites (Phase 1) |
| consumers: naru-fs (`SetWorkingDirTool`, `GetWorkingDirTool`, `FileWriteTool`, `NaruFsGuard`, `FileToolHelper`, `NaruCdDirective`), naru-shell (`RunShellTool`, `NaruSystemDirective`), naru-index (`ProjectMapTool`, `ProjectSummaryTool`, `FindSymbolTool`, `CodeSymbolsTool`), naru-git (`GitHelper`), naru-skills (`NaruSkillManager(Impl)`, `NaruSkillTrustStore`, `NaruSkillRootKind`, `NaruSkillDirective`), naru-budget, naru-coder-java (Maven tools), naru-semantic, naru-permissions, `NaruAccessPolicyImpl`, `NaruPoisonTask`, tests incl. desktop `WorkbenchPane`/`InspectorPane` | task anchor dir | mechanical rename (Phase 1) |
| `NaruWorkspace.projectDirectory()` / setter; `NaruWorkspaceBuilder.projectDirectory(NPath)` | workspace root: registrations, project env, data stores, catalog, default sessionDir | **keep** (plan A4: means more than seeding) — X2 |
| `NaruStoreConfig.projectDir`, `NaruDataStoreConfig.projectDir`, `NaruStoreConfigs.forProject` | store root's "home project" | keep name (workspace root concept); Phase 4 re-anchors its *value* (X8) |
| `NaruCmdLineProcessor.projectDir` / CLI `-d|--project <dir>` | workspace root | flag `-d` semantics unchanged; long-name collision handled in Phase 6 — X7 |
| docs: `HELP.md` L599 `/sessiondir : change the project directory`; `documentation/user-guide/content/sessions.md` (`agent.projectDirectory`); plans docs | prose | update wording where the rename touches it (Phases 1/8) |
| `test/bench/**` captured payloads mentioning "project dir" | recorded model/tool schema data | leave (test data, not identifiers) |
| `examples/*.naru`, repo scripts | — | **no `projectDir` hits** — nothing to do |

## 3. Assumption answers (A1–A6)

- **A1** — PARTLY TRUE. `session.tson` contains `uuid, name, creationDate, modificationDate,
  model, sessionDir, workingDir, version, env`. The `projectDir`→`sessionDir` rename the
  plan books as Phase 1 **already happened** at session level (WP7): key and API are
  `sessionDir`. `projectDir` survives at **task** level (`NaruTask.projectDir()`,
  `task.tson` key) where it is the same anchor value. Phase 1 therefore renames the task
  identifier + serialization key and the builder setter, and adds a round-trip test proving
  the `sessionDir` key.
- **A2** — TRUE for the store SPI + task layering + version tokens
  (`NaruSessionData.version`, `compareAndSetData`, `NaruSessionConflictException`).
  **`findByQuery` for sessions does not exist** (catalog is `list()` returning full sorted
  `NaruSessionRef`s; only *messages* have keyset paging). → Phase 4 implements the minimum
  per the plan (filters `projectFilter`/`user`, sort `UPDATED_DESC`, keyset paging with an
  opaque `(sortKey,id)` cursor) and a generic `NaruPage<T>`, as default methods on
  `NaruSessionStore` overridden efficiently by the file store. The JDBC store inherits the
  defaults.
- **A3** — TRUE. `.naru/sessions/` public, `.naru/local/…` private; scope = location, never
  persisted; default scope PRIVATE via env `session.defaultScope`.
- **A4** — TRUE IN SPIRIT, RENAMED. There is no `NaruAgent`; the shape is
  `NaruWorkspace.of()`/`NaruWorkspaceBuilder...newSession()...build()` + `start().waitFor()`.
  `NaruSessionBuilder` exists. Agent-level `projectDirectory(...)` **does more than seed the
  session directory** (registrations, project env, data store roots, session catalog) → per
  plan: leave it, rename nothing there. The builder's session-level setter is
  `directory(NPath)` and gets renamed to `sessionDir(NPath)`.
- **A5** — TRUE. Directives are registered through the `NComponent` SPI from per-extension
  providers; `naru-sessions` exists with `/session` (subcommands) and five fully-commented
  standalone directives. No `/project` directive exists yet; the directory-switching
  directive is already `/sessiondir`, so the "name hazard" is pre-resolved. New extension
  module per plan: yes (directives are per-extension); named **`naru-projects`** (repo
  convention is `naru-<topic>`, not `naru-tools-<topic>`) — wired in `extensions/pom.xml`
  + `profiles/naru-profile-dev/pom.xml` + `test/naru-agent-test/pom.xml` (not `app/naru`,
  which resolves extensions via the profile).
- **A6** — TRUE. `NaruSessionImpl` holds state in memory and persists through
  `compareAndSetData` with its own `storeVersion`; any out-of-band rewrite of
  `session.tson` would break its CAS. Live-session moves therefore go through the live
  object (setter + `save()`), as the plan requires. `NaruWorkspace.sessions()` +
  `NaruSessionHandleImpl.LIVE` are the live lookups; CASCADE liveness check uses
  `isRunning()`/`hasLiveTasks()`/`pendingInputs()`.

## 4. Deviations from the plan document and decisions taken

- **X1 — Phase 1 scope.** The session-level rename is already done in the tree. Decision:
  Phase 1 renames the remaining same-concept identifiers: `NaruTask.projectDir/setProjectDir`
  (+ `task.tson` key), `NaruSessionBuilder.directory(...)` → `sessionDir(...)`; acceptance
  test proves `session.tson` round-trips with `sessionDir`.
- **X2 — `NaruWorkspace.projectDirectory` kept** (means the workspace root; per plan A4's
  second branch). Same for `NaruStoreConfig.projectDir` (store config keeps the name).
- **X3 — builder setter** is `directory(NPath)` today (not `projectDirectory`); renamed to
  `sessionDir(NPath)`.
- **X4 — `/sessiondir` already exists**; `/project` is free for the entity. No hazard fix
  needed.
- **X5 — `user` naming.** `NaruSession.user()` already exists returning `NaruPrincipal`
  (access-control identity, never persisted to `session.tson`), and
  `NaruSessionBuilder.user(NaruPrincipal)` too. The plan's opaque string user is a distinct
  concept. Decision: store field + `session.tson` key + summary field stay `user`; on
  `NaruSession` the string is exposed as `userName()` getter with a `user(String)` setter
  overload kept next to `user(NaruPrincipal)`; documented clearly to prevent conflation.
- **X6 — project file store location.** The plan says the default file store lives in
  `naru-impl`; convention here puts file-store implementations in
  `extensions/naru-store-file` (package `net.thevpc.naru.impl.store`), opened through the
  factory SPI so JDBC-style backends can replace it. Decision: `NaruFileProjectStore` in
  `naru-store-file`, opened via a new `NaruProjectStoreFactory` SPI mirroring
  `NaruSessionStoreFactory`; root `<storeDir>/local/projects` (default `.naru/local/projects`,
  private by default), overridable like the session store.
- **X7 — CLI `--project` collision.** `naru -d|--project <dir>` already means "workspace
  root". Decision: the app entrypoint keeps `-d` and gains the long name `--directory` for
  that meaning; `--project=<id|name>` (the entity, per the plan's syntax) plus `--user=<u>`
  are added for session creation at the entrypoint. Clean cut, no alias for the old long
  name.
- **X8 — store-root contradiction with the glossary (structural).** Today a session's
  store is derived from its own `sessionDir` at construction, which (a) violates the plan's
  glossary rule "never use `sessionDir` to build storage paths" and (b) scatters sessions
  across stores whenever projects have different folders, breaking the single-store tree,
  `countByProject`, and `findByQuery`. Decision: in Phase 4, anchor the session store at
  the **workspace root** (`projectDirectory` → `.naru`, falling back to the session dir only
  when the workspace has none). In normal use (CLI, desktop, tests) `sessionDir` already
  equals the workspace root, so existing folders do not move; only the exotic
  "session created under a different directory" case relocates, and no data is migrated
  (clean cut per working rule 4). Reported as a behavior change.
- **X9 — no `NaruPage<T>` existed; messages have their own page type.** A generic
  `NaruPage<T>` is added for projects/sessions; `NaruMessagePage` is left untouched.
- **X10 — `NaruSessionSummary`.** Doesn't exist; `NaruSessionRef` is the store's header
  row. Decision: introduce `NaruSessionSummary` (uuid, name, scope, creation/modification,
  sessionDir, workingDir, projectId, user, version) per the plan; `projectMissing` computed
  in the service layer. `NaruSessionRef` stays as-is for existing `list()` callers.
- **X11 — `/session new` is a reset, not a creation.** Directive-side session creation
  happens via the app entrypoint/builder only; `--project/--user` options land there (X7).
- **X12 — module wiring**: extensions reach the CLI through `profiles/naru-profile-dev`,
  not `app/naru/pom.xml` (comment in the desktop pom explains why). `naru-projects` wired
  accordingly.

## 5. Build notes

- `mvn -q -DskipTests compile` passes on the current tree (JDK 25 default, toolchain
  `openjdk 25` present). Use `-Dnuts.args=-y` for tests (`Nuts.openWorkspace()` prompt).
- Working tree contains unrelated user WIP under `app/naru-desktop` and
  `documentation/todo/todo-ui.md`; those files are **not** part of this task's commits and
  must not be staged (Phase 1 only touches `WorkbenchPane.java`/`InspectorPane.java`, which
  are clean).
- **Test-loop trap (learned in Phase 1):** extension resolution goes through the Nuts
  profile, which reads jars from `~/.m2` — cross-module test runs must be preceded by
  `mvn -q -DskipTests install` (or use `-am` so the reactor wins), otherwise module tests
  silently run against stale installed jars and mix two generations of the API.
- **Baseline test failures at 889fb39 (pre-existing, NOT caused by the project work —
  reproduced in a clean worktree of that commit):** naru-impl: `NaruGoDirectiveTest.
  aContextWithNothingInItAtAllIsStillNothingToDo`, `NaruModelResourceResolutionTest` (2),
  `NaruToolCallLifecycleTest` (2), `NaruTaskInterruptTest` (2),
  `NaruTaskCompletionTest.cancelSettlesTheRunAsKilled`, `NaruNArgCompleteResolverTest.
  anAliasedDirectiveStillDelegatesArguments`, `NaruLineCompletionTest.
  anArgumentGoesToTheDirectiveThatOwnsIt` (10 total). naru-skills:
  `NaruSkillDirectiveTest` (8) + `NaruSkillsRootsTest.theTrustCommandTogglesAForeignRootAndListShowsItsState`
  (1). naru-budget: `NaruBudgetDirectiveTest` (27). naru-sessions:
  `NaruSessionDirectiveTest` (3: ambiguousNameIsReported, resetOnASavedOnlySessionAsksForARunningOne,
  subcommandsThatActOnEverythingRejectSelectors). naru-plan: `NaruPlanWorkflowTest` (11)
  + `NaruPlanGraphTest` (2) + `NaruPlanModeSwitchTest` (1). naru-tasks:
  `NaruSpawnDirectiveTest` (3). All other suites green, including store-file, fs, shell,
  coder-java, index, git, semantic, compact, routines, tags, permissions, api.
