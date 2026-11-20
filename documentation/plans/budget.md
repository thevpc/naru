# Task: unify `/stats` into `/budget` — real prices, labels, levels, filtering, grouping

You are working in the NARU repository (JVM-native durable actor-model AI agent runtime). The work is mostly in `extensions/naru-budget`, with touch points in `core/naru-impl` (task/session plumbing), `core/naru-api` (budget/metering types), `extensions/naru-llm-models` (usage normalization) and docs.

## 0. Ground rules

- **Verify before editing.** File names and line numbers below come from an earlier reading and may be stale. Confirm each claim against the code. If reality differs from this document, say so in your final report and follow the *intent* of the design.
- **Work in the phases of section 6**, in order. Each phase must compile and pass its tests before the next starts.
- **No migration, no compatibility shims.** NARU is early stage. Delete `/stats` outright; no alias, no deprecation period.
- **Never invent prices.** NARU must not claim a price the user did not set or explicitly fetched.
- **Money is `BigDecimal` everywhere.** Never pass money through `double`. Parse prices from strings, accumulate in `BigDecimal`, round only for display.
- **Do not silently resolve ambiguity.** Where this document gives a default for an open point (section 5), implement the default and list it in your final report.

## 1. Terminology (use exactly this)

- **task**: what other systems call an agent or sub-agent. A session starts a main task; a task can `start` a routine as a new (child) task, or `call` a routine inside the same task. Tasks are isolated from each other; the only sharing mechanism is events and spawn config.
- **session**: isolated from other sessions. It starts a task and dies when all its tasks die. `waitFor()` waits for all session tasks.
- **project level**: the global scope. It is the running `NaruWorkspace` (bound to a `projectDirectory`) and everything beneath it. Do **not** call this level "agent": `.md` agent definitions already use that word.
- **label**: a key/value dimension on a transaction. Never "tag" (that word belongs to tool tags).
- Levels: `task < session < project`.

Usage shape (for orientation):

```java
NaruAgent runner = NaruAgent.of();
runner.projectDirectory(NPath.of(projectDir));
runner.newSession().statements(precommands).interactive().build().start().waitFor();
// non-interactive path: same without .interactive(); NCancelException is caught and the process exits
```

The non-interactive path is a short-lived CLI process, so an in-memory-only global store would reset on every run.

## 2. What exists today (verify)

Data path:

```
NaruTaskImpl (after every model call)
  -> session.fireModelCallUsage(model, prompt, eval, cacheWrite, cacheRead, duration)
  -> NaruBudgetExtension.onModelCall(...)
  -> budgetService.trackTransaction(new NaruTokenTransaction(null, null /*sessionId,userId*/, model, prompt, eval, cacheWrite, cacheRead, now, duration))
  -> InMemoryNaruBudgetStore (plain List, per session, never persisted)
  -> NaruAccumulatorBuilder.accumulate(...)  -> NaruModelBudgetStats per model
```

- **Every printed cost is 0**: `accumulate` multiplies by `NAruUnitBudgetSupplier.getInputUnitBudget(labels)`, which defaults to `ZERO` and is never overridden.
- `NaruSpending` is `record(input, output, thinking)`; `thinking` is always 0.
- `cacheWrite`/`cacheRead` use `-1` for "provider does not report". `cacheHitRatio()` is computed but never displayed.
- `NaruTokenTransaction.buildLabels` stamps `session`, `user`, `provider`, `model`, but production passes `sessionId=null, userId=null`, so only provider/model exist. There is no user-defined label mechanism.
- `NaruBudgetStoreQuery` already supports `whereModel/whereUser/whereLabel/whereLabels/wherePeriod`, and the in-memory store honors them, but neither directive uses any of it. Grouping is hardcoded per model.
- `/budget` (`NaruBudgetDirective`) shows rate limits, per-model detail, request-shape estimate, bucket shares, calibration. `/stats` (`NaruStatsDirective`) shows the same per-model query + estimator, plus skill attribution (catalog vs loaded bodies vs other agent). Neither takes flags.
- Directive permissions: central gate in `NaruTaskImpl.invokeDirective` (`NaruResource.directive(name)` + `NaruAction.CALL` via `NaruAccessGuard`), default ALLOW; built-in denies in `NaruPermissionsBuiltins` currently only target agents calling `/permission`.

## 3. Target behaviour

One command: **`/budget`**. `/stats` is deleted; its unique content (request estimate, bucket shares, skill attribution, calibration) becomes the opt-in `--explain` section.

