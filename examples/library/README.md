# NARU as a Java library

Four runnable examples, from lowest to highest level. Each one shows a job people usually
reach for a different tool to do, done with the same runtime.

| Example | You would otherwise use | What it shows |
| --- | --- | --- |
| [`Ex01LlmBridge`](src/main/java/net/thevpc/naru/examples/Ex01LlmBridge.java) | a provider SDK, LiteLLM | one API in front of every model provider: chat, streaming, multi-turn |
| [`Ex02Chain`](src/main/java/net/thevpc/naru/examples/Ex02Chain.java) | LangChain / LangChain4j | prompt templates, a sequential chain with memory, a Java tool the model calls, results read back in Java |
| [`Ex03Graph`](src/main/java/net/thevpc/naru/examples/Ex03Graph.java) | LangGraph | nodes, shared state, a conditional review cycle, parallel branches with a join |
| [`Ex04TurnServer`](src/main/java/net/thevpc/naru/examples/Ex04TurnServer.java) | a hand-rolled chat backend | an HTTP server that runs one turn per request and keeps each conversation asleep in a store between requests |

## Running them

The examples default to an offline scripted model, so they run with no Ollama and no API key.
From the repository root, build the module and what it depends on once:

```bash
mvn -q install -DskipTests -pl examples/library -am
```

Then, from `examples/library`:

```bash
mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex01LlmBridge
```

Point the same code at a real model with `-Dnaru.model=provider/model`:

```bash
mvn -q exec:java -Dexample=net.thevpc.naru.examples.Ex03Graph -Dnaru.model=ollama/qwen2.5-coder:7b
```

Cloud providers read their key from the environment (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
`GEMINI_API_KEY`, ...): `-Dnaru.model=openai/gpt-4o-mini`.

All four were run against the scripted model and against `ollama/qwen2.5-coder:7b`.

## The pieces

```
NaruWorkspace          the runtime: extensions, providers, settings      NaruWorkspace.of()
 └─ NaruSession        a conversation space: tasks, variables, store     ws.newSession()...build()
     └─ NaruTask       one actor: a script, a conversation history       session.newTask(spec)
         ├─ statements   prompts and /directives, run in order
         ├─ tools        Java methods the model may call
         └─ model        any provider/model, per task
```

- A **prompt line** is sent to the task's model, with its `$variables` expanded, and the
  answer joins the task's history.
- A **directive** (`/set`, `/if`, `/while`, `/call`, `/start`, `/wait`, `/fire`...) is run by
  the engine. `_` holds the last result, and every statement replaces it, so capture an answer
  on the next line: `/set reply = _`.
- **Variables**: `/set x = ...` writes the task env (`task.getTaskEnv("x", false)` in Java),
  `/set --session x = ...` the session env (`session.getSessionEnv("x")`), shared by every task.
- **Tools** are `NaruTool`s grouped in a `NaruToolset`, registered on the session or shipped in a
  jar through `META-INF/services/net.thevpc.nuts.spi.NComponent`.
- **Providers** are `NaruModelProvider`s, found the same way.
  [`ScriptedModelProvider`](src/main/java/net/thevpc/naru/examples/support/ScriptedModelProvider.java)
  is a complete one in about a hundred lines.

## Choosing an entry point

| You want | Use |
| --- | --- |
| a single completion, your own loop | `session.registry().protocol(model, session).get().chat(...)` (Ex01) |
| a scripted pipeline, run to the end | `session.newTask(spec)`, `session.start()`, `task.unhold()`, `task.await(...)` (Ex02, Ex03) |
| a conversation driven by outside events | `ws.newSession().id(id).interactive().buildHandle()`, then `handle.submit(NaruInput.of(text))` per event (Ex04) |

## Things to know

- **Prompt lines name variables.** `$name` and `${name}` are replaced by the variable's value
  when the line runs: routine arguments (`/call classify ticket="..."`), task variables set from
  Java (`NaruTaskSpec.var`), earlier results (`/set status = _`, then `$status`). A name that
  is not a variable stays as written, so `$HOME` in a shell snippet reaches the model intact;
  `$$name` writes a literal `$name`.
- **NARU's expressions are small.** Comparisons, arithmetic and boolean logic work; string
  functions such as `contains` do not exist. Ask the model for a one-word verdict and compare
  it with `==`, as Ex03 does.
- **`NaruWorkspace.of()` also loads the installed `naru-profile-dev`** from the local nuts
  repository, not only what is on your classpath, and fails if none is installed.
- **Activating a handle is slow today** (tens of seconds): resolving the task's model reads the
  live model catalog of every provider over the network.
- **The backend turn API is early.** Ex04 needed fixes in `NaruSessionHandleImpl` to work at all;
  see the project README's TODO list for what is still missing (interrupts, acceptance tests).
