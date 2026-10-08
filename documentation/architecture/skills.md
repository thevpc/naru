# Skills

How NARU's "skills" feature works *today*: what a skill is, where it lives on
disk, how it becomes part of the model's context, how loading/unloading is
decided per task, and what is persisted. This is the baseline for redesigning
the feature — every behaviour described here is implemented, tested, and
intentional unless the "seams" section says otherwise.

## 1. What a skill is

A skill is a **named, plain-markdown block of instructions**, loaded from a
single file:

| Visibility | Path | Intended use |
|---|---|---|
| public | `<project>/.naru/skills/<name>.md` | checked in with the project |
| private | `<project>/.naru/local/skills/<name>.md` | a developer's local override |

Example (`<project>/.naru/skills/git-flow.md`):

```markdown
Follow git-flow strictly.
- features go to feature/<ticket>, never straight to main
- a merge to main is a release: tag it
```

A skill has no front-matter, no parameters, no dependencies and no tools — when
it is *active* its whole file is spliced into the model's context verbatim
(§4). The only shape NARU imposes is the file name.

### Names

- The canonical name is the file name lowercased and kebab-cased:
  `NNameFormat.LOWER_KEBAB_CASE` (`"MySkill"` → `my-skill`, `"git flow"` →
  `git-flow`).
- Resolution is case- and separator-insensitive: `load "Git Flow"`,
  `load "git-flow"` and `load "  GIT-FLOW  "` all resolve the same file.
- A skill name is the file name; there is no id distinct from it.

### Public vs private

- **Private shadows public outright.** If both
  `.naru/skills/javadoc.md` and `.naru/local/skills/javadoc.md` exist, the
  local file *replaces* the checked-in one; the public lines do not leak in
  and the two are never concatenated.
- The resolved skill carries one `NaruVisibility` (`PUBLIC` or `PRIVATE`);
  the older design's `ConflictResolution` enum (`PRIVATE_WINS` / `MERGE`,
  including a `MIXED` visibility) was abandoned — only private-wins remains,
  hard-coded.

## 2. Where the state lives

Two very different things are called "skill state"; keep them apart:

1. **Content** — markdown files on disk (§1). Read on demand; nothing about
   the text is ever copied into the session archive.
2. **Selection** — which skills are *active for which task*. This is
   session-scoped mutable state, kept in memory by the extension and
   persisted per session:

   ```
   .naru/sessions/<uuid>/ext/skills.tson            (public session)
   .naru/local/sessions/<uuid>/ext/skills.tson      (private session)
   ```

   The store hands each session extension its own `ext/<name>.tson` file;
   "skills" is the extension's stable name. The shape is `schemaVersion: 1`
   with a `selection` array:

   ```tson
   {
     schemaVersion: 1,
     selection: [
       { id: 3, loaded: ["git-flow"], masked: [] }
       { id: 7, loaded: ["javadoc"], masked: ["git-flow"] }
     ]
   }
   ```

   `masked` records names a task explicitly *unloaded* (§6). The file is
   rewritten after every persist (the store persists after every statement),
   so `save()` must stay cheap and idempotent. When a task reaches a terminal
   state and leaves the session, the core calls
   `NaruSessionExtension.onTaskDeregistered(session, taskId)` and the extension
   drops that task's `selection` entry — otherwise the file would keep a dead
   entry for every task the session ever ran.

## 3. The architecture: an optional session extension

The whole feature lives in one optional jar, `naru-skills`. **The core has
zero knowledge of it** — `naru-api` and `naru-impl` must not contain the
string `NaruSkill` (a test scans the core tree to enforce this). Remove the
jar from the classpath and the feature disappears: `/skill` stops existing,
`/context skills` reports nothing, no contribution is made.

Two SPI entries in `META-INF/services/net.thevpc.nuts.spi.NComponent` register
it:

