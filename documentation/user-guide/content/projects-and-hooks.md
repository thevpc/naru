# Projects, navigation and hooks

> **When to use this:** you move between directories or project roots while a session is
> running, and you need to know exactly what that does — which scripts run, which do not,
> and how a session-wide default or a workspace bootstrap belongs in your setup.

This page is about the **navigation** directives and the **init scripts** they trigger.

---

## Table of contents

- [1. Two directives, two meanings](#1-two-directives-two-meanings)
- [2. Init scripts: where they live](#2-init-scripts-where-they-live)
- [3. Hook events](#3-hook-events)
- [4. What runs when](#4-what-runs-when)
- [5. The deprecated init-on-cd behaviour](#5-the-deprecated-init-on-cd-behaviour)
- [6. Session-wide defaults](#6-session-wide-defaults)
- [7. What survives a project change](#7-what-survives-a-project-change)

---

## 1. Two directives, two meanings

| Directive          | Changes        | Side effects |
|--------------------|----------------|--------------|
| `/cd <dir>`        | working dir    | **none** — pure navigation |
| `/project <dir>`   | project root   | re-resolves roots/context files/model defaults, runs the workspace `init.naru` once, fires `project-change` |

`/cd` is the cheap one: it moves where relative paths and folder-scoped context are
resolved, and does nothing else. In particular it **does not run any init script** and
does not change the task's loaded skills or granted tags.

`/project <dir>` is the deliberate one. It changes the session's project root, so every
feature that was rooted at the old project (skill roots, context files, model defaults)
re-resolves against the new one. Per-task selections and granted tag sets are **kept** —
only *availability* changes. A skill a task had loaded that no longer exists under the new
root is reported by `/skill doctor`; it is not silently unloaded.

The directory argument may be absolute or relative; a relative path resolves against the
current project root, not the process working directory.

```
/project /srv/services/billing
```

When the argument is missing or not a directory, the directive reports an error and the
project root is unchanged.

## 2. Init scripts: where they live

An **init script** is a plain NARU script named `init.naru`. On task creation and on
`/project`, the engine looks for it in two places, in this order:

| Scope     | Location |
|-----------|----------|
| shared    | `<shared:naru>/init.naru` (the workspace-level script) |
| project   | `<root>/.naru/hooks/init.naru`, with `<root>/.naru/local/hooks/init.naru` overriding it |

The project copy is read from the **task's working directory** (`/cd` root) at task
creation, and from the **new project root** on `/project`. The shared copy is included
when the working directory is the project root, and is always included on `/project`.

Lines are parsed like any other script and are **prepended** to the task, so an init script
runs before the task's own statements. An absent file is simply nothing.

```
# .naru/hooks/init.naru
/print 'booting the billing workspace'
```

## 3. Hook events

Three event names are the explicit hooks around task and session lifecycles. They are
ordinary events, so a task can observe any of them with `/on`:

| Event            | Fired when | Payload |
|------------------|------------|---------|
| `session-start`  | the session starts serving, once | `projectDir`, `workingDir` |
| `task-spawn`     | a task is created (the historical event is `TaskSpawned`) | resolved spawn sets and provenance |
| `project-change` | the project root changes through `/project` | `oldProjectDir`, `newProjectDir` |

Events **carry no grants**. Observing `task-spawn` or `project-change` never confers a
tag, a tool exclusion or a skill; it only tells a listener that the transition happened.

## 4. What runs when

| Action                        | Runs `init.naru`? | Fires an event? |
|-------------------------------|-------------------|-----------------|
| session starts                | no (no task exists yet) | `session-start` |
| task created                  | yes, once         | `task-spawn` |
| `/cd <dir>`                   | **no**            | none |
| `/project <dir>`              | yes, once         | `project-change` |

The workspace-level `init.naru` is a script, and a script needs a task to carry its
statements. A session has no task at the moment `session-start` fires, so the workspace
bootstrap is delivered at the **first task's creation**, which is the earliest point at
which it can run. `session-start` itself is where a *code* feature installs session-wide
defaults.

## 5. The deprecated init-on-cd behaviour

Before WP7, `/cd` ran the destination directory's init hooks. That coupling of navigation
and execution is gone: `/cd` is pure navigation, and `/project` is the one navigation with
side effects.

If a setup depended on the old behaviour, it can be restored for a migration window. Set
one of these to `true`:

- the system property `naru.initOnCd`
- the session variable `naru.initOnCd` (aliases `session.initOnCd`, `init.onCd`)
- the project configuration key `naru.initOnCd` (same aliases)

When enabled, `/cd` runs the destination hooks again and the session prints a
**one-per-session deprecation warning**. The flag is read once and cached: it is a
migration switch, not a per-command option.

```
/set naru.initOnCd=true      # keep the old behaviour for this session, with a warning
```

**Migration:** move the work that used to happen on `/cd` into one of:

- a `/project` (which already runs the workspace init once), or
- `session-start` / the workspace `init.naru` (session-wide setup), or
- an explicit script invoked where you need it.

## 6. Session-wide defaults

There are two deliberate ways to install a session-wide default, and they cover different
needs.

**As a script** — put it in the workspace init:

```
# <shared:naru>/init.naru
/skill load pdf-reader
```

Every task created afterwards inherits the effect, because the script is prepended to each
of them.

**As code** — a `NaruSessionExtension` does it in
`onSessionStart(NaruSession)`. The `session-start` event is fired alongside that callback,
so a feature can install a default before any task runs.

Named spawn policies are **not** persisted. What is stored is the *resulting* set for each
task (its flat skills and its granted/excluded tags by name); to make a named policy apply
again, re-run the init script that defines it. A missing tag at load time is kept and
warned about, never an error.

## 7. What survives a project change

`/project` re-resolves availability but does not reset state:

| Kept                                  | Re-resolved |
|---------------------------------------|-------------|
| per-task loaded skills                | skill roots |
| granted tags / excluded tools         | context files |
| the session's store binding           | model defaults |
| tasks' working directories            | init hooks (new root) |

"Model defaults" re-resolve (what the new root's config would default a fresh model
selection to); a task's *selected model* is fixed at task creation and `/project` does
not switch it.

Storage follows the session: `/project` does **not** migrate the session to another store.
The session keeps writing to the store it was created with, so a project switch is about
what the session *reads*, not where it *saves*.
