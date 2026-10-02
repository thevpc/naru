# Context compaction

> **When to use this:** a conversation has grown until every request carries a large
> transcript of work that no longer matters — you are paying for the same tokens on turn
> forty that you paid on turn two, and the model is re-reading tool output from an hour
> ago. Compaction summarizes the older part of the conversation into one item and keeps
> the recent part verbatim. **Nothing is deleted.**

Compaction lives in the optional `naru-tools-compact` extension. Remove that jar from
the classpath and the feature disappears; the API in `naru-api` remains, because a
session with no compactor installed must be able to say so rather than silently do
nothing.

---

## Table of contents

- [1. Non-destructive by construction](#1-non-destructive-by-construction)
- [2. Quick start](#2-quick-start)
- [3. What "keep" means](#3-what-keep-means)
- [4. Summary levels](#4-summary-levels)
- [5. Configuration](#5-configuration)
- [6. Automatic compaction](#6-automatic-compaction)
- [7. Directives](#7-directives)
- [8. The compaction tool](#8-the-compaction-tool)
- [9. Model selection](#9-model-selection)
- [10. The cache](#10-the-cache)
- [11. Stale summaries](#11-stale-summaries)
- [12. Using it from Java](#12-using-it-from-java)
- [13. Troubleshooting](#13-troubleshooting)

---

## 1. Non-destructive by construction

A summary is an **item in the history**, not a replacement for one:

```
  1  user       "add retry logic to the http client"
  2  assistant  tool_call: http_get(url=...)
  3  tool       "..."
  4  assistant  "here is the retry logic"
  ↓  /compact
  1  user       "add retry logic..."        ← kept verbatim, flagged as covered
  2  assistant  tool_call: http_get(...)     ← kept verbatim, flagged as covered
  3  tool       "..."                        ← kept verbatim, flagged as covered
  4  assistant  "here is the retry logic"
  5  summary    "=== SUMMARY OF EARLIER ===" ← stands in for 1-3 in future requests
  6  user       "now add the same to the grpc client"
```

Items 1–3 stay in the history file exactly as they were, carrying a flag naming the
summary that replaced them. What changes is the **context view**: the list of items a
request is actually built from. A summary substitutes for its covered items; everything
else is sent as-is.

Three consequences worth relying on:

- **`/compact undo` is exact.** Nothing was removed, so undoing clears the flags and the
  previous context view comes back with no need to reconstruct anything.
- **A summary that no longer describes its content is detectable.** Each summary records a
  hash of what it was written from, so an edit to a covered item is noticed rather than
  silently losing the change ([§11](#11-stale-summaries)).
- **History is never shorter than it was.** `/compact status` reports both numbers, and
  the history count only ever grows.

On the wire, a summary is a **user-role message** with the report wrapped in explicit
delimiters. The internal role exists only in history; a provider that receives
`{"role":"summary"}` rejects the request, so the conversion happens in one place before
serialization.

## 2. Quick start

```
/compact                      # summarize older conversation, keep the last 4 turns
/compact preview              # show what it would do, change nothing
/compact status               # what is summarized, what would be saved
/compact undo                 # put the messages back
/compact undo 3f9a…           # undo one specific summary
```

Automatic compaction is **on by default** and needs no setup: when a request would
exceed 80% of the model's context window, the older part is summarized first, and the
request proceeds against the smaller context. Nothing is lost either way.

## 3. What "keep" means

`keep` states how much of the *recent* conversation survives verbatim. Three units:

| Value        | Meaning                                                  |
|--------------|----------------------------------------------------------|
| `4turns`     | keep the last 4 user turns and everything after each     |
| `20items`    | keep the last 20 items                                   |
| `2000tokens` | keep as much of the end as fits in roughly 2000 tokens   |

`lastItems=20`, `items=20` and `20items` are the same thing, as are `lastTurns=4` and
`4turns`. `none` (or `0turns`) means "summarize everything", and `all` means "keep
everything" — the two spellings of doing nothing.

A cut never lands between a tool call and its result. Providers reject a request whose
tool result has no preceding call, and that complaint names nothing about compaction, so
when the requested boundary would split one, the cut moves **earlier** instead: you keep
slightly more than you asked for rather than sending a request that fails.

Turn counting uses the persisted turn-boundary flag, so the same turns are counted after
a restart. A conversation with no boundaries at all — one built by a script — is treated
as a single turn rather than guessed at.

## 4. Summary levels

| Level         | Keeps                                            | Typical use |
|---------------|--------------------------------------------------|-------------|
| `light`       | almost everything, just shorter                  | short sessions where detail matters |
| `normal`      | conclusions, decisions, open questions, file paths | the default |
| `aggressive`  | decisions and state only; reasoning and tool output dropped | long runs where the model only needs to know where it is |

Each level also decides what happens to bulky tool output, because at that level that is
the same decision. The rule: **a failed tool call is worth more per token than a
successful one**, so at `light` failures are kept and successful output is shortened to
its opening. Java callers can override the level's choice per call with
`NaruSummaryOptions.withToolOutputs(...)`; the `/compact` directive does not, because a
subcommand that takes both `level` and a policy would let the call contradict itself.

## 5. Configuration

Every key is `naru.compact.*`, resolved **task → session → project env → JVM system
property → default**, so a project can change the policy without touching global
configuration, and one task can deviate without changing the session.

| Key                          | Default    | Meaning |
|------------------------------|------------|---------|
| `auto`                       | `true`     | compact automatically before a request that would overflow |
| `threshold`                  | `0.80`     | fraction of the context window that triggers it |
| `target`                     | `0.40`     | fraction of the window the summary may occupy |
| `keep`                       | `4turns`   | how much of the end survives verbatim |
| `level`                      | `normal`   | `light` / `normal` / `aggressive` |
| `models`                     | —          | ordered list of summarizer models, e.g. `["fast","good"]` |
| `models.includeCurrent`      | `true`     | allow the task's current model to summarize |
| `maxTokens`                  | derived    | hard cap on the summary; `target` is used when unset |
| `minGap`                     | `6`        | minimum new items between automatic compactions |
| `onStale`                    | `deactivate` | what to do with a summary whose content changed: `deactivate` or `keep` |
| `modelCanCompact`            | `false`    | let the model call the tool itself ([§8](#8-the-compaction-tool)) |
| `cache.enabled`              | `true`     | reuse a summary of identical content |
| `cache.maxEntries`           | `64`       | entries kept in memory and on disk |

Example, in `.naru/config/env.tson` (project default, committed) or
`.naru/local/config/env.tson` (your override, not committed):

```tson
{
  naru.compact.auto   : true,
  naru.compact.keep   : "6turns",
  naru.compact.level  : "aggressive",
  naru.compact.models : ["qwen2.5-coder:1.5b", "qwen2.5-coder:7b"]
}
```

The same key works as a JVM system property (`-Dnaru.compact.level=aggressive`) or as
a session env value set during a session, which is why a project can ship a default and a
developer can override one value without editing the file.

There is no per-user configuration layer, as there is nowhere in NARU for one: settings
that should follow a person belong in `.naru/local/`, and settings that should follow a
task can be written into that task's own env.

## 6. Automatic compaction

Before each model request the extension estimates the context and, if it would cross
`threshold`, summarizes the older part. Three things keep it from being surprising:

- **`minGap` items must have been added since the last compaction.** Otherwise a long
  conversation is summarized again on every turn, paying for a summary each time and
  degrading the record one step per turn.
- **A failure is silent and remembered.** If no model is available or the call fails,
  the request proceeds with the full context — the original problem, but no worse — and
  the reason is reported by `/compact status` rather than as an error mid-request.
- **Stale summaries are reconciled on load**, so a session reopened after an edit does
  not carry a summary describing text that has since changed.

Automatic compaction never applies a tool's own decision: the *user's* configuration
decides whether the model can compact itself.

## 7. Directives

### `/compact run [keep=4turns] [level=aggressive] [focus="..."]`

Summarize now and insert the summary. `focus` steers the summary toward something —
`focus="the API we chose and why"` — which is often worth more than a different level.

### `/compact preview [keep=4turns] [level=aggressive]`

Run exactly the same code path and change nothing. This is the honest dry run: it uses
the same model selection, the same cache and the same prompt as `run`, so what it
predicts is what `run` will do.

### `/compact status`

Active summaries, how many history items they cover, the current context view size in
items and tokens, the outcome and model of the last compaction, any models that were
skipped and why, and the cache's hit rate.

### `/compact undo [id]`

With no argument, the newest active summary; with an id, that one. Clearing the
exclusion flags and retiring the summary is exact, and it leaves the summary in history
marked `UNDONE` rather than deleting it — so the record of what happened survives.

## 8. The compaction tool

`context_compact` lets the model compact its own conversation. Two switches are off by
default: the permission, in the project env —

```tson
{ naru.compact.modelCanCompact : true }
```

— and the toolset, which must be enabled before any of its tools can be offered —
`toolset.<provider>` is the key a toolset provider is configured under:

```tson
{
  naru.compact.modelCanCompact : true,
  toolset.context               : { context : {} }
}
```

Both are off by default for the same reason: an agent that can discard its own context
can also decide to discard it, and a model that has just been squeezed for room is
exactly the kind of component that concludes the squeeze was the problem. Enable them for
tasks that are expected to run long enough to need it — a build loop, a migration — and
leave them off where you want to watch what the model does.

The tool reports tokens saved rather than item counts, because tokens are the currency
the decision is actually made in. It also tells the model that nothing was deleted,
which keeps the tool from being used as a way of avoiding re-reading something: the
summary is only as good as what it replaced.

## 9. Model selection

A summarizer is a model call, so it is a cost, and the wrong one is worse than none.
Candidates are taken from `models`, plus the task's current model when
`models.includeCurrent` is true, and each is checked before use:

- is the provider enabled and reachable,
- is a key configured,
- is the context window known and large enough to be worth it,
- has the provider reported a rate limit that has not yet reset.

The first candidate that passes is used; the rest are recorded as *skipped* with the
reason, and `/compact status` and the tool's response both report them. A compaction that
silently used an expensive model because the cheap one was rate limited is the thing you
need to be able to see.

Budget policy is the one thing left to the caller: nothing here spends against a budget
extension, so if you have one, give compaction its own allowance rather than letting it
compete with the task it is compressing.

## 10. The cache

Summarizing the same content twice is pure waste, so summaries are cached by content:
SHA-256 over every covered item's text plus the output-affecting options.

- **The model is not in the key.** Any model's summary of the same content is as good as
  another's. Which model produced a hit is recorded on the entry.
- **Every item's text is in the key.** This is what makes an edited item a miss rather
  than a stale hit.
- **Concurrent callers make one call.** Two requests missing the same key collapse onto a
  single summarizer call, and both get its result — including when the cache is disabled,
  which is why disabling it saves memory rather than doubling your bill.

Entries live in memory (bounded by `cache.maxEntries`, eldest evicted first) and are
persisted to `.naru/cache/compact.tson` in the project directory, so a restart does not
re-summarize a conversation that has not changed. Unreadable or absent cache files are
not errors; the cache starts empty.

## 11. Stale summaries

Each summary stores a hash of the content it was written from. On load, every active
summary is re-checked against live history. An edited, deleted or externally modified
covered item makes the summary stale, and `onStale` decides what happens:

- `deactivate` (default) — the summary is retired **and the items it covered come back**.
  Both halves matter: retiring the summary while leaving the exclusion flags would drop
  the content from the context view entirely, which is the one outcome non-destructive
  compaction must never produce.
- `keep` — the summary stays in the view, flagged stale, so the model and the user both
  see that it is out of date.

A summary with no recorded hash predates the field and is treated as fresh. Deactivating
every such summary would silently empty the context view of every existing session.

## 12. Using it from Java

The reusable entry point is `NaruCompactors`, and it separates two different questions:
"what should I send?" and "change this task".

```java
// Non-mutating: what would the context become?
NaruCompactionResult preview = NaruCompactors.preview(task, NaruContextSpec.of(
        128_000, NaruWindowSpec.lastTurns(4)));

// Mutating, all-or-nothing. On failure the task is exactly as it was.
NaruCompactionResult applied = NaruCompactors.compact(task, spec);

// Summarize a view you built yourself, without touching the task.
// The task is a handle for the session and models, not the subject.
NaruCompactors.preview(task, myMessages, spec);
```

Results carry the summary item a caller would insert, the covered and summary token
counts, the model used, and any models that were skipped and why. Outcomes are explicit:
`APPLIED`, `PRODUCED`, `NOTHING_TO_COMPACT`, `CACHE_HIT`, `FAILED`. A conversation that
is too short to compact is `NOTHING_TO_COMPACT`, not a failure.

The preview that summarizes an explicit view needs a task because the summarizer runs the
model call through one; a session-only overload exists for compactors that do not, and
says so plainly if it cannot proceed.

## 13. Troubleshooting

**"no summarizer model is available"** — set `naru.compact.models`, or leave
`naru.compact.models.includeCurrent` on and run the task with a model. The message lists
what it tried and why each candidate was rejected.

**Compaction never happens automatically** — the context window may be unknown, or the
model's reported window is large enough that `threshold` is never crossed. `/compact
status` shows both. `minGap` also blocks a second compaction until new items arrive.

**A summary is still there after I edited a covered message** — check `onStale`. With
`keep` the summary is flagged rather than retired; with `deactivate` the covered items
come back into the context view. Either way the edited text is never dropped.

**The provider says the tool result has no call** — a cut landed inside a tool exchange.
This should not happen with this version; if it does, the window value is the thing to
report along with the transcript, because the correction is driven entirely by the
requested boundary.

**The cache is empty after a restart** — the project directory changed, or the cache file
could not be written. `/compact status` reports the file's location and whether there is
anything to save.