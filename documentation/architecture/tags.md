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
| `NaruToolTags` | The constant vocabulary (`fs`, `routine`, `network`, `exec`, `write`, `ai`, `dev`, `mcp`, `index`, `git`, `tags`) |

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
method is the single source of truth for the tool schema sent to the model, the
"Available tools" line of the system prompt, `/tools list` and `/tools
unselected`. The gate runs, in order:

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
| `ai` | `naru-impl` (builtin) | AI operations including calling other LLMs/models | `delegate_to_model`, `context_compact`, `ollama_*` |
| `network` | `naru-impl` (builtin) | networking operations including search web | `search_web`, `run_shell` |
| `write` | `naru-impl` (builtin) | persistent modifications in files, folders, databases | `file_write`, `file_edit_search_replace`, `git_commit`, `routine_add_line` |
| `exec` | `naru-impl` (builtin) | spawning new processes or tasks | `run_shell`, `ollama_start/stop/status/ps`, `routine_run` |
| `fs` | `naru-tools-fs` | file system operations including add, edit, search files | `file_read`, `file_grep`, `folder_find`, `cd`, `pwd`, `diff` |
| `dev` | `naru-tools-coder-java` | development operations including compile and test | `maven_compile/test/package`, `git_*`, `code_symbols`, `project_map`, `semantic_*` |
| `git` | `naru-tools-git` | Git version control tools | `git_status`, `git_diff`, `git_log`, `git_commit` |
| `mcp` | `naru-tools-mcp` | MCP tools | every `McpBackedTool` (only once an MCP server is configured) |
| `index` | `naru-tools-index` | codebase indexing and symbol search | `code_symbols`, `find_symbol`, `project_map`, `project_summary` |
| `tags` | `naru-tools-tags` | grant and revoke tool tags at runtime | `tag_add`, `tag_remove` |
| `plan` | `naru-tools-plan` | planning tools | `plan_create`, `plan_update`, `plan_get` |

Two tags that used to be here — `java` and `semantic` — were removed: no tool
wore either one (every Java and semantic tool wears `dev`), so granting them was
a silent no-op. `NaruTagRegistryLintTest.everyAvailableTagIsMeaningful` now
pins the invariant for the whole vocabulary: **every tag `/tags available`
offers has a tool behind it**, with `mcp` as the one exception (its tools are
created only when a server is declared in the agent env).

Notes on the vocabulary:

- Every provider normalizes its names to lower-kebab, so `"FILESYSTEM"` and
  `"fs"` are the same tag.
- A tool may wear several tags; the gate matches with **or** semantics — `git_commit`
  is `{dev, git, write}`, so granting any one of the three reveals it.
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
are empty at spawn unless explicitly seeded.

### Seed the floor at spawn

`NaruTaskSpec.toolTags(...)` sets the tags a new task is born with:

```java
NaruTaskSpec.of().toolTags("exec", "plan")   // replaces, never accumulates
```

Semantics pinned by tests:

- **Tags are never inherited from the parent task.** A child spawned with no
  spec sees zero tagged tools even if its parent held `exec` and `plan`.
- `toolTags(...)` **replaces**, it does not accumulate; calling it again clears
  the previous list.
- Blank and null entries are dropped; entries are trimmed.
- There is no default grant: a fresh `NaruTaskSpec.of()` yields an empty set.

### Mutate at runtime

| What | API | Via directive/tool |
|---|---|---|
| Grant | `task.addToolTag(name)` (throws on unknown tag) | `/tags enable fs`, `tag_add` (model-callable) |
| Revoke | `task.removeToolTag(name)` (silent no-op on unknown) | `/tags disable fs`, `tag_remove` |
| Query | `task.findToolTags()` → `List<NaruToolTag>` | `/tags list`, `/tags available` |
| Ban a tool name | `task.addToolExclusion(name)` | `/tools exclude cd` |

`/tags enable` understands `*`, `all` and glob patterns (`fs*`); `tag_add`
accepts a comma/space/`;`-separated list of names in a `tags` parameter.

### Persistence caveats

Task tags are **session-scoped and not persisted**. `NaruTaskImpl.toElement()`
does not serialize the granted set, `load()` does not restore it, and a task
`reset()` clears it. A reloaded session therefore starts from the empty floor
again — re-grant in the session's init script if a task must keep its tags
across restarts.

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

