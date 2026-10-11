# Session settings and the env chain

> **When to use this:** you want to change how NARU behaves without touching code —
> raise a file-tool cap for one task, lower it for a whole session, or rename a
> project default. Every setting documented here is read through the same chain:
> **task env → session env → agent env → project config → system property**, first
> hit wins.
>
> If you only ever use the built-in defaults, you never need this. The keys exist
> so a limit can be tuned per task, per session, per machine or per project, and
> so a typo cannot silently balloon a preview or freeze a listing.

---

## Table of contents

- [1. The chain: where a value comes from](#1-the-chain-where-a-value-comes-from)
- [2. Validated numbers: a cap you cannot break](#2-validated-numbers-a-cap-you-cannot-break)
- [3. `naru.fs.*`: the file-tool limits](#3-narufs-the-file-tool-limits)
- [4. Examples](#4-examples)

---

## 1. The chain: where a value comes from

A setting is looked up in order, and the **first definition wins**:

| Order | Store | Set by | Example |
|-------|-------|--------|---------|
| 1 | **Task env** | the running task (`/set` task store, variables) | a task scoping its own preview size |
| 2 | **Session env** | `/set`, `session.setEnv(...)` | a cap for the rest of this conversation |
| 3 | **Agent env** | `agent.storeEnv(...)` | a machine-wide default |
| 4 | **Project config** | `/settings` — private (default) in `.naru/local/config/env.tson`, public with `--public` in `.naru/config/env.tson`; private beats public for the same key | a checked-in default the team shares |
| 5 | **System property** | `-Dkey=value` | a one-off override at launch |

Child tasks inherit their parent's env at `start`, so a setting resolved this way
stays stable for the whole task tree unless a task deliberately overrides it.

The chain is the same everywhere it matters: the validated readers use it, and
`NaruTaskConfig.resolve(...)` reports which store supplied a value (its scope and,
for the config store, the visibility) so a "the setting says X and nothing
happened" mystery resolves itself instead of staying a mystery.

## 2. Validated numbers: a cap you cannot break

Size and count settings are read with `NaruTaskConfig.getPositiveInt` /
`getIntInRange`. That matters because `0`, a negative number, and a missing
value each mean something different ("nothing", "infinite", "unset") — trusting
them directly would turn a typo into a balloon of output or a freeze on a
listing.

So the rule is: **a value that is unset, not an integer, or outside the valid
range never reaches the code that truncates or counts** — it falls back to the
built-in default, and a `DEBUG` log line names the offending value:

```text
'naru.fs.read.previewChars' is not an integer in [1, 2147483647] (got 'huge'); using default 8000
```

The caller's default is the contract: a setting can lower or raise a cap, never
break it.

## 3. `naru.fs.*`: the file-tool limits

All keys live under the `naru.fs.*` namespace, one per category, each read
through the chain above:

| Key | Controls | Default |
|-----|----------|---------|
| `naru.fs.read.previewChars` | previews returned by edit/read-side tools | 8000 |
| `naru.fs.read.maxLines` | lines read from a file at once | 2000 |
| `naru.fs.find.contextLines` | context lines around a `find` match | 2 |
| `naru.fs.find.maxMatches` | matches surfaced by one `find` | 50 |
| `naru.fs.find.maxFiles` | files examined by one recursive `find` | 50 |
| `naru.fs.grep.maxTotalMatches` | total matches surfaced by one grep | 100 |
| `naru.fs.grep.maxOutputChars` | characters a grep result may occupy | 15000 |

## 4. Examples

Raise the preview cap for the rest of the session (`/set` writes the session env,
which outranks the config files for every task in the conversation):

```text
/set naru.fs.read.previewChars=16000
```

Ship a team default in the project config so everyone who opens the project gets
it, still overridable per session:

```text
/settings --public naru.fs.grep.maxOutputChars=40000
```

which writes:

```text
# .naru/config/env.tson
{
  "naru.fs.grep.maxOutputChars": 40000
}
```

A per-machine preference goes in the same file privately (the default of
`/settings`, untracked by git) — and outranks the public one:

```text
/settings naru.fs.find.maxFiles=20
# → .naru/local/config/env.tson
```

Ask which store won for a key (a typo'd value falls back to the default and logs
why, so "the setting says X and nothing happened" resolves itself):

```text
/settings naru.fs.read.previewChars
# shows the resolved value and where it came from
```