| SPI entry | Role |
|---|---|
| `NaruSkillsExtension` | session-scoped feature state and prompt contribution |
| `NaruSkillsDirectiveProvider` | the `/skill` directive (registered in `naru-tools-llm`'s `/context` for the `skills` source) |

### The session-extension contract it rides on

`NaruSessionExtension` gives a feature a session-scoped singleton object that:

- declares the **sources** it needs enabled (`sources()` → `{SKILL}`) and the
  source stamped on its messages (`source()` → `SKILL`);
- **contributes** prompt messages (`contribute(task)`), after a cheap
  `isRelevant(task)` gate (for skills: `!selection.isEmpty()`);
- persists **durable state** as an opaque `NElement` (`save()` / `load()`),
  keyed by `name()` in the store;
- gets **lifecycle** callbacks (`open(session)` binds the manager because the
  SPI instantiates the extension before a session exists, `close()` clears
  state).

The extension owns *everything* the core used to own: which skills exist
(`NaruSkillManagerImpl`, filesystem-backed), which are active per task, how
their text is worded into the prompt, and the persisted selection.

## 4. The runtime flow: how a skill becomes a prompt

Every model request goes through `NaruTaskImpl` context assembly
(`buildModelRequest`-equivalent). The assembled message order is:

1. core system history (`SYSTEM`)
2. the prompt-mode system prompt (`MODE`)
3. **session-extension contributions** — this is where skills land
4. classpath / user-home / workspace / project / folder context files
5. the conversation history (`USER`, from the task's history)

For skills the contribution is one `NaruMessage.user(...)` per active skill
(note: **user role**, not system), formatted exactly as:

```
## ACTIVE SKILL DIRECTIVE: GIT-FLOW
<the file's lines, verbatim>
```

The message is stamped `NaruSource.SKILL` and its `sourceName` is the file
path (or both paths when public+private could both exist — they cannot win
together, but the type supports the set). This lets `/context skills` and
`/stats` attribute it.

Two behaviours fall out of *when* contribution runs:

- **It is evaluated fresh on every call.** There is no snapshot: `/skill
  load` only records the *name* in `selection`; the body is re-read from disk
  each request.
- **A selected skill that no longer exists, or is empty, is silently
  skipped.** Deleting the `.md` after loading doesn't error — the skill just
  stops appearing. `/skill load` itself refuses names that do not resolve to a
  file.

The history records *events*, not content: `/skill load x` appends a user
message "Loaded skill : x", and `/skill unload x` appends "Unloaded skill : x"
(plus a "Back to main." progress note). Nothing about the skill's text enters
the conversation store.

## 5. `/cd`, init hooks, and the folder hierarchy

Two closely related things put skills into play when the working directory
changes. Neither is skill-specific, but together they are how `/cd` can
*recalculate* which skills are active — and they clarify an important
non-feature: skills are **not** resolved per folder.

### What `/cd` actually does

`/cd <dir>` resolves the path and calls `task.setWorkingDir(dir)` — no skill
is consulted. `setWorkingDir` then does two things:

1. **Re-runs the init hooks of the new working directory**
   (`_prependInitHooks`). After the change it re-reads:

   - `<workingDir>/.naru/hooks/init.naru` and
     `<workingDir>/.naru/local/hooks/init.naru` — a flat public/private merge
     of that one directory (private shadows public for the same file name;
     it lists files in those two folders, it does not walk ancestors);
   - additionally the **workspace-level** `init.naru` from the NARU shared
     store (`NStoreKey.ofShared(naru)`) — but only while `workingDir ==
     projectDir`, i.e. at task creation or when cd'ing back to the project
     root.

   The parsed statements are *prepended* to the task's statement queue
   (`prependStatements`), so they execute before the next statement — again
   on every directory change, not just at spawn.

2. **Fires a change event** (`fireChanged`), which persists the task and, in
   the same flush, each session extension's state. The skills extension's
   `save()` writes the selection map. This is persistence, not evaluation:
   nothing about skills is recomputed *here*.

### The only skill-specific effect of `/cd`

Init hooks are ordinary NARU scripts. If a folder's `init.naru` contains
`/skill load <name>` or `/skill unload <name>`, cd'ing into that folder
*re-executes* those statements and the task's inherited selection changes as
a side effect. That is the whole mechanism: skills are re-calculated only to
the extent the target folder's hooks say so — there is no folder-level skill
state, and leaving a folder does not "undo" anything on its own.

### Folder-hierarchy resolution is (still) not a skills feature

Skills are resolved only from the **project directory**:

- `<project>/.naru/skills/<name>.md` and
  `<project>/.naru/local/skills/<name>.md`

There is **no** folder-level `.naru/skills`, no walk from `workingDir` up to
`projectDir`. cd'ing into a subfolder does *not* make its `.naru/skills`
visible. The only "hierarchy" a skill resolves through is the **task parent
chain** (§6), not the filesystem.

The hierarchical load that does exist is for *model/agent context files*
only: `NaruTaskImpl.loadLoadModelAgentInfos` walks from `projectDir` down to
`workingDir`, collecting `.naru/models/<file>.md` at each level (project
first, closest directory last — "specific wins"), and falls back to the bare
project-level `.naru/models` when the working directory leaves the project.
Skills do not participate in that walk. If the redesign wants folder-scoped
skills, this walk is the existing pattern to mirror.

## 6. The selection model

Selection is **per task**: a map `task id → { skill name → TRUE | FALSE }`,
where `FALSE` is an explicit *mask* of an inherited load.

### Read-time resolution with inheritance

`resolve(task)` walks the chain from the task to the root, then applies the
chain **root → task in order**, so the nearest ancestor wins:

- `TRUE` puts the name in the result;
- `FALSE` removes it (masks whatever ancestors had).

Reading state by walking parents instead of copying it at spawn time is what
made the feature movable into an extension — the core had to know nothing
about "hand a child a copy of the parent's set".

The observable guarantees (all covered by
`NaruSkillsExtensionTest`):

| Scenario | Result |
|---|---|
| nothing loaded | nothing active; `isRelevant` false; no contribution |
| `load` on a task | active for that task only (siblings/other roots unaffected) |
| `load` of an unknown/blank name | rejected, no-op |
| `load` twice | idempotent, second call returns false |
| child of a loaded parent | inherits the parent's set |
| child loads more | adds to the inherited set |
| child unloads an inherited skill | masked for that subtree only; parent and the parent's other children keep it |
| child unloads then re-loads | the child's own copy wins; the parent's stays |
| parent loads *after* the child was created | the child sees it (read-time lookup, not spawn-time copy) |
| unload of something never active | false, and a mask is still written (harmless) |

`load`/`unload` normalize the name to canonical form before touching
`selection`, so `load "Git Flow"` and `unload "git-flow"` match (a bug the
core used to have).

### The `/skill` directive surface

| Command | What it does |
|---|---|
| `/skills` or `/skill` (= `list`) | names active for *this* task (nearest-ancestor resolved) + how many are available in total |
| `/skills available` | every skill visible to the session, one row per canonical name, private wins, sorted by name |
| `/skills load <name>` | activate for this task and descendants |
| `/skills unload <name>` | mask for this task and descendants |
| `/skills show <name> [<n1>-<n2>]` | print the body, optionally line-filtered |
| `/context skills [<n1>-<n2>]` | print the *contributed* skill messages currently in the assembled context (from naru-tools-llm) |

`list` shows the *resolved* set; `available` shows what *exists*. There is no
"which available skills are loaded *right now* for this task in one listing"
command — that is `list` (names) or `available` (existence), not both.

## 7. Prompt caching interplay

The caching splitter in `NaruTaskImpl` (`segmentForCaching`) partitions
messages by **source**, not by role: everything that is not
`NaruSource.USER` goes into the stable `system-context` cache segment.

Skill messages carry `SKILL` — so despite being *user-role*, they live in the
stable segment:

| Segment | Contains |
|---|---|
| `tool-defs` | tool definitions |
| `system-context` | system prompt, mode prompt, extension contributions (skills), agent classpath, context files |
| `turn-N` | one completed user turn |

Two redesign-relevant consequences:

- **Changing a skill's file, or loading/unloading a skill, invalidates the
  whole cache prefix** (everything after `system-context`), because the
  segment hash changes. Mid-session skill edits are expensive the same way
  any system-material change is.
- Since the skill body is re-read per request, an *edit to the file* (not a
  load/unload) also changes the hash on the next call.

## 8. Where the code lives

| File | Role |
|---|---|
| `extensions/naru-skills/.../NaruSkill.java`, `NaruSkillImpl.java` | value type: name, visibility, source path, lines |
| `.../NaruSkillManager.java`, `NaruSkillManagerImpl.java` | filesystem resolution, canonical-name matching, public/private shadowing, `available()` |
| `.../NaruSkillsExtension.java` | session extension: selection map, read-time resolve, load/unload/exists, contribute, save/load of `ext/skills.tson` |
| `.../NaruSkillDirective.java` | `/skill` (alias `skills`) subcommands |
| `.../NaruSkillsDirectiveProvider.java` | directive provider registration |
| `extensions/naru-tools-llm/.../NaruContextDirective.java` | `/context skills` (reads contributed `SKILL` messages) |
| `core/naru-impl/.../NaruTaskImpl.java` | the extension-contribution loop, the `system-context` cache segmentation, and `setWorkingDir → _prependInitHooks` (the `/cd` hook re-run) |
| `core/naru-impl/.../NaruSessionImpl.java` | `listOverridablePaths` (public/private hook merge) and `_prependInitHooks` at task creation |
| `core/naru-impl/.../NaruFileSessionStore.java` | `ext/<extension>.tson` persistence |
| `core/naru-api/.../NaruSessionExtension.java` | the contract the whole feature rides on |
| `extensions/naru-skills/.../NaruSkillsExtensionTest.java` | behaviour tests (resolution, selection, contribution, persistence, core-separation) |

Related concepts a redesign must stay consistent with: `NaruSource.SKILL`,
`NaruSessionExtension` (order()-sorted contribution), prompt-mode `MODE`
source, and the `system-context` cache segment.

## 9. Seams — what is worth re-examining when redesigning

These are the current properties most likely to be reconsidered, with the
cost of each as it stands. Not all are "problems"; they are the surface the
feature exposes.

1. **Raw markdown only.** No front-matter, no parameters, no "apply to"
   clause. A skill cannot say who may load it, what models/modes it fits, or
   carry metadata (version, source URL, deprecation). All of that would have
   to live outside the `.md` body today.
2. **Manual, positional activation.** Only an explicit `/skill load` on a
   task activates a skill. There are no triggers (auto-load on folder/mode,
   on-project-open, model-based), no glob rules, no defaults file.
3. **Prompt wording is fixed and minimal.** `## ACTIVE SKILL DIRECTIVE:
   <NAME>` + body, as a **user-role** message placed *before* the context
   files and history. No skill-level governance, no "this is from the system,
   must be obeyed" framing, no per-skill header/usage-guide.
4. **Selection is per task id, persisted per session.** A new session starts
   with nothing active; reload restores per-task ids that still exist, but
   there is no "make `git-flow` active in every session" mechanism.
5. **Silent degradation.** A selected skill whose file was deleted or emptied
   is quietly absent — no warning in the request, no marker in `/skill list`
   (which resolves names against `available()` through `findSkillInfo`; a
   deleted file simply stops being listed). `load` rejects unknown names, but
   nothing validates a *selected* skill still exists.
6. **Content read every request.** Every model call re-reads each active
   skill file from disk. That is how edits apply immediately and how a
   deleted file disappears — but it is also per-request I/O and it makes any
   content edit a prompt-cache bust (§7).
7. **Skills cannot reach tools or state.** The feature only splices text. It
   cannot pin a model, delegate to a routine, or feed the agent's tool call
   machinery.
8. **Existence and activity are two separate listings.** `available` (what
   exists) and `list` (what is resolved-active) do not overlap into one view
   that shows "loaded vs not" per skill.
9. **Naming collision model is private-wins-without-merge.** A local copy
   simply replaces the public one. There is no merge, no way to extend a
   public skill locally, no mixed-visibility mode (removed from the earlier
   design).
10. **History records load/unload events only.** The skill body never enters
    the conversation, which is exactly why selection can live in an extension
    — but it also means replay/audit cannot reconstruct what instructions were
    in effect at a given turn without the files still on disk.
11. **No folder-scoped skills; `/cd` recalculates only via init hooks.**
    Skills resolve strictly from the project directory (§5); a subfolder's
    `.naru/skills` is invisible, and cd'ing into it changes selection only if
    its `.naru/hooks/init.naru` re-loads or unloads skills explicitly.
    Leaving a folder never auto-unloads anything. The `.naru/models`
    hierarchical walk is the existing pattern to copy if folder scope or
    authoritative "on entry / on exit" skill behavior is wanted.

## 10. Design history (for context)

- **Before** the extension extraction (`24935c9`, "extracted skills as an
  extension"), skills were core-owned: `NaruSkill`/`NaruSkillManager` lived
  in `naru-api`, the manager in `naru-impl`, and the core *snapshotted* the
  parent's selection onto the child at spawn time. The core carried a
  `ConflictResolution` enum (private-wins / merge).
- **Now** the feature is an optional extension, selection is resolved at read
  time by walking parents (no spawn-time copy), and the file content is
  re-read per request. The extraction was done precisely so the core could
  stop knowing the feature exists.