**Skills have no tags, and the tag system has no skills.** This is a strict
non-relation, documented to prevent the expectation that the two meet:

- Skills are **markdown instruction text**, selected per task by an explicit
  `/skill load` and spliced into the model's context verbatim. Their only
  attribute is visibility (`public` vs `private`, a filesystem concern).
- Skill visibility is directory-based (`.naru/skills` vs `.naru/local/skills`),
  not tag-based. There is no such thing as a "skills-authorized" tag.
- Loading a skill does **not** grant tool tags, and granting a tag does **not**
  load a skill. A skill that instructs the model to call `file_write` is useless
  unless the task also holds `fs` + `write`.
- The two features load and unload independently: skills via `/skill`, tags via
  `/tags` — both per task, neither inherited, both cheap to reset.

If you want "this skill implies these tools", that link must be made explicit
today — e.g. an init script that `load`s the skill and `/tags enable`s the tags
it needs, or a routine that does both.

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
  (`{tags}`) let the model flip tags at runtime — but they are themselves tagged,
  so a task must first be granted `tags` from outside (directive or spawn spec).
  The self-referentiality is deliberate: "the right to change rights" is never
  granted by default. `NaruTagGateTest` pins both halves of this.

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

1. **The system prompt's "Available tools" line is a spawn-time snapshot.**
   `NaruSessionImpl` renders it once when the task is born; `/tools list` (and
   the actual tool schema sent with each request) recompute `findTools()` live.
   So after a mid-session `/tags enable` the prompt line and the schema can
   disagree until the next context rebuild. `/context system` shows the line,
   `/tools list` shows the truth.
2. **Tag changes mid-turn are schema-only.** A tag granted after a request was
   built does not retroactively un-hide tools already sent; it affects the *next*
   request's tool list.
3. **Not persisted** (§5): a restarted session forgets its floor.

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
- **`semantic` and `java` were registered but worn by no tool.** Both tags and
  the semantic tag provider were deleted (§3); the invariant is now a test.
- **The gate treated "no tags" as "always visible".** It is now fail-closed
  behind `NaruTool.isEssential()` (§2), and `file_write`/`run_shell` declare
  the full `{fs, write}` / `{network, exec}` tag sets they actually exercise.

## 11. Where the code lives

| Concern | Location |
|---|---|
| `NaruToolTag`, `DefaultNaruToolTag`, `NaruToolTagProvider`, `NaruToolTags` | `core/naru-api/src/main/java/net/thevpc/naru/api/registry/` |
| `NaruTool.tags()`, `DefaultNaruTool` (tag store), `NaruTool.isEssential()` | `core/naru-api/.../api/registry/` |
| Tag registration + `availableTags`/`findAvailableTag`, bootstrap discovery | `core/naru-impl/.../impl/registry/NaruRegistryImpl.java` |
| Builtin tag vocabulary (`routine`, `ai`, `network`, `write`, `exec`) | `core/naru-impl/.../impl/registry/NaruBuiltinToolTagProvider.java` |
| The gate itself (`findTools`) | `core/naru-impl/.../impl/engine/scheduler/NaruTaskImpl.java` |
| Per-task tag set + grant/revoke/exclusion API | `NaruTaskImpl` (state + methods), `NaruTaskSpec` (spawn seeding) |
| Mode veto | `NaruTaskImpl.findTools()`, `NaruPromptMode.acceptToolTags` |
| `/tags` directive | `extensions/naru-tools-llm/.../NaruTagsDirective.java` |
| `/tools` directive | `extensions/naru-tools-llm/.../NaruToolsDirective.java` |
| `tag_add` / `tag_remove` tools + `tags` tag + toolset | `extensions/naru-tools-tags/` |
| Per-extension tag providers | one `*ToolTagProvider` per feature extension (§3) |
| Behavioural pin | `test/naru-agent-test/.../NaruTagGateTest.java`, `test/naru-agent-test/.../NaruTagRegistryLintTest.java`, `test/naru-agent-test/.../NaruPlanModeToolGateTest.java`, `test/naru-agent-test/.../AgentModelIntegrationTest.java`, `core/naru-impl/src/test/.../TaskSpawnConfigTest.java` |