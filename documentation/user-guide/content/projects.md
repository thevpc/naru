# Projects

> **When to use this:** you want to organize NARU sessions by *what they work on* — a
> repository, a task area, a client — without giving up the fact that every session is
> independent. A project is a grouping label that also carries initial values (a folder,
> a user) for sessions created under it. This page covers the concept, the `/project`
> directives, the embedding API, the storage layout, and the exact calls a UI makes to
> render the two-level Project → Session tree.

Everything below is in `naru-api` (`net.thevpc.naru.api.project`, `...api.store`) with the
default file store in the `naru-store-file` extension and the `/project` directive in the
`naru-projects` extension.

---

## Table of contents

- [1. The idea, in one paragraph](#1-the-idea-in-one-paragraph)
- [2. Glossary — four directories, do not conflate them](#2-glossary--four-directories-do-not-conflate-them)
- [3. Semantics](#3-semantics)
- [4. `/project` directive reference](#4-project-directive-reference)
- [5. Embedding API](#5-embedding-api)
- [6. Storage layout and what is public](#6-storage-layout-and-what-is-public)
- [7. Desktop tree contract](#7-desktop-tree-contract)
- [8. Change notifications](#8-change-notifications)
- [9. Known v1 limits](#9-known-v1-limits)

---

## 1. The idea, in one paragraph

A **project** (`NaruProject`) has an `id`, a `name`, a `folder`, an optional opaque
`user`, timestamps, and a `version` token. It is **semantic, not restrictive**: when a
session is created under a project, the project's `folder` and `user` are *copied* into
the session (to `sessionDir` and the session's `user` label) for fields the caller did
not state. Afterwards the session is fully independent — editing the project never
touches existing sessions, and a session can be moved to another project (or to none) at
any time, including while it is running, because a move changes only
`session.projectId`. Projects are flat (no nesting); a session belongs to at most one
project, and a session with no project is a valid standalone session. The session store
and the project store are dissociated: separate SPIs, separate file layouts, and the
session folders stay flat where they are — never nested under a project.

## 2. Glossary — four directories, do not conflate them

| Term | What it is | Where |
|---|---|---|
| **project** | the grouping entity `NaruProject` | `.naru/local/projects/<id>.tson` |
| **sessionDir** | the directory a session is anchored to: skills, configuration, context files. Copied from the project's `folder` at creation, then owned by the session. Changed explicitly with `/sessiondir`. | `session.tson` key `sessionDir` |
| **workingDir / cwd** | where shell and file tools run | `session.tson` key `workingDir`; `/cd` |
| **session storage folder** | the on-disk folder holding `session.tson`, task files, history | `<workspace>/.naru/{sessions,local/sessions}/<uuid>/` |

The last one is never derived from `sessionDir`: the workspace owns the store; a project
move never moves a directory. (The workspace-level directory that hosts all of this is
`NaruWorkspace.projectDirectory()` — the "project folder" in the older sense kept by
[Projects, navigation and hooks](projects-and-hooks.md).)

## 3. Semantics

**Uniqueness.** `(user, name)` is unique; names compare case-insensitively after trim +
Unicode NFC; the `null` user is its own namespace. A violation raises
`NaruProjectNameConflictException`. Folders are **not** unique — two projects may point
at the same folder.

**Add / edit.** `folder` is required at creation (no implicit default); a folder that
does not exist on disk is a *warning*, not an error (it may be unmounted). Editing
re-checks uniqueness when `name` or `user` changes, requires the caller's `expectedVersion`,
and never changes any session (`NaruProjectConflictException` on a stale version).

**Remove** (`NaruProjectDeletePolicy`):

- `REJECT` (default): fails while any session points at the project, reporting the count
  and up to ten session names/ids. Nothing changes.
- `DETACH`: every session is detached first (`projectId` → null), then the project is
  deleted. The order is deliberate: a crash in between leaves a tolerated state —
  sessions pointing at a still-existing project minus some members, or at a deleted
  project.
- `CASCADE`: refuses outright if any session is **live** (held in memory with running or
  blocked-on-input tasks), otherwise deletes sessions one by one through the session
  store's normal delete path, then the project. A partial failure leaves the project in
  place with the remainder, reported precisely (`NaruRemoveReport`). The caller confirms
  the cascade; the service never prompts.

**Reads tolerate dangling ids.** A session whose `projectId` names a missing project is
returned as-is with `projectMissing = true` (computed by the service, since the session
store never sees projects). This is legal state — a public session committed to git may
reference a project that exists only on its author's machine. Creation is strict; reads
are not.

**Moves.** `moveSession(sessionId, target)` / `moveSessions(...)`: target is a project
id or name, or `none`/null for standalone. Only `projectId` changes — never `sessionDir`,
`user`, `workingDir`, env, tasks, messages, or storage location. Idempotent (already
there → `unchanged`). Optimistic concurrency per session (one retry, then that session
fails alone). A live session is moved through its own in-memory state and persisted on
the engine's checkpoint path, never by rewriting `session.tson` behind its back.

## 4. `/project` directive reference

Provided by the `naru-projects` extension. It manages the **entity** only; the
directory-switching directive is `/sessiondir`. Options before positional arguments,
bash-style.

```
/project list [--user=<u>|--no-user] [--filter=<text>]
/project show <id|name>
/project add <name> --folder=<path> [--user=<u>]
/project edit <id|name> [--name=<n>] [--folder=<path>] [--user=<u>|--no-user]
/project remove <id|name> [--sessions=reject|detach|cascade]
/project move [--to=<id|name|none>] <sessionId|name>...
```

`list` prints id, name, folder, user and session count per row, then a `no project:` line
with the standalone count; a bare `/project` runs `list`. `show` also prints
`version:` — the token `edit`/`remove` need; both fetch it fresh by reference, so typing
them needs no arithmetic. Errors print the exception message; an ambiguous name lists the
candidate ids (`id@user` form). `<id|name>` resolution: exact id first, else the
case-insensitive normalized name among all users.

Session creation takes the same references: `naru --project=<id|name> --user=<u>` at the
app entrypoint, and `.project(...)` / `.user(...)` on the builder (next section). A
missing project is a build-time error; reopening an existing session keeps its stored
values.

## 5. Embedding API

```java
NaruWorkspace agent = NaruWorkspace.of().projectDirectory(root);
NaruProjectService projects = agent.projects();

// create a project (folder required; missing folder = warning, not error)
NaruProjectResult<NaruProject> r = projects.add("backend", NPath.of("/home/me/app"), "alice");
r.warnings().forEach(System.out::println);
NaruProject p = r.value();

// new session seeded from it: folder -> sessionDir, user -> user, copied at creation
NaruSession session = agent.newSession()
        .project("backend")        // by id or name, resolved at build()
        .build();                  // explicit .sessionDir(..)/.user(..) win over the project

// edit (version-checked; never propagates to sessions)
projects.edit(p.id(), p.version(), NaruProjectPatch.of().folder(NPath.of("/home/me/app-v2")));

// the two-level tree: projects with counts…
for (NaruProjectNode node : projects.tree(NaruProjectQuery.first(50)).items()) {
    System.out.println(node.project().name() + " (" + node.sessionCount() + ")");
    // …and the children of one node:
    NaruPage<NaruSessionSummary> kids = projects.sessions(
            new NaruSessionQuery(null, null,
                    NaruProjectFilter.ofId(node.project().id()), NaruUserFilter.any(), 50));
}
// the synthetic nodes of the tree:
long standalone = projects.countStandaloneSessions();
NaruPage<NaruSessionSummary> unknown = projects.findOrphanedSessions(null, 50); // projectMissing=true rows

// move (also legal on a live session; changes only projectId)
projects.moveSession(session.uuid(), NaruProjectService.NO_PROJECT);

// remove
projects.remove("backend", p.version(), NaruProjectDeletePolicy.DETACH);
```

Stores themselves: `NaruProjectStore` (create/find/update/delete/findByQuery, version
tokens, `(user,name)` uniqueness) and the additions to `NaruSessionStore`
(`findByQuery(NaruSessionQuery)` with `projectFilter`/`user`, `countByProject()`,
`reassignProject(...)`, `findSessionIdsByProject(...)`). The session store does not know
projects exist beyond the nullable id; `NaruProjectService` is the only component allowed
to see both sides.

## 6. Storage layout and what is public

```
<workspace>/.naru/
    local/
        projects/<projectId>.tson      ← private by default
        sessions/<uuid>/session.tson   ← private session rows
    sessions/<uuid>/session.tson       ← public (committed) session rows
```

Project rows carry absolute, machine-specific paths and live under `local/`: **do not
commit `local/projects/`**. Session rows gained two optional keys, omitted when null:

```tson
{
  uuid : "…", name : "…", creationDate : …, modificationDate : …,
  model : …, sessionDir : "/home/me/app", workingDir : "/home/me/app",
  projectId : "6b0a…",            // optional, omitted when standalone
  user : "alice",                 // optional, opaque label
  version : 4, env : {…}
}
```

A project file is written atomically (temp + fsync + rename); a corrupt project file is
skipped by listings with a warning and named in a clear error when fetched by id.

## 7. Desktop tree contract

The session list is a two-level tree, **Project → Session**. Tasks are selected elsewhere
and are not part of this tree.

- **Branches**: `projects.tree(NaruProjectQuery.first(limit))` — one page of
  `NaruProjectNode { project, sessionCount }`. Page with `query.after(nextCursor)`;
  cursor is opaque and belongs to its sort.
- **No-project node**: not a stored project. Count: `projects.countStandaloneSessions()`;
  children: `projects.sessions(new NaruSessionQuery(cursor, null, NaruProjectFilter.none(), …))`.
- **Unknown-project node** (optional): `projects.findOrphanedSessions(cursor, limit)` —
  sessions whose `projectId` names no existing project, each with `projectMissing=true`.
  They count as neither a project's children nor as standalone.
- **Expanding any node**: `projects.sessions(new NaruSessionQuery(cursor, null,
  NaruProjectFilter.ofId(id) | none(), NaruUserFilter.any(), limit))` — same call for
  second-level paging under a branch.
- **Drag-and-drop a session** onto a project node:
  `projects.moveSession(sessionId, projectIdOrName)`; onto the no-project node:
  `projects.moveSession(sessionId, NaruProjectService.NO_PROJECT)`. Nothing else in the
  UI has to change: storage, directories and content do not move, so a row can simply be
  re-parented in place — including while the session is running.
- **Keeping the tree fresh**: `projects.addListener(...)` gives `onProjectCreated /
  onProjectUpdated / onProjectRemoved / onSessionMoved(sessionId, from, to)`; session
  creation/death still comes from the session-side lifecycle stream (`NaruSessionListener`
  started/stopped). Combine both: project events re-parent and add/remove branches,
  session events add/remove leaves. There is no transport (HTTP/SSE) in v1 — the tree
  lives in-process next to the engine.

## 8. Change notifications

`NaruProjectListener` is fired by the service **after successful operations, on the
calling thread**; listener exceptions are caught and logged, never propagated, and never
fail the committed write. `moveSessions` fires per session, so a bulk drop yields one
event per re-parented row.

## 9. Known v1 limits

- Listing projects is a **directory scan**, and sorting/paging happens in memory over
  `(sortKey, id)` — fine for the intended dozens of projects.
- Session **counts are an O(all sessions) header pass** per `tree`/`countByProject` call
  (`countByProject()` reads only `session.tson` headers — never task or message content).
  A real index can replace it later without changing the API.
- **Single-process** access to one store directory: per-project in-process locks plus
  version tokens guard one JVM; two processes writing the same `.naru` are not supported.
- No JDBC project store (SPI stub only), no nesting, no authentication/permissions around
  `user`, no model-facing project tools, and no transport for change events.
