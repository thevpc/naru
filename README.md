# NARU — Nuts AI Reasoning Unit

> A durable, actor-based AI agent runtime for the JVM.

[![Install](https://img.shields.io/badge/install-nuts-blue)]([https://github.com/thevpc/nuts](https://thevpc.github.io/nuts/download.html))
[![License](https://img.shields.io/badge/license-LGPLv3-blue)](https://www.gnu.org/licenses/lgpl-3.0.html)

NARU is a general-purpose Java agent runtime and microkernel-style platform. It is
designed to be embedded as a library/runtime or launched as a complete application.
The Nuts-packaged NARU app is one composition of the runtime and its extensions; its
current tools lean toward software development because that is the use case implemented
so far, not a limit on what the runtime can host.

---

## Features

- **Pluggable persistence** — The runtime depends on the `NaruSessionStore` contract,
  not on a file layout. A file-backed store ships as one implementation; applications
  can provide a store backed by their own database or persistence service. Sessions
  support checkpoint, save, and load through that store boundary.
- **Concurrent task runtime** — Sessions schedule multiple tasks and coordinate them
  with durable events, waits, routines, and task-local state.
- **Scriptable REPL** — Commands, scripts, and routines share the same runtime. Agents
  can update routines while work is active.
- **LLM provider and protocol extensions** — Local and cloud providers include Ollama,
  llama.cpp, OpenAI-compatible services, and provider integrations for OpenAI, Anthropic,
  Gemini, Groq, OpenRouter, Mistral, DeepSeek, Kimi, Qwen, and others. Models can be
  selected per task and streamed output is supported.
- **Tool extensions** — The repository includes filesystem, shell, web, Git, Java
  development, MCP, skills, planning, task, session, routine, budget, indexing, and
  semantic-search integrations.
- **Microkernel-style composition** — Models, directives, tools, session stores, and
  session extensions are discovered through extension points, so an embedding
  application can select its own capabilities and compose a domain-specific agent.
- **Prompt and history controls** — Prompt construction supports task context, skills,
  model-specific formatting, and history compaction. Prompt-context caching is documented
  as an architecture feature.
- **Backend turn API (early)** — An initial `NaruSessionHandle` API can accept inputs,
  publish turn events, and hibernate sessions that are waiting for input. See the required
  TODOs below for behaviors and acceptance tests still needed before relying on it in
  production.

### Project layout

- `core/naru-api` — public APIs, models, task/session interfaces, and store contracts.
- `core/naru-impl` — runtime, scheduler, file-backed session store, and CLI behavior.
- `extensions/` — optional model providers and tool integrations.
- `app/naru` — Nuts package/application entry point.
- `test/` — shared test support and integration tests.

## Where NARU fits

“Harness” is a reasonable description of part of NARU: it connects models, tools,
context, and execution policy. The more useful label is **agent runtime/platform** because
NARU also owns task scheduling, durable sessions, extension lifecycle, and an application
shell. These projects overlap, but they sit at different layers:

| Project | Main role | How it relates to NARU |
| --- | --- | --- |
| [Aider](https://aider.chat/docs/) | Terminal-based AI pair-programming tool | A ready-to-use coding assistant; NARU is a general Java runtime that can host coding or other domain agents. |
| [OpenCode](https://opencode.ai/) | Open-source coding agent available as terminal, desktop, and IDE experiences | Also a coding-agent product. NARU is more focused on an embeddable runtime and a pluggable full application. |
| [LangChain4j](https://docs.langchain4j.dev/) | Java library for LLM integrations, tools, agents, and RAG | Closest in language and library use. LangChain4j supplies a broad Java LLM toolbox; NARU emphasizes a durable task/session runtime and microkernel-style application composition. |
| [LangGraph](https://langchain-ai.github.io/langgraph/) | Stateful agent/workflow orchestration framework, with Python and JavaScript/TypeScript implementations | Closest in orchestration concerns. LangGraph centers on graph-defined workflows; NARU centers on JVM sessions, tasks, routines, and extensions. |

NARU is not a claim that these tools are interchangeable. Aider and OpenCode focus on
the coding-assistant experience; LangChain4j and LangGraph are building blocks for
LLM applications and orchestration. NARU aims to provide both an embeddable runtime and
a complete microkernel-based app, with code assistance as its first substantial domain.

---

## Install
NARU is distributed as a **package inside Nuts** — a fast, lightweight JVM package manager (similar to how `npm` or `cargo` work, but for Java/JVM tools).

![NARU install](documentation/term-cast/demo-install/naru-install-demo.webp)

```bash
# 1. Install Ollama and pull a model
ollama pull qwen2.5-coder:7b

# 2. Install Nuts
curl -s https://thevpc.net/nuts/install-latest.sh | bash

# 3. Run
nuts -y naru
```

---

## The basics

Type naturally. The REPL is the script.

```bash
nuts -y naru
> /model use qwen2.5-coder:7b
> Assess the current directory as a senior architect.
> /session save arch-review
```

Sessions can be saved and loaded later. A session stores runtime state such as routines,
tasks, variables, events, and task history; exact recovery behavior depends on the last
successful checkpoint and the persistence implementation.

```bash
nuts -y naru
> /session load arch-review
```

---

## What it actually looks like

A multi-phase bug-fix routine: a lightweight model locates problems,
history is pruned to save tokens, a heavier model generates the patch,
the agent applies it.

```vb
/use bug-fix
10  /set TARGET = src/main/java/com/project/OrderProcessor.java
20  /model use qwen2.5-coder:7b
30  /cat $TARGET
40  List only the line numbers of methods with potential resource leaks.
50  /set BUG_LINES = $history[-1].content
60  /if BUG_LINES == "" goto 100
70  /history delete 0..-2
80  /model use deepseek-2.5
90  Fix the issues at lines [${BUG_LINES}] in ${TARGET}. Use file_write to apply the patch.
100 /print "Done."
```

Save it. Run it. Let the agent rewrite it at runtime if it needs to.

```bash
/routine save
/call bug-fix
```

Spawn parallel tasks, react to incremental updates, and block only when you need to:

```vb
/start review-security
/start review-progress
/on --event=some-progress --from=child --call=another-task 
/wait --for=event(review-performance)
```

One task blocked on a 3-minute LLM call never blocks the others.

---

→ [See the full help](HELP.md) for more examples including databases, IDEs, security tools, and games.
→ [See Examples](examples/README.md) for more examples including databases, IDEs, security tools, and games.

---

## Documentation

Architecture deep-dives live in [`documentation/architecture/`](documentation/architecture/):

- [NAF utilities: what NARU is built on](documentation/architecture/nuts-utilities.md) — why NARU uses the Nuts Application Framework (NAF) utilities (`NPath`, `NExec`, `NExpr`, `NCp`, ...) instead of hand-rolling general-purpose code, with the authoritative enumeration.
- [Tool tags](documentation/architecture/tags.md) — the capability-tag gate that decides which tools a task may see.
- [Skills](documentation/architecture/skills.md)
- [Prompt context caching](documentation/architecture/prompt-context-caching.md)

---

## TODO

These are curated project-level follow-ups, not a dump of every source comment. The
required list covers completeness and reliability for the backend turn/store APIs; the
nice-to-have list is broader product work.

### Required

- [ ] **Complete the message-store contract.** Enforce append idempotency keys, creation
  range filters, immutable creation ordering, correct keyset cursors, and position-ordered
  history paging across the store API and its implementations. Keep old flat store methods
  compatible. The file-backed store is one adapter, not the reference contract.
- [ ] **Finish turn concurrency semantics.** Implement `INTERRUPT`, define and persist
  original outcomes for idempotent retries, and complete queued-input and disconnect
  behavior.
- [ ] **Finish resource lifecycle behavior.** Make restartable MCP resources reacquire
  lazily on wake and verify that live-resource pin declarations prevent unsafe
  hibernation.
- [ ] **Add backend acceptance coverage.** Test hibernate/wake round trips, multiple
  waiting tasks, pinning, concurrent upserts/submits, event ordering and lag, capacity
  eviction, idempotency, and legacy-session loading.
- [ ] **Prove checkpoint recovery.** Inject failures between checkpoint pieces and verify
  that reloads remain valid, message references never dangle, and file-store compatibility
  is preserved.
- [ ] **Run the file-store tests in a writable test environment.** The current test setup
  needs a configurable temporary Nuts home so it can run without writing to a user-global
  directory.

### Nice to have

- [ ] Add an embedding provider SPI and a pgvector integration.
- [ ] Add a first-party retrieval-augmented generation (RAG) pipeline.
- [ ] Surface detailed provider model-discovery errors in `/model list`.
- [ ] Add end-to-end examples for application-owned stores and backend turn handling.
- [ ] Review model capability metadata and tune per-model context limits as provider
  catalogs evolve.

---

## Citation

```bibtex
@software{bensalah2026naru,
  author       = {Ben Salah, Taha},
  title        = {NARU: A Durable Actor-Based AI Agent Runtime for the JVM},
  year         = {2026},
  publisher    = {GitHub},
  howpublished = {\url{https://github.com/thevpc/naru}}
}
```