### 3.1 Levels and the store

- **One append path, one shared store, owned by the `NaruWorkspace` (project level).** Sessions and tasks do not own transaction stores. Every model call appends one transaction carrying labels.
- A "level" is a **filter**, not a separate store: task level = `task-id=X` (subtree: see `task-path`), session level = `session=<uuid>`, project level = no filter.
- The store must be thread-safe (appends from actor threads, reads from directive threads) and exposed behind an SPI (file-based default; other backends possible later).
- **Persistence: spend is persisted once, in the project-level store.** Sessions persist only their labels (in task state), never transactions. This deliberately reverses the old "no restored spend" stance in `NaruBudgetExtension`; update that comment and the docs.
    - Append **per transaction** (not on close): cancel paths and short-lived CLI runs must not lose spend.
    - Default location: under the project's `.naru/local/` (private, gitignorable). Confirm against the existing `.naru` layout.
    - Multiple naru processes can share one project directory: use an append-only log with file locking, or one file per process merged on read. A shared mutable file that loses writes is not acceptable.
- Provide a `NaruWorkspace`-level accessor (e.g. `budget()`) usable **before** any session exists, so prices can be configured and loaded from a config file for non-interactive runs.

### 3.2 Unit prices

- Keys: **provider** (`anthropic`) or **provider/model** (`anthropic/claude-sonnet-4`). Split on the **first slash only** (`openrouter/anthropic/claude-x` is provider `openrouter`, model `anthropic/claude-x`). Resolution: exact provider/model beats provider; no match means unpriced.
- Entry fields (all per **1M tokens**, `BigDecimal`): `input` (required), `output` (required), `cache-read` (optional), `cache-write` (optional). **There is no `thinking` price.** Reasoning tokens are billed as output.
- Plus provenance: `source` (`user` | `fetched`), `fetchedAt`, `origin` URL for fetched entries. User entries always win over fetched ones and are never overwritten by refresh.
- Validation on `price set`: numeric; reject values > 1000 (likely per-token mistake) **and warn** on nonzero values < 0.01 (likely per-token mistake in the other direction). Document "per million".
- Prices live at the **project level** (persisted with the project-level config, loadable at `NaruWorkspace` setup). Session/task price overrides are a non-goal for now.
- **Snapshot the resolved price into each transaction at track time** (or `null` = unpriced). Cost is computed from the snapshot, so `price set` and `price refresh` never rewrite the cost of past calls, and an unpriced call stays visibly unpriced.
- Persist price numbers as strings in tson (a number literal may come back as `double`).

### 3.3 Cost math

Clamp first: `cacheRead' = max(0, cacheRead)`, `cacheWrite' = max(0, cacheWrite)` (the `-1` sentinel must never reach arithmetic). Track "provider reported cache data" separately for hit-ratio and approximation flags.

```
fresh = max(0, prompt - cacheRead' - cacheWrite')
cost  = fresh*price.input + cacheRead'*cacheReadPrice + cacheWrite'*cacheWritePrice + completion*price.output
```

- **This only holds if adapters normalize usage** so that `prompt` *includes* cached tokens and `eval` *includes* reasoning tokens. As far as we know this differs per provider: OpenAI `cached_tokens` is a subset of `prompt_tokens`; Anthropic `input_tokens` *excludes* cache read/creation tokens; Gemini reports `thoughtsTokenCount` separately from `candidatesTokenCount` while billing both as output. **Audit every adapter in `naru-llm-models`** (Anthropic, Gemini, OpenAI-compat family, Ollama, custom) and normalize at the adapter boundary. Document the normalization contract in the javadoc of `fireModelCallUsage` and cover it with per-adapter tests.
- If `cache-read`/`cache-write` prices are unset: default to `input`, but flag the affected totals as **approximate** when the provider reported cache usage (the true read price is far below input, the write price above it).

### 3.4 Labels

Built-in labels stamped on every call: `provider`, `model`, `session` (`session.uuid()`), `user` (`session.user().id()`, never null at runtime but apply strip-to-null so a blank id yields no label), `task-id`, `task-name`, `task-path`.

