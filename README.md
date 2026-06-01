# NARU — Nuts AI Reasoning Unit

> Durable AI agents for the JVM — multi-task, actor-based, 
> self-modifying, built entirely on Nuts.

NARU is a **Java-native**, durable multi-model AI agent and 
orchestration runtime. Unlike most agentic frameworks which are Python-first,
NARU runs entirely on the JVM — no bridge, no subprocess, no
glue code. The REPL **is** the script, and a routine is just a named, persistable snapshot of that live script buffer
Supports single-task execution, interactive REPL, multi-task parallel agents, and self-modifying workflows via named routines.

Currently supports local models via **Ollama**. Cloud LLM support
(Claude, OpenAI, Gemini) is on the roadmap.

![Naru in action](documentation/term-cast/naru-install-demo.webp)

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Usage](#usage)
  - [Single Task Mode](#single-task-mode)
  - [File Task Mode](#file-task-mode)
  - [Interactive REPL Mode](#interactive-repl-mode)
- [Models](#models)
- [Tools](#tools)
- [Directives Reference](#directives-reference)
- [Sessions](#sessions)
- [Skills](#skills)
- [Agents](#agents)
- [Routines](#routines)
- [Java Library](#java-library)
- [Examples](#examples)

---

## Features

- **Java-Native** — Runs entirely on the JVM; no Python dependency, no foreign runtime
- **Durable by Default** — Sessions survive crashes, power cuts, or pauses; resume mid-LLM-call
- **REPL = Script = Routine** — Zero translation layer; what you type interactively is exactly what runs
- **App & Library** — Use as a standalone CLI tool or embed it in your own Java application
- **Multi-Task Actor Model** — Concurrent tasks, typed events, dynamic spawning, independent scheduling
- **Self-Modifying Routines** — Agents can add, replace, or delete lines of their own BASIC-style numbered script at runtime
- **File-Driven Skills & Agents** —  `.md` context files, auto-loaded by scope, private overrides public
- **Tools & Vision Delegation** — Rich built-in toolset + offload image tasks to vision models
- **Persistent Sessions** — Save and restore the full execution state across restarts

---


## 💡 REPL = Script = Routine
NARU eliminates the boundary between interactive exploration and batch execution.
What you type in the REPL is the script. A routine is simply a named, persistable snapshot of that live script buffer.
**How it works:**

🔹Every line you enter (directives, prompts, or numbered edits) lives in the current script buffer
🔹`/routine save` persists it to disk as a `.naru` file
🔹`/routine run` executes it exactly as typed
🔹Numbered lines let you insert, replace, or reorder steps mid-session (BASIC-style)
🔹The agent can modify its own routine while it runs — self-modifying workflows are intentional

**Why it matters:**

🔹🔄 No "interactive vs batch" dichotomy — prototype in REPL, save as routine, run anywhere
🔹🐛 Fully debuggable — every step is a visible, editable line
🔹🧬 Self-modifying by design — routines rewrite themselves based on runtime discoveries
🔹📜 Version-control friendly — plain text files, diffable, shareable, reversible
🔹⚡ Zero cognitive switch — no separate scripting language, config format, or transpiler

## 🤝 How NARU compares
Different frameworks make different tradeoffs. Here is where NARU
sits relative to the most common alternatives.

> 💡 **Note**: This comparison reflects NARU's design goals as of v0.9. Frameworks evolve rapidly — always evaluate based on your specific needs.

|                         | LangChain4j  | LangGraph  | AutoGen  | CrewAI  | Temporal | NARU  |
|-------------------------|--------------|------------|----------|---------|----------|-------|
| **Language**            | Java         | Python     | Python   | Python  | Java/Go  | Java  |
| **Durable**             | ❌            | Partial    | ❌        | ❌       | ✅        | ✅     |
| **Multi-task parallel** | ❌            | Static DAG | Partial  | Partial | ✅        | ✅     |
| **Actor/event model**   | ❌            | ❌          | Partial  | ❌       | ❌        | ✅     |
| **LLM-native**          | ✅            | ✅          | ✅        | ✅       | ❌        | ✅     |
| **Self-modifying**      | ❌            | ❌          | ❌        | ❌       | ❌        | ✅     |
| **REPL-is-script**      | ❌            | ❌          | ❌        | ❌       | ❌        | ✅     |
| **Self-contained**      | ❌            | ❌          | ❌        | ❌       | ❌        | ✅     |
| **MCP support**         | Partial      | Partial    | ❌        | ❌       | ❌        | ✅     |
| **JVM-native**          | ✅            | ❌          | ❌        | ❌       | ✅        | ✅     |

The combination that makes NARU unique is the top-right corner — **durable + LLM-native + JVM**. Temporal has durability but no LLM
awareness. LangChain4j has LLM awareness but no durability. LangGraph has both but is Python-only and uses a static graph.

Two things make NARU's position unique in this table.

**Durability** — most AI agent frameworks are stateless. A crashed
process loses everything. NARU sessions survive restarts, power cuts,
and explicit pauses — resuming mid-routine, mid-loop, mid-LLM-call.
Only Temporal matches this, but Temporal has no LLM awareness.

**Actor-based multi-task** — tasks run concurrently, communicate via
typed events, and are scheduled independently. One task blocked on a
3-minute LLM call never blocks others. Tasks can be spawned,
backgrounded, awaited, and killed at runtime — from within the script
itself. No other AI agent framework models this explicitly. LangGraph
has parallel nodes but they form a static graph defined at design time.
NARU's task topology is dynamic — agents spawn other agents based on
what they discover at runtime.

Together: a durable, dynamic, actor-based AI runtime for the JVM.
That combination exists nowhere else.

## NARU's Design Choices

**Runtime, not library** — NARU manages execution, scheduling, and
communication between agents. You don't call NARU from your code
to do one thing — you run agents inside NARU.

**Durable by design** — Every session survives a crash and resumes
exactly where it left off. Durability is not a feature you opt into —
it's the default.

**Multi-task, actor-based** — A session runs multiple concurrent tasks.
Tasks communicate via typed events, not shared state. One task blocked
on an LLM call never blocks others.

**REPL is the script** — There is no difference between interactive
and scripted execution. Any REPL session is a script. Any script runs
in a REPL. `/if`, `/while`, `/for` work identically in both.

**Self-modifying routines** — A routine can rewrite itself mid-execution.
The LLM can add, replace, or delete lines of the running program while
it runs. This is intentional, not a side effect.

**Uniform expression engine** — The same `${...}` syntax works in
prompts, conditions, paths, configuration, and output formatting.
One mental model for the whole system.

**Self-contained** — Depends only on Nuts, which itself has zero
external dependencies. No Jackson, no OkHttp, no Spring. Optional
terminal enhancement (jline) is loaded at runtime if available.

**REPL is the script** — There is no difference between interactive and scripted execution. 
Any REPL session is a script. Any script runs in a REPL. Numbered lines edit; 
unnumbered lines execute or prompt. The agent reads and rewrites its own instructions at runtime.

### When You Might Choose Something Else
If you need heavy multi-agent simulation with complex negotiation protocols → consider CrewAI or AutoGen.
🔹 If you want deep IDE integration with inline code suggestions → consider Continue, Aider, or Copilot.
🔹 If you're building a Python-first application and want maximum library flexibility → LangChain or LlamaIndex may be a better fit.
🔹 If you need enterprise cloud orchestration with managed scaling → explore cloud provider agent services.

### When NARU Shines
🎯 You're a Java/Kotlin team building internal tooling, build automation, or code maintenance workflows.
🎯 You value reproducibility and auditability — routines and sessions are plain files you can commit, review, and replay.
🎯 You want local execution for privacy, cost control, or offline development.
🎯 You prefer explicit context control over magical abstractions — you decide what the model sees and when.

> 💡 Bottom line: NARU is a focused tool for JVM developers who want practical, scriptable, local-first agentic assistance — without leaving the Java ecosystem or adding foreign runtimes.


## Requirements

- **Ollama** — Local LLM server running at `http://localhost:11434`
- **Java 8+** — Required to run the application
- **Maven** — Required to build from source
- **Nuts** — Universal Java package manager (used to install and run NARU)

---

## Quick Start

**1. Install [Ollama](https://ollama.com/download) and pull the required models:**

```bash
ollama pull qwen2.5-coder:7b
ollama pull qwen2.5vl:7b
```

**2. Install [Nuts](https://github.com/thevpc/nuts):**

```bash
curl -s https://thevpc.net/nuts/install-latest.sh | bash
```

**3. Launch NARU in interactive mode:**

```bash
nuts -y naru
```

---

## Usage

### Single Task Mode

Pass a task directly on the command line to execute it non-interactively:

```bash
nuts -y naru -c "Review the current directory as a senior architect"
```

### File Task Mode

Pass a `.naru` script file to execute it non-interactively:

```bash
nuts -y naru -f my-task-script.naru
```

> 💡 Note: While the REPL uses line numbers for easy interactive editing, .naru files are executed sequentially and do not require line numbers.

### Interactive REPL Mode

Launch without arguments to enter the REPL:

```bash
nuts -y naru
```

In REPL mode, you can type natural language tasks, run directives (prefixed with `/`), or build and execute routines. Any line that is not a directive is forwarded to the active model as a prompt.

---

## Models

NARU supports any model available through Ollama. Use the `/model` directive to manage models at runtime.

```bash
# List available models
/model list

# Switch the active model for this session
/model set qwen2.5-coder:7b

# Set a persistent default model
/model set-global qwen3-coder:30b

# Pull a new model from Ollama (will take a while)
/model install deepseek-2.5

# Check which models are currently loaded on the GPU
/model ps
```

Recommended models:

| Use Case                    | Model                                   |
|-----------------------------|-----------------------------------------|
| Code generation & debugging | `qwen3-coder:30b`, `qwen2.5-coder:7b`  |
| Vision / image analysis     | `qwen2.5vl:7b`                          |
| Heavy reasoning             | `deepseek-2.5`                          |

---

## Tools

Tools are functions the active model can invoke autonomously during a task. Use `/tools` to list all currently available tools.

| Tool | Description |
|------|-------------|
| `cd(work_dir)` | Change the working directory |
| `pwd()` | Print the current working directory |
| `file_read(path, line_start, line_end)` | Read a file, optionally limited to a line range |
| `file_write(path, content)` | Write content to a file (overwrites) |
| `file_append(path, content)` | Append content to a file |
| `file_edit_lines(path, from, to, content, dry)` | Replace a line range in a file; set `dry=true` to preview changes |
| `file_grep(path, content_pattern, regex, case_sensitive, context_lines, max_matches)` | Search for a pattern within a single file |
| `folder_find(path, content_pattern, regex, case_sensitive, context_lines, max_matches, max_files, include_filename, exclude_filename, recursive, modified_after, modified_before)` | Search for files across a directory tree |
| `diff(file1, file2, context_lines)` | Show a diff between two files |
| `run_shell(path, content)` | Execute a shell command in a given directory |
| `maven_compile(project_dir)` | Compile a Maven project |
| `maven_test(project_dir, test_class)` | Run Maven tests, optionally filtering by class name |
| `delegate_to_model(model_name, prompt, image_path)` | Delegate a sub-prompt to another model (e.g. a vision model for image analysis) |
| `routine_run(routine_name)` | Execute a saved routine |
| `routine_list_lines(routine_name, line_start, line_end)` | View lines of a routine |
| `routine_add_line(routine_name, line_number, command)` | Insert a line into a routine |
| `search_web(query)` | Search the web for information |

---

## Directives Reference

Directives are REPL commands prefixed with `/`. They control session state, flow, models, and scripting.

| Directive                                                                                            | Description                                    |
|------------------------------------------------------------------------------------------------------|------------------------------------------------|
| `/model (list\|get\|set\|install\|uninstall\|unload\|ps\|set-global\|alias\|unalias\|update)`        | Manage Ollama models                           |
| `/session (name\|list\|public\|private\|drop\|clear\|load\|reload\|restore\|save\|new\|reset\|copy)` | Manage named sessions                          |
| `/skills (show\|list\|available\|load\|unload)`                                                      | Manage skill modules                           |
| `/routine (name\|show\|list\|drop\|clear\|load\|unload\|run)`                                        | Manage and execute routines                    |
| `/history (list\|all\|drop\|clear\|trim)`                                                            | Inspect or prune the conversation history      |
| `/content (agents\|system\|skills\|user\|classpath\|project\|folder\|all)`                           | Display the active context content by source   |
| `/mode (show\|list\|set)`                                                                            | View or change the execution mode              |
| `/if /elseif /else /end`                                                                             | Conditional control flow (routines)            |
| `/while /for`                                                                                        | Loop control flow (routines)                   |
| `/set`                                                                                               | Set a variable — e.g. `/set VAR = value`       |
| `/system`                                                                                            | Display or modify the system prompt            |
| `/go`                                                                                                | Submit the current input buffer to the model   |
| `/save`                                                                                              | Save the current session                       |
| `/restore`                                                                                           | Restore the last saved session                 |
| `/reset`                                                                                             | Reset the session to a clean state             |
| `/new`                                                                                               | Start a new empty session                      |
| `/reload`                                                                                            | Reload the current session from disk           |
| `/tools`                                                                                             | List all available tools                       |
| `/cat`                                                                                               | Print the content of a file                    |
| `/ls`                                                                                                | List directory contents                        |
| `/cd`                                                                                                | Change directory                               |
| `/pwd`                                                                                               | Print the current directory                    |
| `/sh`                                                                                                | Run a shell command directly                   |
| `/buffer`                                                                                            | Inspect the current input buffer               |
| `/stats`                                                                                             | Display session statistics (token usage, etc.) |
| `/help`                                                                                              | Show help                                      |
| `/exit`                                                                                              | Exit NARU                                      |
| `/source`                                                                                            | inline execution same frame                    |
| `/call`                                                                                              | subroutine new frame                           |
| `/start`                                                                                             | spawn new task                                 |
| `/task`                                                                                              | full task management                           |
| `/emit /on /once`                                                                                    | event system to emit or capture and event      |
| `/task await`                                                                                        | synchronization                                |

---

## Sessions

A session is the complete execution state of a NARU interaction.
Sessions are durable by design. Every task, routine, frame, variable, and event subscription is persisted continuously. 
A session interrupted by a crash, power cut, or explicit pause resumes exactly where it left off — mid-routine, 
mid-loop, mid-LLM-call. There is no difference between save and run.

A session is the complete durable execution environment. It contains:

**Session-level state** — shared across all tasks:
- Routine manager — named routines available to all tasks
- Event bus — inter-task communication
- Scheduler — thread pool and task queue
- Metering data — token usage and cost tracking across all tasks
- Working directory and project directory

**Per-task state** — each task owns independently:
- Conversation history — the full exchange between user and model
- Execution stack — frames, program counter, current routine
- Active model and mode — which LLM and execution mode
- Loaded skills — which skill modules are active
- Variables — scoped to task, readable from parent frames
- Input buffer — pending input in BLOC mode
- Pending input request — readline prompt and delivery state
- Event subscriptions — `/task on` registrations
- Event inbox — delivered but unprocessed events

Sessions are named and stored on the filesystem, so they persist across NARU restarts. A session can be public (shared) or private.

```bash
# Save the current session
/session save my-debug-session

# List all saved sessions
/session list

# Load a previously saved session
/session load my-debug-session

# Create a fresh empty session
/session new

# Copy the current session under a new name
/session copy my-debug-session-backup

# Mark the current session as public (shared) or private
/session public
/session private

# Drop a saved session permanently
/session drop my-debug-session
```

---

## Skills

A skill is a set of domain-specific instructions injected into the model context as an `ACTIVE SKILL DIRECTIVE` system message. Skills give the model specialized knowledge (e.g. Java coding conventions, framework patterns, project-specific rules) without permanently inflating the system prompt.

Skills are managed with the `/skills` directive. Only loaded skills consume context tokens — unloading a skill immediately frees that space.

```bash
# List all available skills
/skills list

# Show currently loaded skills
/skills show

# Load a skill into the active context
/skills load java

# Unload a skill to free context space
/skills unload java
```

Skills can also be loaded and unloaded inside routines to surgically control context at each phase of a workflow:

```
30  /skills load java
...
140 /skills unload java
```

---

## Agents

Agent files are Markdown documents that provide persistent contextual instructions to the model. Unlike skills (which are loaded on demand), agent files are loaded automatically based on their location in the filesystem. This makes them ideal for encoding project conventions, coding standards, or domain background that should always be in scope.

### Agent File Locations

Agent files are resolved in the following order (later entries override earlier ones):

| Scope | Path | Injected as |
|-------|------|-------------|
| Classpath | built into the NARU jar | `AGENT CLASSPATH` |
| User global | `~/.naru/agent/*.md` | `USER LOCAL SPECIFIC CONTEXT` |
| Project root | `<project>/.naru/agent/*.md` | `USER PROJECT SPECIFIC CONTEXT` |
| Project root (local override) | `<project>/.naru/local/agent/*.md` | `USER PROJECT SPECIFIC CONTEXT` |
| Subfolder | `<folder>/.naru/agent/*.md` | `USER FOLDER SPECIFIC CONTEXT` |
| Subfolder (local override) | `<folder>/.naru/local/agent/*.md` | `USER FOLDER SPECIFIC CONTEXT` |

Files within each scope are loaded in alphabetical order. The `.naru/local/` variants are intended for machine-local overrides (e.g. paths, credentials) and should be added to `.gitignore`.

### Agent File Format

An agent file is a plain Markdown file with an optional YAML/TSON front-matter header:

```markdown
---
key: value
---

You are working on a Java 17 project using the Nuts framework.
Always prefer immutable value types.
Never use raw types.
```

The front-matter header is parsed as structured metadata and made available to directives and tools via the environment map.

### Inspecting Loaded Agents

```bash
# Show what agent content is currently active
/content agents

# Show all context sources at once
/content all
```

---

## Routines

Routines are numbered scripts stored on the filesystem and shared across sessions. They support natural language prompts, directives, variables, control flow, and model-switching — making them the primary mechanism for encoding repeatable agentic workflows.

### Managing Routines

```bash
# Load (or create) a routine by name
/routine load my-workflow

# List lines of the loaded routine
/routine list

# Run the loaded routine
/routine run

# Save the current routine
/routine save my-workflow

# Drop (delete) a routine permanently
/routine drop my-workflow
```

### Routine Syntax

Routines use a BASIC-style numbered line format. Each line is one of:

- **Natural language prompt** — forwarded to the active model
- **Directive** — any `/`-prefixed command (`/set`, `/model`, `/skills`, `/history`, `/if`, `/while`, etc.)
- **Comment** — lines starting with `#`

Lines are entered directly in the REPL with a leading number — just like a BASIC interpreter:

```
10 /set FILE = src/main/java/com/example/MyService.java
20 list the public methods of $FILE, one per line, no explanation
```

Entering a numbered line inserts or replaces it in the current routine. Run the routine with `/routine run`.

**Variables** are set with `/set VAR = value` and referenced as `$VAR` or `${VAR}`.  
**History references** let you capture model output: `$history[-1].content` is the last model response.

### Example: Automated Bug-Fix Workflow

This routine illustrates a multi-phase agentic loop: a lightweight model identifies problem locations, history is pruned to save tokens, then a heavier reasoning model generates a precise patch.

```
10  /print "=== Starting Agentic Bug-Fix Workflow ==="
20  /set TARGET_FILE = src/main/java/com/project/core/OrderProcessor.java
30  /skills load java
40  /model set qwen3-coder:7b
50  /print "=== $TARGET_FILE content ==="
60  /cat $TARGET_FILE
70  /print "=== end of file ==="
80  Read this file and list only the line numbers of methods containing potential resource leaks or concurrency bugs.
90  /set BUG_LINES = $history[-1].content
100 /if BUG_LINES == "" goto 210
110 /print "Targeted lines identified: $BUG_LINES"
120 # Prune the large file dump from history to save context tokens
130 /history delete 0..-2
140 /skills unload java
150 # Switch to a heavier reasoning model for patch generation
160 /model set deepseek-2.5
170 You are an expert architect. Given these problematic lines [${BUG_LINES}] in ${TARGET_FILE}, generate a precise patch. To apply it, call the file-writer tool using JSON formatting.
210 /print "Workflow completed successfully."
```

Save and run:

```bash
/routine save bug-fix-workflow
/routine run
```

---

## ⚡ Multi-Tasking & Actor Model

NARU sessions can run multiple concurrent tasks that communicate via typed events. This enables parallel agent workflows, background processing, and dynamic task orchestration.

### Core Directives

| Directive | Description |
|-----------|-------------|
| `/start <task-name>` | Spawn a new concurrent task |
| `/task list` | Show all active tasks in the session |
| `/task await <name>` | Block until the specified task completes |
| `/task kill <name>` | Terminate a running task |
| `/emit <event-type> <payload>` | Broadcast an event to subscribed tasks |
| `/on <event-type> <handler>` | Subscribe to an event type in the current task |
| `/once <event-type> <handler>` | Subscribe to an event, auto-unsubscribe after first trigger |
| `/call <routine>` | Execute a routine in a new stack frame (subroutine) |
| `/source <file>` | Inline execution of a file in the current frame |

### Example: Parallel Code Review

```bash
# Spawn two parallel analysis tasks
/start review-security
/start review-performance

# Wait for both to complete
/task await review-security
/task await review-performance

# Merge results
/print "Security findings: ${tasks['review-security'].result}"
/print "Performance findings: ${tasks['review-performance'].result}"
```

### Event-Driven Coordination

```
# Task A: Emit an event when analysis is done
/routine set routineA
10 /emit analysis-done {"file": "OrderProcessor.java", "issues": 3}

/routine set routineB
# Task B: React to the event
10 /on analysis-done /print "Received findings for ${event.file}"

```

## Java Library

NARU is not only a CLI tool — it is also a Java library you can embed directly in your own applications or extend with custom tools and agents.

To the best of our knowledge, NARU is one of the very few agentic orchestration frameworks written entirely in Java. There is no Python runtime, no subprocess bridge, no FFI glue. If your stack is JVM-based, NARU is a native citizen of it.

### Embedding NARU

Add the repository and dependency to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>thevpc</id>
        <url>https://maven.thevpc.net</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>net.thevpc.naru</groupId>
        <artifactId>naru-impl</artifactId>
        <version>0.8.9.0</version>
    </dependency>
</dependencies>
```

### Extending NARU with Custom Tools

Custom tools are Java classes that implement the `NaruTool` interface and are registered with the NARU registry. The model can then invoke them autonomously the same way it invokes built-in tools — no special prompting required.

This makes it straightforward to expose domain-specific operations (e.g. calling an internal API, querying a database, running a proprietary build system) as first-class tools available to the agent.

### Extending NARU with Custom Agents

Custom agent files (Markdown with optional front-matter) can be bundled on the classpath of your application. NARU loads classpath agents automatically, so your embedded instance can ship with domain knowledge baked in — without requiring users to configure anything manually.

---

## Examples

### Assess a codebase as a senior architect

```bash
nuts -y naru
> /model set qwen2.5-coder:7b
> Can you assess the current directory as a senior architect?
```

### Delegate an image analysis subtask to a vision model

```bash
> /model set qwen3-coder:30b
> Analyze the project structure and then use delegate_to_model to inspect the architecture diagram at docs/arch.png
```

### Build a routine interactively and run it

```bash
nuts -y naru
> /model set qwen2.5-coder:14b
> 10 /set FILE = src/main/java/net/thevpc/naru/api/agent/NaruAgent.java
> 20 list the public methods of $FILE, one per line, no explanation
> /routine run
```

### Run a saved routine

```bash
nuts -y naru
> /routine load bug-fix-workflow
> /routine run
```

### Inspect what is currently in context

```bash
> /content all       # Show all context sources
> /content agents    # Show only agent file content
> /content skills    # Show only loaded skills
> /stats             # Token usage summary
> /history list      # Show conversation history
```

### Work with sessions across restarts

```bash
# First session
nuts -y naru
> /model set qwen2.5-coder:30b
> /skills load java
> /session save refactor-session

# Later, resume exactly where you left off
nuts -y naru
> /session load refactor-session
```

### Example of Model override
When building its context, NARU injects targeted formatting instructions based on the active model selection (using specific files like `qwen2.5-coder@7b.md`, or series-level files like `qwen2.5-coder.md`).

Example:

**File:** `.naru/local/qwen2.5-coder@7b.md`

```markdown
---
emulate_tool_calls: true
tool_call_start: <|tool_call|>
tool_call_end: <|tool_end|>
tool_result_start: <|tool_result_call|>
tool_result_end: <|tool_result_end|>
---

When you need to use a tool, output ONLY this exact format:

<|tool_call|>
{"name": "tool_name", "arguments": {"param": "value"}}
<|end_tool_call|>

Do not wrap in markdown. Do not add explanation before or after.
Wait for the result before continuing.
```


### Example of Hooks
Hooks are special lifecycle routines that execute automatically at designated runtime events. For instance, NARU automatically loads and runs `init.naru` during application startup to configure your default environment.
On startup, naru loads the `init.naru` file.

Example:

file: `.naru/init.naru`
```bash
# current public directives live here
/model set ollama/qwen2.5-coder:14b
/skill load javadoc
```



## 🔮 Roadmap
- [ ] Cloud LLM SPIs (Claude, OpenAI, Gemini)
- [ ] Streaming output
- [ ] Embedding SPI + pgvector support
- [ ] RAG pipeline (chunking, retrieval, injection)
- [ ] Document ingestion (Tika)
- [ ] MCP CLI (zero-lib, testable)
- [ ] Output parsers (JSON → POJO)
