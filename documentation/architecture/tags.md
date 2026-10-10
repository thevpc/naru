# Tool tags

A **tool tag** is a named capability label that sits on top of the tool
registry and decides, per task, which tools the model is offered. This is the
second layer of the "what may this task do" ladder — toolsets decide *whether a
tool exists*, tags decide *whether a task may see it*.

This document explains what a tag is, how the tag gate works, the vocabulary
that ships today, and how tags relate to tools, tasks, prompt modes, skills,
agents and directives.

## 1. What a tag is

A tag is the pair `(name, description)`:

| Type | Role |
|---|---|
| `NaruToolTag` | API type: `name()` + `description()` |
| `DefaultNaruToolTag` | The only implementation; normalizes `name` to **lower-kebab-case**, requires a non-blank description |
| `NaruToolTagProvider` | Declares a list of tags; one provider per feature/extension |
| `NaruToolTags` | The constant vocabulary (`fs`, `routine`, `network`, `exec`, `write`, `ai`, `dev`, `java`, `semantic`, `mcp`, `index`, `git`, `tags`) |

Tags are **registered**, not invented ad hoc. A provider is a Nuts `NComponent`
discovered on the classpath, so each extension jar contributes its own tags the
moment it is on the classpath:

- the built-in provider (`naru-impl`) always registers the five base tags;
- every `NaruToolTagProvider` implementation found by the extension loader is
  registered at session bootstrap (`NaruRegistryImpl.registerDefaults()`);
- the agent may install a `tagFilter` predicate, which vetoes a tag *before* it
  reaches the registry — a rejected tag is invisible to every task;
- registration is first-wins: a duplicate tag name is silently ignored.

Two API entry points expose them:

```java
NOptional<NaruToolTag> findAvailableTag(String name);   // name is kebab-insensitive
Map<String, NaruToolTag> availableTags();
```

`addToolTag(name)` resolves through `findAvailableTag(...).get()`, so granting a
tag that no provider declared **throws** instead of silently no-op'ing.

> **Not to be confused with "thinking tags".** `NaruThinkingTags` is a separate,
> unrelated API: an open/close delimiter pair used to strip reasoning text out of
> model output streams. Same word, different subsystem — see
> [`model-registrations.md`](../user-guide/content/model-registrations.md).

## 2. The tag gate: how a task decides which tools to offer

Every model request builds its tool list from `NaruTask.findTools()`. That one
method is the single source of truth for the tool schema sent to the model,
`/tools list` and `/tools unselected`. The gate runs, in order:

1. **Prompt-mode veto** — `mode.acceptToolTags(tool.tags())`. The mode may
   reject a whole tag set outright (see §6). If it returns false the tool is
   dropped here regardless of anything else.
2. **The tool's own veto** — `tool.isRelevant(task)`. The one gate a tool needs
   for a decision only it can make ("is there actually something to remove?",
   "is this feature switched on?").
3. **Task exclusion set** — `task.findToolExclusions()`. A per-task list of tool
   *names* the task refuses to see. This is the second, name-based filter.
4. **The tag match** — the decisive rule:
   - a tool with **no tags** is hidden unless it declares itself essential
     (`tool.isEssential() == true`) — the gate is **fail-closed**;
   - a tool with tags is offered **iff the task holds at least one of them**.

```java
Set<String> tt = tool.tags();
if (!excludedTools.contains(tool.name())) {
    if (tt.isEmpty()) {
        if (tool.isEssential()) {
            include(tool);                          // untagged AND declared essential
        }                                            // else: hidden (fail-closed)
    } else if (tt.stream().anyMatch(x -> taskToolTags.contains(kebab(x)))) {
        include(tool);                              // tagged: needs one granted tag
    }
}
```

**The effect-tag AND rule (O3), opt-in.** The OR match above reads wrong for a
tool that reaches outside the process. `run_shell` wears `{network, exec, write}`,
and "any one" means a task granted only `write` — a right meant for editing a
file — is also handed a shell. The rule that fixes that is behind
`naru.tags.effectAnd`, resolved once per session (system property, then session
env, then project config), with three values:

