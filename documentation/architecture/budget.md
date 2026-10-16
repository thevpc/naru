# Budget

How NARU accounts for token spend: what a `/budget` row means, how a scope (a
"level") and a grouping are chosen, how a price is resolved and when it is fixed,
and the contract every model adapter must honour so the numbers add up. The whole
feature lives in the optional `naru-budget` extension; the core only carries the
usage-reporting seam, so removing the extension stops the accounting without
stopping the engine.

## 1. The one command

`/stats` is gone. Everything it did lives in `/budget`:

- the **report** is the empty-named default subcommand — `/budget` with no words;
- `--explain` adds the request estimate, the bucket shares, the skill attribution
  and the calibration line, strictly opt-in. The default report shows only what a
  provider actually reported, never a guess.

```
/budget                                            report: current session, grouped by provider
/budget --level=task|session|project               scope
/budget --group-by=provider,task-name              flat tuple of label keys
/budget --user=<name|all> --session=<uuid> --provider=<p> --label k=v ...
/budget --task=<id>                                one task and everything it spawned
/budget --since=1h | --from=<ts> --to=<ts>         time bounds
/budget --explain                                  add the estimate and the split
/budget price list | set <provider>[/<model>] input=<x> output=<y> [cache-read=<x>] [cache-write=<y>]
/budget price unset <provider>[/<model>]
/budget price refresh
/budget label set <key>=<value>... | unset <key>... | list | clear
```

## 2. Scope: what a "level" is

A level is a **filter on one shared store**, not a separate store. There is a
single project-level transaction log (see §4); a level narrows it:

| Level | Filter |
|---|---|
| `session` (default) | `session=<current-or-given-uuid>` |
| `project` | none — the whole project's spend |
| `task` | the current task's subtree (see below) |

A transaction is stamped at track time with the built-in labels `provider`,
`model`, `session`, `user`, and for task-scoped calls `task-id`, `task-name` and
`task-path`. `task-path` is the ancestor ids joined by `/`
(`1/4/9`), so a subtree is "any transaction whose `task-path` contains the id as
a component". Keys are never prefixed or renamed, so any label key is a valid
grouping key.

## 3. Grouping

`--group-by` is a flat tuple of label keys, comma-separated
(`--group-by=provider,task-name`). One row per distinct tuple; a key a
transaction does not carry groups under `(none)`. Built-in dimensions
(`provider`, `model`, `fullModel`) fall back to the model a transaction recorded,
so a row is never split merely because a low-level caller built the transaction
without the label. Rows are ordered by cost, descending.

## 4. Prices and cost

### Resolution

A price is keyed either by a bare **provider** (`anthropic`) or by
**provider/model** (`openrouter/anthropic/claude-x` — split on the *first* slash
only). Resolution walks most-specific first: exact `provider/model`, then bare
`provider`, then **nothing** — and nothing is `null`, never a zero price. NARU
must not claim a price the user did not set or explicitly fetch.

### What a price is

Per **1M tokens**: a required input and output price, plus optional cache-read
and cache-write prices that default to the input price. Prices are `BigDecimal`,
parsed from strings and written as strings (a tson number may come back as a
`double`, and money never touches `double`). A price is rejected if it is not a
number, is negative, or exceeds an implausible ceiling (`MAX_REASONABLE`); a
nonzero price below `MIN_REASONABLE` is accepted with a warning, because a cheap
model really can be that cheap.

Provenance is recorded per entry: `user` or `fetched`, with a fetch time and
origin. A **user** entry always wins and is never overwritten by a refresh.

### The cost of a call

```
fresh   = max(0, prompt - cacheRead - cacheWrite)
cost    = (input*fresh + cacheReadPrice*cacheRead + cacheWritePrice*cacheWrite).movePointLeft(6)
        + output*completion.movePointLeft(6)
```

`movePointLeft(6)` is exact (it only shifts the exponent); money is rounded only
when it is printed. `-1` (the provider did not report) and any other negative is
clamped to zero before the arithmetic.

### The price is snapshotted

The price is resolved **once, at track time**, and stored inside the
transaction. `price set` therefore never rewrites the bill of calls already made,
and an unpriced call stays visibly unpriced even after a price appears (it adds no
money and increments `unpricedCalls`). A cache token billed at the input price
because the model has no cache price increments `approximateCalls` and the row is
marked `(approx.)`.

## 5. The adapter normalization contract

All of the above holds only if every adapter normalizes what the provider
reported to one shape before it reaches `NaruSessionUsageListener.onModelCall`:

- `promptTokens` **includes** both cache classes;
- `completionTokens` **includes** reasoning/thinking tokens;
- `cacheWriteTokens` is the count billed at a cache-write rate — `0` when the
  provider has cache accounting but no write class (OpenAI, Gemini), `-1` when it
  reports none;
- `cacheReadTokens` is the discounted read — `-1` when unreported.

The full-rate remainder is always `max(0, prompt - cacheRead - cacheWrite)`. The
contract is documented on the listener and covered per adapter (Anthropic, the
OpenAI-compatible family, Gemini including thoughts, Ollama).

## 6. Permissions

A price or label mutation is an accounting write: it is checked with
`NaruAccessGuard` on the `budget` resource type with the `update` action. The
`naru-permissions` built-ins deny that to an **AGENT** actor; a human acting
directly is allowed, and the report is allowed to both. An agent that can set
prices or labels can rewrite its own bill, so it may read but not mutate.
