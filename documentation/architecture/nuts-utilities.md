# NAF utilities: what NARU is built on

NARU is a **Nuts Application Framework (NAF)** application. It runs inside a
Nuts workspace, discovers extensions through the Nuts component model, and
reuses the framework's general-purpose plumbing instead of re-implementing it.

## The rule

> NARU **must** use Nuts Application Framework (NAF) utilities whenever the need
> maps to an existing one — `NPath`, `NExec`, `NExpr`, `NCp`, and the rest of the
> catalog below.

Concretely:

- **No hand-rolled general-purpose code.** File I/O, process execution,
  expression parsing, terminal formatting, logging, retries, locking, data
  (de)serialization, time handling: use what NAF already ships. If NARU
  hand-writes one of these, that is a defect, not a feature.
- **NARU types own only NARU domain abstractions.** Tasks, sessions, tags,
  directives, spawn contracts, event filters — these are NARU-specific and
  rightfully live in `net.thevpc.naru.*`. But the general work underneath them
  (reading a file, spawning a process, running an expression, printing a
  message) still goes through NAF utilities.
- **Why.** The Nuts APIs are workspace/session-aware, work identically in the
  REPL and in embedded tests, normalize paths across local/remote and across
  OSes, and keep NARU's own surface small and declarative. Every NARU module
  already runs inside a Nuts workspace, so there is no reason to drop down to
  raw JDK or third-party libraries for these tasks.

### What NARU already does

The migration away from hand-written infrastructure is ongoing; what is already
in place is the template for the rest:

| Area | Mechanism |
|---|---|
| Expressions | Every expression NARU parses, evaluates or templates goes through the Nuts `NExpr` engine via the single `NaruExprs` entry point (`net.thevpc.naru.api.util.NaruExprs`), including the spawn tag expressions in `NaruToolTagExpression`. |
| Files & paths | `NPath`, `NIO`, `NStoreType` (`net.thevpc.nuts.io`, `net.thevpc.nuts.platform`) are used for session stores, working directories and template/catalog reads. |
| Processes | `NExec` (`net.thevpc.nuts.command`) runs external tools (shell, ollama, git ...). |
| Messaging & logging | `NMsg`/`NText` (`net.thevpc.nuts.text`) and `NLog`/`NOut`/`NTrace` for task responses and diagnostics. |
| Optionality | `NOptional` (`net.thevpc.nuts.util`) for every tri-state lookup/parse result. |

## The catalog

The enumeration below is the authoritative list of NAF general-purpose utility
classes for the Nuts version NARU builds against (1.1.x). If a general-purpose
utility is needed and it is **not** clearly resolvable to one of these entries,
stop and enumerate it here before writing anything from scratch. The canonical
walk-through of each family is the NAF tutorial:
[`FWK-TUTORIAL.md`](../../../nuts/documentation/tutorials/FWK-TUTORIAL.md).

### Workspace & session (`net.thevpc.nuts`)

| Type | Role |
|---|---|
| `NWorkspace` | The running Nuts workspace: boots the runtime, owns configuration, lifecycle and boot APIs. |
| `NSession` | The per-context execution facade (paths, repositories, logs, config) every NARU call should flow through. |

### Command line (`net.thevpc.nuts.cmdline`)

| Type | Role |
|---|---|
| `NCmdLine` | Fluent command-line parsing, completion and usage — used by NARU directives (`/cmd` style). |
| `NArg` | One typed command line argument with `-o/--option` and `key=value` handling. |

### Text, messaging, terminal (`net.thevpc.nuts.text`, `net.thevpc.nuts.io`, `net.thevpc.nuts.log`)

| Type | Role |
|---|---|
| `NMsg` | Format-safe, i18n-aware message building; the currency of every NARU log/response line. |
| `NText`, `NTextFormat` | Structured, stylable text trees and the NTF notation renderer behind `NMsg` styling. |
| `NOut`, `NErr`, `NTrace` | Workspace-aware output/error/trace streams (session routing instead of `System.out`). |
| `NLog` | The semantic logger: op names, source, session, levels and structured messages. |