| Value | Behaviour |
|---|---|
| `off` (default) | exactly the OR match above |
| `warn` | OR is kept, and the first `findTools()` that has a mismatch reports the tools the AND rule would withdraw |
| `on` | `write`, `exec` and `network` must **all** be granted; the remaining domain tags keep the OR rule |

Under `on`, a tool is visible when every effect tag it declares is held **and**,
if it also declares domain tags, at least one domain tag is held. `git_commit`
(`{dev, git, write}`) therefore needs `write` plus `git` or `dev`; `run_shell`
needs all three effects; a domain-only tool such as `git_status` (`{dev, git}`)
is unchanged. The flag exists so the change can be measured before it becomes
the rule.

**Why fail-closed.** "No tags" used to read as "no permission needed", so a tool
that simply forgot to declare its tags was offered to every task, including ones
holding none — the exact inverse of what the rest of the gate promises. A tag is
a permission floor; a tool that wears none must not be able to walk under it.
The exception is explicit rather than implicit: `NaruTool.isEssential()`
defaults to `false`, and a tool that is genuinely unconditional says so in its
own source (`ThinkTool` is the only one today).

So a tag is a **permission floor, granted per task**. A task with zero tags sees
no tagged tool at all — not "everything minus a few", the complete inverse: it
starts from zero coverage and every grant opens the doors of one capability.

### Two gates, both by name but opposed in spirit

| | `tags` (the gate in §2.4) | exclusions (`/tools exclude`) |
|---|---|---|
| Operates on | a *tag*, shared by many tools | a *tool name*, one tool |
| Meaning | "this capability is allowed" | "this specific tool is banned" |
| Default | nothing granted | nothing excluded |
| Conflict | a tool wins if any granted tag matches | exclusion always wins over a tag |

Untagged tools bypass the *tag match* but not the gate — they are visible only
when they declare `isEssential()`, and only when a mode accepts them and their
name is not excluded. Today the only essential untagged tool is `think` (the
scratchpad), which is why a completely ungranted task still has *one* tool
available. Every other untagged tool is invisible to everyone until it is
tagged, which the registry lint (`NaruTagRegistryLintTest`) enforces.

## 3. The vocabulary: tags that ship today

| Tag | Declared by | Description (as registered) | Example tools |
|---|---|---|---|
| `routine` | `naru-impl` (builtin) | routine operations including add, edit, search routines | `routine_add_line`, `routine_list_lines` |
| `ai` | `naru-impl` (builtin) | AI operations including calling other LLMs/models | `delegate_to_model`, `model_list`, `context_compact`, `ollama_*` |
| `network` | `naru-impl` (builtin) | networking operations including search web | `search_web`, `run_shell` |
| `write` | `naru-impl` (builtin) | persistent modifications in files, folders, databases | `file_write`, `file_edit_search_replace`, `git_commit`, `routine_add_line` |
| `exec` | `naru-impl` (builtin) | spawning new processes or tasks | `run_shell`, `maven_compile/test/package`, `ollama_start/stop/status/ps`, `routine_run` |
| `fs` | `naru-tools-fs` | file system operations including add, edit, search files | `file_read`, `file_grep`, `folder_find`, `cd`, `pwd`, `diff` |
| `dev` | `naru-tools-coder-java` | development operations including compile and test | `maven_compile/test/package`, `git_*`, `code_symbols`, `project_map`, `semantic_*` |
| `java` | `naru-tools-coder-java` | java development operations | `maven_compile`, `maven_test`, `maven_package` |
| `semantic` | `naru-tools-semantic` | semantic code search and vector indexing | `semantic_index`, `semantic_search` |
| `git` | `naru-tools-git` | Git version control tools | `git_status`, `git_diff`, `git_log`, `git_commit` |
| `mcp` | `naru-tools-mcp` | MCP tools | every `McpBackedTool` (only once an MCP server is configured) |
| `index` | `naru-tools-index` | codebase indexing and symbol search | `code_symbols`, `find_symbol`, `project_map`, `project_summary` |
| `tags` | `naru-tools-tags` | grant and revoke tool tags at runtime | `tag_add`, `tag_remove`, `tag_list` |
| `plan` | `naru-tools-plan` | planning tools | `plan_create`, `plan_update`, `plan_get` |
| `skills` | `naru-skills` | load a skill's instructions on demand | `skill` |