- `task-name` = the name of the **routine that started the task**: the file stem when the routine or session start was loaded from a file. A main task with no file (e.g. built from `statements(...)`) falls back to `main` (or the session name). Use the routine *name*, not its path.
- `task-path` = ancestor task ids joined by `/`, built at spawn time (`<mainId>/<childId>/...`). This enables subtree rollup ("this task and everything it spawned") via a **prefix match**, so `NaruBudgetStoreQuery` needs a label-prefix operator.
- `task-id`, `task-name`, `task-path` are per-task: **not inherited**; each child gets its own.
- **User-defined labels**:
    - Stored in **task state** (persisted; verify the task `toElement()/load()` path round-trips them), not in `ext/budget`.
    - Bound at task creation (spawn config) and, for sessions, at session creation (applied to the main task).
    - `/budget label set k=v ...`, `unset k ...`, `list`, `clear` change the **current task's** labels, affecting model calls from then on and children spawned afterward. Existing children keep the snapshot they got at spawn. The directive runs on the task's own actor thread, so no cross-thread mutation.
    - Inheritance to child tasks goes through the **existing spawn-config inherit/add/revoke policy** (caller-owned, same mechanism as skills/tags/context). Do not invent a second inheritance model. Default for user labels: inherit.
    - User labels never override built-in keys.
- The usage seam must carry the **task** (or its resolved labels), not only the session. Change `fireModelCallUsage` and its call site in `NaruTaskImpl` accordingly.

### 3.5 Command surface

Follow the `/session` / `/model` precedent: one anonymous report subcommand reading `NCmdLine` options, plus named subcommands.

```
/budget                                   report; default scope = current session, group-by provider
/budget --level=task|session|project      scope shorthand (project = no filter)
/budget --group-by=model                  flat tuple key over any label keys, comma-separated (e.g. provider,task-name)
/budget --user=<name|all> --session=<uuid> --provider=<p> --label k=v (repeatable)
/budget --task=<id>                       task + descendants (task-path prefix match)
/budget --since=1h | --from=<ts> --to=<ts>
/budget --explain                         adds estimate, bucket shares, skill attribution, calibration (strictly opt-in)
/budget price list | set <provider>[/model] input=<x> output=<y> [cache-read=<x>] [cache-write=<y>]
/budget price unset <provider>[/model]
/budget price refresh                     explicit fetch, see 3.7
/budget label set|unset|list|clear
```

- `--group-by` is **flat** (one row per distinct tuple), not nested output. Transactions missing a grouped label go into a `(none)` bucket.
- Do not ship flags that cannot work. `--session` / `--user` are meaningful now because the store is shared across sessions.

### 3.6 Report layout

```
provider: anthropic
  calls 42 | in 1.2M | out 84K | cache-hit 73% | cost 3.8421
provider: openai
  calls 7  | in 210K | out 12K | cache-hit n/a | cost 0.5140
-----------------------------------------------
priced total  49 calls | in 1.4M | out 96K | cost 4.3561   (N calls unpriced)
unpriced: ollama/llama3.1:8b (no price for provider 'ollama')
rate limits (unchanged section)
[--explain sections, ported from NaruStatsDirective]
```

- Cache-hit ratio = Σ cacheRead / Σ prompt over **only transactions that reported cache data**; mixed groups show `n/a` or a `partial` marker.
- Cost to 4 decimals; unpriced models flagged; totals labelled "priced total" and show the unpriced call count; approximate costs (3.3) are marked.
- Per-model detail (context %, calls, duration, tokens) remains available under `--group-by=model` or a single group.

### 3.7 `price refresh`

- Explicit command only. **No silent background fetching.** It is a network call: gate it and deny it to AGENT actors like price mutation.
- Structured sources only. Candidates (verify the live format before coding against them): OpenRouter's public models endpoint (per-token prompt/completion/cache prices) and LiteLLM's `model_prices_and_context_window.json`. Do not scrape provider pricing pages.
- Convert per-token to per-1M with `BigDecimal`. Store with `source=fetched`, `fetchedAt`, `origin`. Never overwrite `source=user` entries.
- Model-id matching (e.g. `claude-sonnet-4` vs dated ids vs `anthropic/claude-...`) uses an explicit mapping. Unmatched ids stay unpriced; say so in the output.
- Document that flat per-model prices ignore long-context tiers, batch discounts and 5-minute vs 1-hour cache-write rates, so fetched costs are approximate.

### 3.8 Permissions