### I/O and filesystem (`net.thevpc.nuts.io`, `net.thevpc.nuts.platform`)

| Type | Role |
|---|---|
| `NPath` | The unified path abstraction: local, `tmp:`, `~`, `tar:`, `zip:`, `http(s):` — never use `java.io.File` for NARU file work. |
| `NIO` | Streams, copy helpers, compression and file operations on top of `NPath`. |
| `NCp` | Progress-aware copying. |
| `NDigest` | Checksums (md5/sha-*) and digest comparisons. |
| `NPs` | Process discovery and lifecycle queries. |
| `NStoreType` | The standard store locations (`CONFIG`, `CACHE`, `VAR`, `TMP`, `LOGS` ...) resolved per workspace/session. |

### Expressions and templates (`net.thevpc.nuts.expr`)

| Type | Role |
|---|---|
| `NExprContextBuilder` | The customizable entry point (`of()`, `declareBuiltins()`, `declareVars(...)`, `declareOperator(...)`, `build()`, `parse(...)`). |
| `NExprContext` | A ready evaluation context; variables, operators, literals, constructs. |
| `NExprNode` | The parsed syntax tree (words, literals, operator/function calls). |
| `NExprTemplate` | Text templating with `{{var}}`/`${expr}` interpolation — NARU template resolution builds on this. |

### Data (`net.thevpc.nuts.elem`, `net.thevpc.nuts.util`)

| Type | Role |
|---|---|
| `NElement` | Format-agnostic structured data (objects/arrays/literals), parsing and multi-format serialization (JSON/JSON5/TSON/XML/YAML...). |
| `NLiteral` | Typed scalar coercion used by option/attribute readers. |
| `NOptional` | The tri-state (present/empty/error) result wrapper for every NARU lookup and parse. |

### Concurrency and resilience (`net.thevpc.nuts.concurrent`, `net.thevpc.nuts.collections`)

| Type | Role |
|---|---|
| `NRetryCall` | Reusable retry policies with backoff and validation callbacks. |
| `NRateLimitValue` | Rate limiting / token bucket for API access. |
| `NWorkBalancer` | Distributing work items across actors/slots. |
| `NCachedValue` | Refreshable, resilient caches. |
| `NLock` | JVM + cross-process locking. |
| `NSagaContext` (+ `NSagaStep`, `NSagaModel`, `NSagaStore`) | Compensating transaction machinery (`call`/`undo`). |
| `NCollectionDiff` | Differences and three-way sync of collections. |

### Processes and network (`net.thevpc.nuts.command`)

| Type | Role |
|---|---|
| `NExec` | Safe, fluent external-process execution (args, env, streaming, timeout, exit policies) — local and SSH sessions. |
| SSH | Remote execution is available through `NExec`'s SSH session support. |

### Artifacts and time (`net.thevpc.nuts.artifact`, `net.thevpc.nuts.util`, `net.thevpc.nuts.time`)

| Type | Role |
|---|---|
| `NId` | The canonical artifact identifier (group/artifact/version...) for Nuts packages. |
| `NRef` | Generic named references (`NRef.ofString` etc.). |
| `NDuration` | Parsable/formatable durations used across NARU scheduling and timeouts. |

## What is *not* in this list

- **Retention policies, event filters/targets, task conditions, spawn tag
  expressions** — NARU-domain semantics. They are *implemented on top of* NAF
  utilities (`NaruExprs` / NExpr for parsing), not reimplementations of NAF.
- **The prompt pipeline, tool registry, task model** — NARU-specific by design.
- Anything NAF already provides that NARU might be tempted to rewrite (an
  object mapper, a YAML reader, a retry loop, a file copier) belongs here
  instead.