`java` and `semantic` are wired **additively**: every Java and semantic tool
keeps `dev` and *also* wears its specific tag. `/tags enable java` therefore
only ever widens visibility — a task granted `dev` before the split sees exactly
the same tools as before, while a task that wants only the Java or only the
semantic surface can now ask for the narrow tag. (Tag matching is OR, so
granting `java` still reveals the Maven tools that also wear `dev`; the specific
tag is the more precise key, not a replacement.)

`NaruTagRegistryLintTest.everyAvailableTagIsMeaningful` pins the invariant for
the whole vocabulary: **every tag `/tags available` offers has a tool behind
it**, with `mcp` as the one exception (its tools are created only when a server
is declared in the agent env).

Notes on the vocabulary:

- Every provider normalizes its names to lower-kebab, so `"FILESYSTEM"` and
  `"fs"` are the same tag.
- A tool may wear several tags; the gate matches with **or** semantics — `git_commit`
  is `{dev, git, write}`, so granting any one of the three reveals it. The
  opt-in effect-tag AND rule (§2) tightens only the effect tags (`write`, `exec`,
  `network`); the domain tags above keep their OR behaviour.
- The `tags` tag gates the two tools that change tags (§4): it is the
  self-referential key that must be granted before the model may manage grants
  itself.
- What ships is a *floor*. Extensions register their own tags; `findAvailableTag`
  makes the current vocabulary queryable at any time (`/tags available`).

## 4. Tags and tools

A tool declares its tags once, in its constructor, and never changes them:

```java
new FileWriteTool() {
    super("file_write", new String[]{NaruToolTags.FILE_SYSTEM, NaruToolTags.WRITE});
}
```

The tag set is what the gate matches on, so it must describe *everything* the
tool can do: `file_write` is both filesystem and write, `run_shell` is both
network and execute. A tool that only declares the friendly half of its effect
would be grantable through the stricter half's absence — `/tags enable fs` must
not be enough to write a file.

A tool's tag set is immutable and shared by every task (the registry reuses the
same tool instance). "What tags this tool wears" is therefore a property of the
tool, while "which tags this task holds" is a property of the task. The gate in
§2 intersects the two.

Three consequences worth spelling out:

- **Removing a jar removes its tags too.** Tag registration rides the same
  classpath discovery as tools; a tool that does not exist needs no tag.
- **Toolsets and tags are orthogonal install/visibility axes.** A toolset
  decides *whether a tool is installed at all*; a tag decides *whether an
  installed tool is shown to a task*. Both have to be satisfied.
- **Tagged tools stay callable code.** The tag gate changes the *schema* the
  model receives, not the tool's implementation. A tool that a task's gate hides
  is simply absent from that task's tool list and tool-call surface.

## 5. Tags and tasks

Every task holds its own **granted tag set** plus a **tool-exclusion set**. Both
are empty at spawn unless explicitly seeded or snapshot-inherited.

### Seed the floor at spawn

`NaruTaskSpec.toolTags(...)` sets the tags a new task is born with:

```java
NaruTaskSpec.of().toolTags("exec", "plan")   // replaces, never accumulates
```

Semantics pinned by tests:

- `toolTags(...)` **replaces**, it does not accumulate; calling it again clears
  the previous list.
- Blank and null entries are dropped; entries are trimmed.
- There is no default grant: a fresh `NaruTaskSpec.of()` yields an empty set.
- Entries are seeded **leniently** at spawn: a name with no registered provider
  is kept (so it still round-trips) rather than rejected, because a spec may name
  a tag whose provider is only present in a later session. The runtime
  `addToolTag` path stays strict.

### Spawn-time resolution: inherit, add, revoke