- Report path: nothing new (existing directive gate, default ALLOW).
- `price set|unset|refresh` and `label set|unset|clear` are config writes with accounting consequences: check `NaruAccessGuard` with a dedicated resource (name TBD to match the `NaruResource` API) and `NaruAction.UPDATE`.
- Built-in rule in `NaruPermissionsBuiltins`: **AGENT actors may read `/budget` but may not mutate prices or run `label set|unset|clear`**. An agent that can set prices or labels can rewrite its own bill. Labels bound by the *caller* in spawn config are allowed (that is the caller's policy). Default for USER stays ALLOW.

## 4. Non-goals

- No currency handling (plain numbers). No automatic price seeding shipped by default. No session/task-level price overrides. No thinking-token accounting or price. No cross-project rollup. No migration of historical data. No end-of-run budget summary in batch mode (possible follow-up: the "last task died" session-end event is the hook).

## 5. Defaults chosen for still-open points (implement, then report)

1. Level name is `project` (alternative under consideration: `workspace`). Keep it a single constant/enum so a rename is cheap.
2. Per-routine attribution inside a task for `call`ed routines is **not** implemented (a `routine` label from the top frame is a possible later addition). Leave a TODO.
3. Default report scope = current session.
4. Agent-level hook: if the extension SPI has no hook for an owner that outlives sessions, add the minimum core hook and report it, because the global store and price table need that owner.
5. Whether `NaruSpending.thinking` is removed or kept inert: remove it if nothing else consumes it, otherwise leave it and note it.

## 6. Phases

**Phase 0 — audit (no behaviour change).** Report findings on: usage semantics per adapter (3.3); the `fireModelCallUsage` seam and how to get the task into it; extension SPI scope and lifecycle hooks; spawn-config inheritance mechanism and where labels plug in; task state persistence of labels; existing `NCmdLine` option parsing precedent; how a session start from a file names its main task.

**Phase 1 — pricing core.** `NaruUnitPrice`/price table with provenance, first-slash resolution, `BigDecimal` math, validation, tson string persistence; replace the `NAruUnitBudgetSupplier` seam with `priceFor(Labels)`; cache-aware, clamped cost math in `NaruAccumulatorBuilder`; price snapshot in transactions; adapter normalization (3.3).

**Phase 2 — labels and seam.** Task identity labels, `task-path`, user labels in task state, spawn-config inheritance, `label` subcommands, seam change so the task reaches `onModelCall`, stamping of `session`/`user` with strip-to-null.

**Phase 3 — shared project store.** Store SPI, thread-safe file-based default, per-transaction append, multi-process safety, `NaruAgent.budget()` accessor, loading prices at setup, label-prefix query operator.

**Phase 4 — query, report, commands.** Generic grouping in the service (flat tuple key, `(none)` bucket), report per 3.6, option parsing per 3.5, `--explain` ported from `NaruStatsDirective` (including the `PromptStats` pieces), `price list|set|unset|refresh`, delete `NaruStatsDirective` and its registration in `NaruBudgetDirectiveProvider`.

**Phase 5 — permissions.** Resource gating and the built-in AGENT deny (3.8); document in `documentation/architecture/permissions.md`.

**Phase 6 — docs.** `HELP.md`: remove `/stats`, rewrite `/budget`. `documentation/architecture/prompt-context-caching.md` (~line 198): close the "no surface for cache ratios" gap. Document levels, labels, price semantics and the adapter normalization contract.

## 7. Tests (minimum)

- Cost math: fresh/cache split, `-1` clamp, unreported cache, unpriced, approximate flag, `BigDecimal` exactness (no drift on large token counts).
- Per-adapter usage normalization (Anthropic, OpenAI-compat, Gemini incl. thoughts, Ollama).
- Price resolution: provider vs provider/model, first-slash split with `openrouter/anthropic/...`, validation (too large, suspiciously small), fetched vs user precedence.
- Snapshot: `price set` after a call does not change past cost.
- Labels: built-ins stamped, user labels do not override built-ins, per-task labels not inherited, user labels inherit/add/revoke via spawn config, persistence round-trip in task state, `task-path` construction and prefix query.
- Store: concurrent appends, per-transaction persistence survives a simulated cancel/crash, two-process append safety, restore across runs.
- Report: grouping by arbitrary label keys, `(none)` bucket, `--level`, `--task` subtree, period filter, priced-total and unpriced notices, cache-hit partial/`n/a`, `--explain` opt-in.
- Permissions: AGENT denied price/label mutation and `price refresh`, USER allowed, report allowed for both.
- Option parsing errors (unknown flag, bad value) produce a clear message.

## 8. Final report (required)

When finished, report: what was implemented per phase; every deviation from this document and why; the defaults from section 5 and how each was resolved; anything unverified (notably the price-source formats and adapter usage semantics); and follow-ups you noticed but did not do.
