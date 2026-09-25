# Planning

> **When to use this:** you want the agent to break a large goal into an explicit
> dependency graph before doing any work — a migration, a refactor, a multi-file
> feature — so that the order of work is visible, reviewable and changeable by you
> instead of being improvised turn by turn. The agent writes the plan; **you** decide
> whether it runs.

Planning lives in the optional `naru-tools-plan` extension. Remove that jar from the
classpath and planning disappears; nothing in `naru-api` or `naru-impl` refers to it.

---

## Table of contents

- [1. The model](#1-the-model)
- [2. Quick start](#2-quick-start)
- [3. Item and plan statuses](#3-item-and-plan-statuses)
- [4. Directives](#4-directives)
- [5. Tools](#5-tools)
- [6. Validators](#6-validators)
- [7. Referring to plans and items](#7-referring-to-plans-and-items)
- [8. Activation is human-only](#8-activation-is-human-only)
- [9. Persistence](#9-persistence)
- [10. Worked example](#10-worked-example)
- [11. Not implemented yet](#11-not-implemented-yet)
- [12. Troubleshooting](#12-troubleshooting)

---

## 1. The model

A **plan** is a goal plus a set of **items**. Items form a directed acyclic graph:
an item's dependencies must reach `done` before it becomes runnable.

The division of authority is deliberate:

| Actor | May do |
|---|---|
| **The model** | Create plans, add items, report progress (`running` / `validating` / `blocked`), read plans. |
| **You** | Activate, deactivate, reopen, and delete plans. Decide *whether* work runs. |

The model **cannot** mark an item `done` and **cannot** activate a plan. Completion
passes through the item's validator, and activation has no tool equivalent at all.
This is what stops a model from quietly deciding that its own plan is finished.

Items with every dependency satisfied are `ready`. The rest become ready automatically
as their dependencies finish — you never promote anything by hand.

---

## 2. Quick start

```text
/mode planning
```

In planning mode the agent is read-only and is instructed to build the graph first.
Then, in ordinary conversation:

```text
> I need to migrate this project to Java 21. Plan the work first.
```

The agent calls `plan_create` and replies with the rendered plan:

```text
Plan 81425562 - migrate the build to Java 21
  89187d2c (ready) Survey every module for deprecated APIs
  c6cd99ac (pending) Add the toolchain and release profile  <- after: 89187d2c
  6151692c [model_review] (pending) Port core/naru-api  <- after: c6cd99ac
  154ddeb8 (pending) Port core/naru-impl  <- after: c6cd99ac
  ecb1acc2 (pending) Port every extension  <- after: 6151692c 154ddeb8
  26b23708 [user_approval] (pending) Cut the release  <- after: ecb1acc2
```

Read it. Edit it by asking for a change, or discard it. When you are satisfied:

```text
/plan show
/plan activate 81425562
```

`81425562` is the plan's 8-character prefix — you never need to paste a full UUID.

> **Heads-up:** activation currently only marks the plan active. Nothing dispatches
> items to tasks yet. See [Not implemented yet](#11-not-implemented-yet).

---

## 3. Item and plan statuses

### Item statuses

| Status | Meaning | Set by |
|---|---|---|
| `pending` | Waiting on at least one dependency. | Derived |
| `ready` | All dependencies `done`. Runnable. | Derived |
| `running` | A task is working on it. | Model (`plan_update`) |
| `validating` | Work finished, judge is deciding. | Model (`plan_update`) |
| `blocked` | Cannot proceed. **Always give a reason in `notes`.** | Model (`plan_update`) |
| `done` | Finished and judged acceptable. | The validator only |
| `failed` | Finished and rejected. | The validator only |

`pending` and `ready` are **derived**, never stored as truth. Any change to the
dependency graph or to a dependency's status recomputes them, so the graph cannot
drift out of sync with itself. An item whose dependency points at a deleted item
stays `pending` rather than silently becoming runnable.

### Plan statuses

The plan's status is always derived from its items — it is not stored:

| Status | When |
|---|---|
| `pending` | No items, or every item still waiting on a dependency |
| `active` | At least one item is `ready`, `running` or `validating` |
| `blocked` | Nothing runnable, and at least one item is `blocked` or `failed` |
| `completed` | Every item is `done` |

---

## 4. Directives

All plan management is a single directive, `/plan`. It is a **human** interface —
there is no tool that does any of this.

| Command | Effect |
|---|---|
| `/plan` | Show the active plan, or list all plans if none is active. |
| `/plan show` | Same as `/plan`. |
| `/plan show <id>` | Show one plan by id or unique prefix. |
| `/plan show all` | List every plan with status and progress. |
| `/plan activate <id>` | Make a plan the active one. |
| `/plan deactivate` | Stop treating a plan as active. Progress is kept. |
| `/plan reopen <item-id>` | Reopen one finished item of the active plan. |
| `/plan clear [<id>]` | Delete a plan, or the active plan when no id is given. |
| `/plan help` | Directive help. |

A listing looks like this:

```text
81425562-bc0a-460e-925f-3317f04eeb93 active 0/6 - migrate the build to Java 21
3f9a1c22-7d41-4a0e-b0c8-1e2f3a4b5c6d pending 2/5 - triage the flaky integration suite
```

That is `<plan id> [<active>] <status> <done>/<total> - <goal>`.

### Notes on individual commands

- **`/plan activate`** only *marks* a plan active; it does not cancel or start
  anything by itself. A task already running is never cancelled by `deactivate`.
- **`/plan reopen`** only accepts a **finished** item. Reopening a running or blocked
  item is refused, because that would desynchronise the item's bookkeeping from the
  task doing the work. A reopened item becomes `ready` again immediately; its
  dependents go back to `pending`.
- **`/plan reopen --cascade`** is currently **refused** with an explanation. The
  cascade logic exists in the engine and is covered by tests, but the directive will
  not run it without an explicit confirmation flow, because cascading discards the
  validation of every downstream item.
- **`/plan clear`** matches the plan id **exactly**, unlike `show` and `activate`
  which also accept a unique prefix. This asymmetry is a known wart; copy the full id
  from `/plan show all` when deleting.

---

## 5. Tools

These are model-callable, and all are tagged `plan`. A task only sees them once the
`plan` tag has been granted — see [Activation is human-only](#8-activation-is-human-only).

| Tool | Purpose |
|---|---|
| `plan_create` | Create a plan from a goal and a list of items |
| `plan_update` | Report progress on one item |
| `plan_get` | Read a plan's items, statuses and dependencies |
| `think` | Untagged scratchpad; always available, changes nothing |

### `plan_create`

| Argument | Type | Required | Meaning |
|---|---|---|---|
| `goal` | string | yes | Overall goal |
| `items` | array | yes | The items of the plan |
| `items[].description` | string | yes | What this item must accomplish |
| `items[].key` | string | no | Short name other items reference in `dependsOn` |
| `items[].dependsOn` | string[] | no | Keys of items that must finish first |
| `items[].validator` | enum | no | `none`, `model_review`, `user_approval` |

Give an item a `key` whenever another item must wait for it, and list those keys in
`dependsOn`. A plan is rejected outright if the result would contain a cycle, and a
rejected batch leaves the plan exactly as it was. Keys are remembered for the life of
the plan, so a later batch can still refer to `survey` rather than a UUID.

### `plan_update`

| Argument | Type | Required | Meaning |
|---|---|---|---|
| `item` | string | yes | Item id, or a unique prefix of it |
| `status` | enum | yes | `running`, `validating` or `blocked` |
| `notes` | string | no | Progress notes, or the reason you are blocked |
| `plan_id` | string | no | Defaults to the active plan |

`done` is deliberately absent. Asking for it returns an error explaining that
completion goes through the validator.

### `plan_get`

| Argument | Type | Required | Meaning |
|---|---|---|---|
| `plan_id` | string | no | Defaults to the active plan; `all` lists every plan |

---

## 6. Validators

An item's validator is the gate that decides whether its output counts as done.

| Validator | Meaning |
|---|---|
| `none` | No gate. The work finishing is enough. |
| `model_review` | A model must judge the output before the item is done. |
| `user_approval` | **You** must approve the output before the item is done. |

A gated item shows its validator in brackets in the rendered plan:

```text
  26b23708 [user_approval] (pending) Cut the release  <- after: ecb1acc2
```

Use `user_approval` for anything destructive or externally visible — cutting a
release, deleting data, sending mail. It makes the session stop and wait for you.

---

## 7. Referring to plans and items

Every plan and item has a UUID internally, but everything printed is an 8-character
prefix, and prefixes are accepted anywhere an id is:

```text
/plan show 81425562
/plan activate 81425562
/plan reopen 89187d2c
```

**Ambiguity resolves to nothing, not to a guess.** If a prefix matches more than one
plan or item, the lookup fails with a "no such plan/item" style error rather than
silently picking one. Copy a longer prefix if that happens.

---

## 8. Activation is human-only

The structural plan tools are tagged `plan`, and a task's tags are a permission
*floor* granted per task. A task without the `plan` tag cannot call `plan_create`,
`plan_update` or `plan_get`, so it cannot write to or read the graph through tools.

There is deliberately **no model-callable mode switch**. Activation happens only
through the `/plan activate` directive, so a model holding the `plan` tag can write a
plan and report on it but can never start one. The trade-off: the directive is not
scriptable from inside a routine, by design.

> **Note on visibility:** the tag gates the *tools*, not the prompt. The active plan
> is rendered into the context of every task that reads system sources, whether or not
> it holds the `plan` tag — the extension declares itself relevant to all tasks. So an
> untagged task can still *read* the plan in its context; it simply cannot change it.
> If you need the plan hidden as well as untouchable, that requires making the
> extension relevance conditional on the tag.

```text
/context
```

shows which tags and tools the current task actually has.

---

## 9. Persistence

Plans are per session and survive restarts. They live in the session's own state
file, which the plan extension owns:

```text
<session folder>/ext/plan.tson
```

Nothing else in the session format changes when plans are added, and removing the
extension simply stops reading and writing that one file. A plan that holds no items
at all is still saved; an extension with nothing to persist deletes its file rather
than leaving a stale one behind.

Plans written by the pre-DAG build used a flat step list and are **skipped** on load
rather than misread, so upgrading leaves your session usable.

---

## 10. Worked example

A plan created with a diamond dependency and two gates:

```text
Plan 81425562 - migrate the build to Java 21
  89187d2c (ready) Survey every module for deprecated APIs
  c6cd99ac (pending) Add the toolchain and release profile  <- after: 89187d2c
  6151692c [model_review] (pending) Port core/naru-api  <- after: c6cd99ac
  154ddeb8 (pending) Port core/naru-impl  <- after: c6cd99ac
  ecb1acc2 (pending) Port every extension  <- after: 6151692c 154ddeb8
  26b23708 [user_approval] (pending) Cut the release  <- after: ecb1acc2
```

`Port every extension` waits on **both** core modules — the fan-out. After the first
two items finish, the graph unblocks exactly what it should, `notes` are preserved,
and the plan stays `active`:

```text
Plan 81425562 - migrate the build to Java 21
  89187d2c (done) Survey every module for deprecated APIs -- found 14 call sites, 3 are unreachable
  c6cd99ac (done) Add the toolchain and release profile  <- after: 89187d2c -- maven-toolchains pinned to 21
  6151692c [model_review] (ready) Port core/naru-api  <- after: c6cd99ac
  154ddeb8 (ready) Port core/naru-impl  <- after: c6cd99ac
  ecb1acc2 (blocked) Port every extension  <- after: 6151692c 154ddeb8 -- cannot start until both core modules are ported
  26b23708 [user_approval] (pending) Cut the release  <- after: ecb1acc2
```

Reading the annotations:

- `89187d2c (done) ... -- found 14 call sites` — status, then the recorded note.
- `[model_review]` — the gate that must pass before this counts as done.
- `<- after: 6151692c 154ddeb8` — the two items it waits on.
- `ecb1acc2 (blocked)` — the agent reported it cannot proceed, and said why.

Notice that completing `89187d2c` promoted `c6cd99ac` on its own, and completing that
one promoted two items. There is no "unblock" step to forget.

### Adding items later

The agent can extend an existing plan with more items that depend on existing ones by
key, and the plan keys from its first batch are still in scope. Ask for the addition
in plain language rather than rebuilding the plan.

---

## 11. Not implemented yet

The graph, statuses, validators' *gating rules*, persistence and the full directive
surface are implemented and tested. The **execution loop is not**, yet:

| Missing | Consequence |
|---|---|
| **No dispatcher** | Activating a plan does not spawn a task per ready item. Ready items wait. |
| **Validators are not run** | Nothing calls the gate, so no item reaches `done` on its own. |
| **No retry policy** | `attempts` is tracked but never incremented by anything. |
| **No preemption** | `/plan deactivate` does not stop a running task; cancel the task yourself. |
| **No incremental-add tool** | `addItems` works in the engine and is tested, but no tool exposes it yet — ask the agent to plan again for now. |

So today a plan is a **reviewed, persisted, live-tracked document**: the agent keeps
it up to date through `plan_update`, and you control its lifecycle. Automatic
execution is the next slice.

---

## 12. Troubleshooting

**The plan extension is not installed in this session**
The `naru-tools-plan` jar is missing from the classpath, or default component
registration was disabled for that session. Planning is optional; without the jar, no
plan tools or `/plan` directive exist.

**`/plan` says "no active plan"**
Creating a plan does not activate it — that is a separate, explicit act. Use
`/plan show all` to list what exists, then `/plan activate <id>`.

**"no plan '...'" on activate, but the plan is listed**
Almost always an ambiguous prefix. `/plan show all` prints full ids; copy one.

**The agent says it cannot mark an item done**
Working as intended. It should report `validating` and let the validator decide. If
the item has `validator: none`, the work finishing is what completes it.

**`/plan reopen` refuses my item**
Only *finished* items (`done` or `failed`) can be reopened. Reopen the item that is
actually stuck instead.

**The agent does not see the plan tools**
The task lacks the `plan` tag. Check `/context` for the tag and the tool list. The
plan text itself is still added to the context — only the tools are withheld.