A spawn is not just a seed; it is a **resolution with provenance**. `NaruTaskSpec`
carries the spawn inputs (`inherit(...)`, `addTags(...)`, `revokeTags(...)`,
`excludeTools(...)`, `addSkills(...)`, `strategy(...)`/`windowTurns(...)`,
`policy(...)`, `contract(...)`, `spawnKind(...)`, `ext(...)`) and the core resolves
them through `NaruSession.resolveSpawn(parent, spec)` before the child is created.
Precedence, lowest to highest:

| Source | Contributed by |
|---|---|
| `DEFAULT` | extension / spawn-kind defaults and the strategy implication |
| `POLICY` | the named policy (`/start --policy=<name>`) |
| `FLAG` | the call-site flags (`--add-tags`, `--revoke-tags`, ...) |
| `CONTRACT` | the target's contract — **skills only**; a contract may never add tags |

Rules pinned by `NaruSpawnTest`:

- **Tags are inherited only on request.** A plain spawn (`NaruSpawnStrategy.NONE`,
  no `--inherit=tags`) still grants nothing, exactly as before. `--inherit=tags`
  copies the parent's granted **names** as a **snapshot**: a later change to the
  parent's set never reaches an already-spawned child, while a sibling spawned
  after the change sees it. Unknown names survive the copy (the raw name set is
  inherited, not the resolved definitions).
- **The context strategies imply tag inheritance.** `--fork`, `--window=<n>turns`
  and `--summary` all inherit tags by default
  (`NaruSpawnStrategy.impliesTagsInherit()`), because a child that shares the
  parent's conversation but not its tool floor would be inconsistent. The
  implication applies only when `--inherit` does not already mention `tags`;
  explicitly asking for `--inherit=env` does **not** cancel a fork's implied tag
  snapshot. There is no "no tags" spelling — fewer tags means revoke them.
- **Add wins over revoke at equal or higher precedence.** Revoking a tag the
  resolution holds removes it; revoking a name nothing holds is a no-op that
  raises a `revoke-without-hold` **warning** on the resolution (surfaced on the
  spawn event and by `/start --explain`) rather than silently doing nothing.
- **Exclusions and env merge.** The strategies `NONE | FORK | WINDOW(n) | SUMMARY`
  map onto `/start`; `SUMMARY` needs a context compactor (e.g.
  `naru-tools-compact`) and degrades to the last turn with a warning without one.

The `/start` flag surface:

```text
/start --fork | --window=6turns | --summary     # context strategy (last flag wins)
       --inherit=tags[,env]                     # snapshot-inherit parent state
       --add-tags=exec,write                    # grant on top of the resolution
       --revoke-tags=write,exec                 # revoke from the resolution
       --exclude-tools=run_shell --add-skills=code-review
       --policy=review-safe                     # apply a /spawn-policy
       --explain review                         # print the resolution, spawn nothing
```

### Named spawn policies

`/spawn-policy <name> [flags]` defines an in-memory bundle of the same seeds;
`/start --policy=<name>` applies it *after* the extension defaults and *before* the
call-site flags, so a flag add still wins over a policy revoke. Policies are never
persisted — they are re-declared by whatever init script defines them on each
session start — and the scalar configs they pin (`model`, `working-dir`,
`prompt-mode`) are **inherit-or-override only**: there is no scalar revoke.

```text
/spawn-policy review-safe --inherit=tags --revoke-tags=write,exec --add-skills=code-review
/start --policy=review-safe review
```

### Contracts

A spawn target (an agent `.md` front-matter, or a script/routine header) may declare
a **contract**: `requires` (a tag expression using `&`, `|`, `!`, names and
parentheses), an optional `tools` list, and `skills`. The contract is
validated **after** resolution against the resolved tag set; an unsatisfied contract
fails the spawn with a message naming the fixing flag (`--add-tags=<tag>` /
`--revoke-tags=<tag>`). `tools` names the tools the target needs by name: a tool the
resolution reaches via `--exclude-tools` (or a policy) fails the spawn with a message
naming the exclusion to drop. Naming a tool is a constraint, not a grant — the
contract cannot add tools any more than it can add tags; a tool missing from the
registry is not a violation. A contract **constrains and grants skills only** — it may
not add, revoke or inherit tags (such keys are rejected at parse time), because it must
never expand permission. The model-initiated path (`delegate_to_model`) is narrower
still: it takes a target name plus optional `revoke_tags` / `inherit` **narrowing
only**, and exposes no parameter that could add a tag.

