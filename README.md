# NARU — Nuts AI Reasoning Unit

> A durable, actor-based AI agent runtime for the JVM.  
> No Python. No bridge. No subprocess.

[![Install](https://img.shields.io/badge/install-nuts-blue)](https://github.com/thevpc/nuts)
[![License](https://img.shields.io/badge/license-LGPLv3-blue)](https://www.gnu.org/licenses/lgpl-3.0.html)

Most agent frameworks are stateless processes wrapped around an LLM.
**NARU** is a runtime. Sessions survive crashes. Tasks run concurrently.
The agent can rewrite its own script while it runs.

---

## Features
- **Full Interpreter Durability** — NARU persists the *entire* execution state (tasks & stack frames, local/task/session variables, event queues, routine definitions, pending LLM prompts, and scheduler state). Sessions survive crashes, support stop-the-world, and resume exactly where they left off.
- **Dynamic Prompt Pipeline** — The prompt sent to the LLM is built dynamically per call. It intelligently combines history, loaded skills, model-specific formatting (e.g. tool calling instructions), per-task overrides, and folder-specific context (changing directory can inject prompts or auto-run routines).
- **TSON Persistence** — All state uses a lenient, typed JSON extension (TSON) designed for graceful evolution and partial updates.
- **Actor-based multi-tasking** — Concurrent tasks with typed, durable events (retention policies: TTL, once, max-count). Events can be fired before receivers exist.
- **REPL = Script = Routine** — The interactive shell *is* the program. Routines are live, self-modifying, and resilient to changes.
- **NxP Scheduler** — Efficient N-threads / P-tasks model per session. Multiple isolated sessions can run in one process.
- **Model & Tool Flexibility** — Per-task formatting, tool-call emulation, and first-class support for MCP tools (same level as built-ins).
- **Self-modifying agents** — The agent can rewrite its own routines and behavior at runtime.

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

Come back tomorrow. Resume exactly where you stopped.
Each session is an isolated durable unit containing routines, tasks, variables, events, and the full prompt pipeline.

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

## How it compares

|                       | LangChain4j | LangGraph  | AutoGen  | Temporal | **NARU** |
|-----------------------|-------------|------------|----------|----------|------|
| **JVM-native**        | ✅           | ❌          | ❌        | ✅        | ✅    |
| **Durable**           | ❌           | Partial    | ❌        | ✅        | ✅    |
| **LLM-aware**         | ✅           | ✅          | ✅        | ❌        | ✅    |
| **Actor/event model** | ❌           | ❌          | Partial  | ❌        | ✅    |
| **Self-modifying**    | ❌           | ❌          | ❌        | ❌        | ✅    |
| **REPL-is-script**    | ❌           | ❌          | ❌        | ❌        | ✅    |

Temporal has durability but no LLM awareness.
LangChain4j has LLM awareness but no durability.
LangGraph has both but is Python-only with a static graph.
**NARU** is the only JVM runtime that is durable, LLM-native, and dynamic — and it can call LangChain4j when you need it.

---

→ [See the full help](HELP.md) for more examples including databases, IDEs, security tools, and games.
→ [See Examples](examples/README.md) for more examples including databases, IDEs, security tools, and games.

---

## Roadmap

- [ ] Cloud LLM SPIs (Claude, OpenAI, Gemini)
- [ ] Streaming output
- [ ] Embedding SPI + pgvector
- [ ] RAG pipeline

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