### Provenance and the `task-spawn` event

Every spawn appends a `task-spawn` event (the pre-kebab historical name
`TaskSpawned` is still matched) whose payload is the resolved sets and the
source of each item — the same lines `/start --explain` prints without spawning:

```text
strategy=fork (flag)
inherit=tags (default)
tags=[fs,git] (inherit − revoke write,exec)
skills=[code-review] (contract)
exclusions=[run_shell]
policy=review-safe
contract={requires: "fs & !write"}
```

The payload carries **no grant channel** — it is a record, not an authorization — and
an already-spawned child is never re-granted by a later parent change.

### Mutate at runtime

| What | API | Via directive/tool |
|---|---|---|
| Grant | `task.addToolTag(name)` (throws on unknown tag) | `/tags enable fs`, `tag_add` (model-callable) |
| Revoke | `task.removeToolTag(name)` (silent no-op on unknown) | `/tags disable fs`, `tag_remove` |
| Query | `task.findToolTags()` → `List<NaruToolTag>` | `/tags list`, `/tags available`, `tag_list` |
| Ban a tool name | `task.addToolExclusion(name)` | `/tools exclude cd` |

`/tags enable` understands `*`, `all` and glob patterns (`fs*`); `tag_add`
accepts a comma/space/`;`-separated list of names in a `tags` parameter.
`tag_add`/`tag_remove` deliberately do **not** inline the tag catalog — ask
`tag_list` (filters: `enabled`, `disabled`, `all`, `query`) to discover tags.

### Persistence

The floor is part of the task, and is written with it. `NaruTaskImpl.toElement()`
stamps `schemaVersion: 2` and serializes both sets — the granted tag **names**
under `toolTags` and the banned tool names under `excludedTools` — and `load()`
restores them, so a saved session comes back with the gate it had:

- **Restoring is lenient where granting is strict.** `addToolTag` resolves a
  name through the registry and throws on an unknown one; the loader must not, or
  an element that mentions a tag whose provider has since been uninstalled could
  never be loaded. The name is **kept** (so it still round-trips and is not
  silently dropped) and the unknown tag is reported as a warning.
- **Tag definitions are derived, never stored.** `findToolTags()` resolves the
  held names through `findAvailableTag` on every call, so there is no parallel
  definition list to drift out of step with the names the gate matches on.
- **`reset()` keeps both sets (O4).** A reset returns the task to a runnable
  state; it is not a permission wipe. The old asymmetry — clearing the tags but
  not the exclusions — left a task that refused tools it no longer had any reason
  to see.
- Elements written before version 2 simply lack both arrays and load as an empty
  floor, exactly as version 1 did.

## 6. Tags and prompt modes

Prompt modes sit *above* the tag gate: `NaruPromptMode.acceptToolTags(Set<String>)
is consulted first, on the whole tag set of each candidate tool.

| Mode | `acceptToolTags` | Effect on tool list |
|---|---|---|
| `default` (builtin) | always true | no veto; tags fully in charge |
| `plan` (planning) | rejects any tool wearing `exec` **or** `write` | read-only surface even if the task holds `exec`/`write` tags |
| `implement` | always true | no veto |

A mode veto is a **hard cut**: it is applied before `isRelevant`, before
exclusions and before the tag match, so it can suppress a tool the task has
explicitly been granted. That is deliberate: a mode expresses the *session's*
current posture (plan now, do later), while tags express the *task's* standing
permission floor. Planning a goal requires an eligible tag floor (`/tags enable
plan`) **and** a mode that does not strip it; switching to `implement` keeps the
tag floor but removes the mode veto.

`modeIntent()` (`GENERAL` / `PLANNING` / `EXECUTING`) answers a different
question — *what a mode switch means*, not *what it permits* — so features can
react to a switch without re-implementing a permission set. See the planning
guide for the concrete read-only design.

## 7. Tags and skills

Skills are **markdown instruction text**; a skill has no capability of its own.
Two things connect skills to the tag system:

- **The `skills` tool tag gates the `skill` tool.** The optional `naru-skills`
  jar declares the tag and owns the `skill` tool; a task that was not granted
  `skills` sees neither the tool nor the skill catalog, because the catalog is
  emitted only when the tool is visible. Granting `skills` never loads a skill:
  it makes the *loading action* available. A loaded skill's body is injected
  whenever the skill's own `requires` gate holds.
- **A skill may declare a tag requirement.** A skill file may open with a small
  front-matter header carrying `requires`, a tag expression using the same
  grammar as a spawn contract (`&`, `|`, `!`, names, parentheses):

  ```markdown
  ---
  requires: "fs & !write"
  ---
  Follow git-flow strictly.
  ```

  The header is stripped from the body. Unlike a spawn-time check, `requires` is
  evaluated at **request-build time** against the task's *current* tags: a
  satisfied (or absent) requirement injects the body, and an unsatisfied one
  withholds the body behind a `SKILL REQUIRES GATE` note instead of silently
  injecting it. An expression that names a tag no provider declares is reported
  separately as unsatisfiable. A malformed expression is treated as absent, with
  a warning. `requires` is never a load-time refusal — a skill stays LOADED when
  a tag is revoked, it is merely gated until the tags hold again.

- **A skill never grants a tag.** A skill that instructs the model to call
  `file_write` is still useless unless the task holds `fs` + `write`; the
  `requires` header only makes that seam *visible*. The `allowed-tools`
  front-matter is parsed and reported by `/skill doctor` when an entry maps to no
  tool the task can call, but it can never widen a task's grants.
- `/start --add-skills=<name>` (and a contract's `skills`) request skills for a
  spawn; when the skills extension is not installed the request is reported as a
  warning rather than silently dropped.

If you want "this skill implies these tools" to *grant* them, that link must
still be made explicit — e.g. an init script that `load`s the skill and
`/tags enable`s the tags it needs, or a spawn with
`--add-skills=<name> --add-tags=<tags>`.

## 8. Tags and agents, sessions and directives

- **Agent-level pruning.** `NaruAgent.tagFilter(Predicate<NaruToolTag>)`
  decides which tags the session's registry may ever know. A tag rejected here
  is invisible to `availableTags()`, cannot be granted, and its tools can never
  be offered — the agent owner sets the outer boundary; tasks set the inner one.
- **Directives are not tag-gated.** `/tags`, `/tools`, `/mode` and friends are
  registry directives, filtered by the agent's *directive* filter, not by a
  task's tags. This is why a fully ungranted task can still run `/tags enable`
  to bootstrap itself. The tag gate applies to **model-callable tools** only.
- **The model can manage grants, once admitted.** `tag_add`/`tag_remove`
  (`{tags}`) let the model flip tags at runtime, and `tag_list` (`{tags}`) lets it
  see what exists and what is granted — but they are themselves tagged,
  so a task must first be granted `tags` from outside (directive or spawn spec).
  The self-referentiality is deliberate: "the right to change rights" is never
  granted by default. `NaruTagGateTest` pins both halves of this.
- **Deregistration cleans per-task extension state.** When a task reaches a
  terminal state and leaves the session, the core calls
  `NaruSessionExtension.onTaskDeregistered(session, taskId)`. An extension that
  keys state by task id (the skills extension's selection map) drops that entry,
  so `ext/<name>.tson` stays proportional to the live tasks rather than to every
  task the session ever ran.

## 9. A worked example

A scripted task that must write files and run commands:

```text
# naru script (init or session statements)
/mode implement                     # remove nothing (implement vetoes nothing)
/tags enable fs                     # open the file tools
/tags enable network                # open run_shell / search_web
/tools exclude cd pwd               # ...but ban the working-dir wanderers
/tags list                          # floor now: fs, network
```

The same floor can be seeded at spawn through the API:

```java
session.newTask(NaruTaskSpec.of()
        .toolTags("fs", "network")
        .promptMode(mode("implement"))
        .statements("/tools exclude cd pwd"));
```

And a task that should only ever *plan* ends up with the opposite shape: read
tools only, writes and executes both stripped by mode and never granted anyway.

## 10. Known inconsistencies and gaps

These are the seams of today's implementation; the tests that pin the intended
behaviour are referenced so the delta is easy to audit.

1. **Tag changes mid-turn are schema-only.** A tag granted after a request was
   built does not retroactively un-hide tools already sent; it affects the *next*
   request's tool list.

### Fixed during Phase 1 (kept for the audit trail)

- **`/tags disable` was documented as "exclude tools by name" while its body
  revoked a tag**, and logged `"tag %s enabled"` on the way out. Decided in
  favour of revoking a *tag* — banning one tool by name is `/tools exclude`,
  the other gate. Help text, description, log message and the integration test
  were all corrected together (`NaruTagsDirective.java`,
  `AgentModelIntegrationTest.testScriptTagsScriptableFileAndSystemSave`), and
  `disable` now understands `all`/`*` like `enable` does.
- **`removeToolExclusion` added to the exclusion set instead of removing**, so
  `/tools unexclude` was a no-op that made exclusions permanent.
- **`/tools exclude|unexclude` re-parsed the full directive argument**, so the
  subcommand keyword itself was treated as a tool name: `/tools exclude cd` also
  banned a tool literally named `exclude`, and `unexclude` always reported
  success 0. Both now consume the already-positioned `cmdLine`, like every other
  subcommand, and count what they removed.
- **`semantic` and `java` were registered but worn by no tool, so granting them
  was a silent no-op.** They are now wired **additively**: the semantic provider
  is restored, `semantic_index`/`semantic_search` wear `{dev, semantic}`, the
  Maven tools wear `{dev, java, exec}`, and the registry lint fails if any
  advertised tag has no tool (§3).
- **Maven tools could leak into plan mode.** `maven_compile`/`maven_test`/
  `maven_package` spawn `mvn` (arbitrary build plugins), but wore only `dev`,
  which plan mode accepts. They now also wear `exec`, so the mode veto keeps
  them out of the read-only surface. `NaruPlanModeToolGateTest` pins plan mode's
  entire tool surface as an explicit allowlist of names.
- **The system prompt carried a spawn-time "Available tools" snapshot** that
  went stale after a mid-session `/tags` or `/tools` change, duplicating (and
  potentially contradicting) the live schema. The line is gone; the schema sent
  with each request is the only tool list.
- **The gate treated "no tags" as "always visible".** It is now fail-closed
  behind `NaruTool.isEssential()` (§2), and `file_write`/`run_shell` declare
  the full `{fs, write}` / `{network, exec, write}` tag sets they actually
  exercise.

### Fixed during Phase 2 (kept for the audit trail)

- **The floor was not persisted.** `toElement()` serialized neither the granted
  tags nor the exclusions and `load()` restored neither, so a reloaded session
  started from the empty floor; `NaruTaskStatePersistenceTest` pins the
  save/reload round trip and the versioned element.
- **An unknown tag was unrecoverable.** `load()` used the throwing `addToolTag`,
  so an element naming a tag whose provider had gone missing could not be loaded
  at all. It now keeps the name and warns.
- **`findToolTags()` could disagree with the gate.** The definitions lived in a
  second list beside the names; the two could drift. The definitions are now
  derived from the names on every call.
- **`reset()` cleared the tags but not the exclusions.** Both are now kept (O4).
- **Terminal tasks leaked their extension state.** `ext/skills.tson` accumulated
  one dead selection entry per task ever run, because nothing told the extension a
  task had left. The new `NaruSessionExtension.onTaskDeregistered` hook fixes it.

### Added during Phase 3 (the spawn seam, kept for the audit trail)

- **The parent's floor was unreachable by a child except by hand.**
  `toolTags(...)` could seed a spec, but there was no way to snapshot-inherit the
  parent's set, combine it with adds/revokes, or record where each item came from.
  The `NaruTaskSpec` spawn inputs, `NaruSession.resolveSpawn` and
  `NaruSpawnPlanner` add that; `/start` grew `--fork` / `--window` / `--summary` /
  `--inherit` / `--add-tags` / `--revoke-tags` / `--exclude-tools` / `--add-skills`
  / `--policy` / `--explain`.
- **Strategies and tags were separate axes.** A fork shared the conversation but
  not the tool floor. Context strategies now imply tag inheritance (unless
  `--inherit` names `tags` explicitly), pinned by `NaruSpawnTest`.
- **A spawn could fail silently.** `/start` now records a failure flag and returns
  an error result for an unknown target, an agent `.md` with no contract, or an
  invalid flag value, instead of logging and continuing to spawn.
- **A contract could have expanded permission.** A contract may validate
  `requires` and grant skills, but tag-add/revoke/inherit keys are rejected at
  parse time; the model path exposes no add parameter at all.
- **The resolution was invisible.** `task-spawn` and `/start --explain` now carry
  per-item provenance; the payload has no grant channel.

## 11. Where the code lives

| Concern | Location |
|---|---|
| `NaruToolTag`, `DefaultNaruToolTag`, `NaruToolTagProvider`, `NaruToolTags` | `core/naru-api/src/main/java/net/thevpc/naru/api/registry/` |
| `NaruTool.tags()`, `DefaultNaruTool` (tag store), `NaruTool.isEssential()` | `core/naru-api/.../api/registry/` |
| Tag registration + `availableTags`/`findAvailableTag`, bootstrap discovery | `core/naru-impl/.../impl/registry/NaruRegistryImpl.java` |
| Builtin tag vocabulary (`routine`, `ai`, `network`, `write`, `exec`) | `core/naru-impl/.../impl/registry/NaruBuiltinToolTagProvider.java` |
| The gate itself (`findTools`) | `core/naru-impl/.../impl/engine/scheduler/NaruTaskImpl.java` |
| Per-task tag set + grant/revoke/exclusion API | `NaruTaskImpl` (state + methods), `NaruTaskSpec` (spawn seeding) |
| Spawn API (`NaruSpawnContext/Resolution/Policy/Seed/Source/Strategy/Inherit/Contract/Targets`, `NaruToolTagExpression`) | `core/naru-api/.../api/spawn/` |
| Spawn resolution (`NaruSpawnPlanner`), `resolveSpawn`, policy registry, `onSpawn`/`onSpawned`, `task-spawn` | `core/naru-impl/.../impl/engine/`, `core/naru-impl/.../impl/engine/spawn/NaruSpawnPlanner.java` |
| Mode veto | `NaruTaskImpl.findTools()`, `NaruPromptMode.acceptToolTags` |
| `/tags` directive | `extensions/naru-tools-llm/.../NaruTagsDirective.java` |
| `/tools` directive | `extensions/naru-tools-llm/.../NaruToolsDirective.java` |
| `/start` spawn flags + `--explain`, `/spawn-policy` | `extensions/naru-tools-tasks/.../NaruStartDirective.java`, `extensions/naru-tools-tasks/.../NaruSpawnPolicyDirective.java` |
| Model-path spawn (narrowing only) | `extensions/naru-tools-llm/.../ModelDelegateTool.java` |
| `tag_add` / `tag_remove` tools + `tags` tag + toolset | `extensions/naru-tools-tags/` |
| Per-extension tag providers | one `*ToolTagProvider` per feature extension (§3) |
| Behavioural pin | `test/naru-agent-test/.../NaruTagGateTest.java`, `test/naru-agent-test/.../NaruTagRegistryLintTest.java`, `test/naru-agent-test/.../NaruPlanModeToolGateTest.java`, `test/naru-agent-test/.../NaruTaskStatePersistenceTest.java`, `test/naru-agent-test/.../NaruSpawnTest.java`, `test/naru-agent-test/.../NaruDelegateSpawnTest.java`, `extensions/naru-tools-tasks/src/test/.../NaruSpawnDirectiveTest.java`, `core/naru-impl/src/test/.../TaskSpawnConfigTest.java` |