# NARU — Nuts AI Reasoning Unit

NARU is a multi-model, tool-based AI agent that works with local language models (via Ollama) to assist with software engineering tasks. It supports single-task execution, interactive REPL sessions, and malleable scripting via named routines.

![Naru in action](documentation/term-cast/naru-install-demo.webp)

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Usage](#usage)
  - [Single Task Mode](#single-task-mode)
  - [Interactive REPL Mode](#interactive-repl-mode)
- [Models](#models)
- [Tools](#tools)
- [Directives Reference](#directives-reference)
- [Sessions](#sessions)
- [Skills](#skills)
- [Agents](#agents)
- [Routines](#routines)
- [Examples](#examples)

---

## Features

- **Single Task Execution** — Run a one-shot task directly from the command line
- **Interactive REPL Mode** — Full command-line interface with history, directives, and scripting
- **Routines** — Create, edit, and execute named reusable scripts stored on the filesystem
- **Multi-Model Support** — Works with Qwen, Llama, DeepSeek, and any Ollama-compatible model
- **Tool Integration** — Rich built-in toolset for file operations, shell execution, compilation, and testing
- **Vision Model Delegation** — Delegate subtasks to vision-capable models via `delegate_to_model`
- **Skills** — Inject domain-specific instructions into the model context on demand
- **Agents** — Markdown files that provide persistent contextual instructions at the classpath, user, project, or folder level
- **Persistent Sessions** — Save and restore the full execution state across interactions

---

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

Pass a task directly as an argument to execute it non-interactively:

```bash
naru -c "Review the current directory as a senior architect"
```

### File Task Mode

Pass a task directly as an argument to execute it non-interactively:

```bash
naru -f my-task-script.naru
```


### Interactive REPL Mode

Launch without arguments to enter the REPL:

```bash
naru
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

# Pull a new model from Ollama
/model install deepseek-2.5

# Check which models are currently loaded
/model ps
```

Recommended models:

| Use Case                   | Model                                  |
|----------------------------|----------------------------------------|
| Code generation & debugging | `qwen3-coder:30b`, `qwen2.5-coder:7b` |
| Vision / image analysis    | `qwen2.5vl:7b`                         |
| Heavy reasoning            | `deepseek-2.5`                         |

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

| Directive | Description |
|-----------|-------------|
| `/model (list\|get\|set\|install\|uninstall\|unload\|ps\|set-global\|alias\|unalias\|update)` | Manage Ollama models |
| `/session (name\|list\|public\|private\|drop\|clear\|load\|reload\|restore\|save\|new\|reset\|copy)` | Manage named sessions |
| `/skills (show\|list\|available\|load\|unload)` | Manage skill modules |
| `/routine (name\|show\|list\|drop\|clear\|load\|unload\|run)` | Manage and execute routines |
| `/history (list\|all\|drop\|clear\|trim)` | Inspect or prune the conversation history |
| `/content (agents\|system\|skills\|user\|classpath\|project\|folder\|all)` | Display the active context content by source |
| `/mode (show\|list\|set)` | View or change the execution mode |
| `/if /elseif /else /end` | Conditional control flow (routines) |
| `/while /for` | Loop control flow (routines) |
| `/set` | Set a variable — e.g. `/set VAR = value` |
| `/system` | Display or modify the system prompt |
| `/go` | Submit the current input buffer to the model |
| `/save` | Save the current session |
| `/restore` | Restore the last saved session |
| `/reset` | Reset the session to a clean state |
| `/new` | Start a new empty session |
| `/reload` | Reload the current session from disk |
| `/tools` | List all available tools |
| `/cat` | Print the content of a file |
| `/ls` | List directory contents |
| `/cd` | Change directory |
| `/pwd` | Print the current directory |
| `/sh` | Run a shell command directly |
| `/buffer` | Inspect the current input buffer |
| `/stats` | Display session statistics (token usage, etc.) |
| `/help` | Show help |
| `/exit` | Exit NARU |

---

## Sessions

A session is the complete execution state of a NARU interaction. It includes:

- **Conversation history** — the full exchange between user and model
- **System history** — system-level messages injected into the context
- **Active model & mode** — which model and execution mode are currently selected
- **Working directory & project directory** — filesystem context
- **Loaded skills** — which skill modules are active
- **Variables** — all values set via `/set`
- **Input buffer** — the current pending input
- **Routine manager state** — the currently loaded routine
- **Metering data** — token usage and cost tracking
- **Global state map** — arbitrary key-value state used by tools and directives

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

A skill is a set of domain-specific instructions that is injected into the model context as an `ACTIVE SKILL DIRECTIVE` system message. Skills give the model specialized knowledge (e.g. Java coding conventions, framework patterns, project-specific rules) without permanently inflating the system prompt.

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

Lines are numbered (conventionally in steps of 10). Each line is one of:

- **Natural language prompt** — forwarded to the active model
- **Directive** — any `/`-prefixed command (`/set`, `/model`, `/skills`, `/history`, `/if`, `/while`, etc.)
- **Comment** — lines starting with `#`

**Variables** are set with `/set VAR = value` and referenced as `$VAR` or `${VAR}`.  
**History references** let you capture model output: `$history[-1].content` is the last model response.

### Example: Automated Bug-Fix Workflow

This routine illustrates a multi-phase agentic loop: a lightweight model identifies problem locations, history is pruned to save tokens, then a heavier reasoning model generates a precise patch.

```
10  /print "=== Starting Agentic Bug-Fix Workflow ==="
20  /set TARGET_FILE = "src/main/java/com/project/core/OrderProcessor.java"
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

## Examples

### Assess a codebase as a senior architect

```bash
naru
> /model set qwen2.5-coder:7b
> Can you assess the current directory as a senior architect?
```

### Delegate an image analysis subtask to a vision model

```bash
> /model set qwen3-coder:30b
> Analyze the project structure and then use delegate_to_model to inspect the architecture diagram at docs/arch.png
```

### Run a saved routine

```bash
naru
> /routine load bug-fix-workflow
> /routine run
```

### Inspect what is currently in context

```bash
> /content all        # Show all context # NARU — Nuts AI Reasoning Unit

NARU is a multi-model, tool-based AI agent that works with local language models (via Ollama) to assist with software engineering tasks. It supports single-task execution, interactive REPL sessions, and malleable scripting via named routines.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Usage](#usage)
  - [Single Task Mode](#single-task-mode)
  - [Interactive REPL Mode](#interactive-repl-mode)
- [Models](#models)
- [Tools](#tools)
- [Directives Reference](#directives-reference)
- [Sessions](#sessions)
- [Skills](#skills)
- [Agents](#agents)
- [Routines](#routines)
- [Examples](#examples)

---

## Features

- **Single Task Execution** — Run a one-shot task directly from the command line
- **Interactive REPL Mode** — Full command-line interface with history, directives, and scripting
- **Routines** — Create, edit, and execute named reusable scripts stored on the filesystem
- **Multi-Model Support** — Works with Qwen, Llama, DeepSeek, and any Ollama-compatible model
- **Tool Integration** — Rich built-in toolset for file operations, shell execution, compilation, and testing
- **Vision Model Delegation** — Delegate subtasks to vision-capable models via `delegate_to_model`
- **Skills** — Inject domain-specific instructions into the model context on demand
- **Agents** — Markdown files that provide persistent contextual instructions at the classpath, user, project, or folder level
- **Persistent Sessions** — Save and restore the full execution state across interactions

---

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

Pass a task directly as an argument to execute it non-interactively:

```bash
naru -c "Review the current directory as a senior architect"
```

### File Task Mode

Pass a task directly as an argument to execute it non-interactively:

```bash
naru -f my-task-script.naru
```


### Interactive REPL Mode

Launch without arguments to enter the REPL:

```bash
naru
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

# Pull a new model from Ollama
/model install deepseek-2.5

# Check which models are currently loaded
/model ps
```

Recommended models:

| Use Case                   | Model                                  |
|----------------------------|----------------------------------------|
| Code generation & debugging | `qwen3-coder:30b`, `qwen2.5-coder:7b` |
| Vision / image analysis    | `qwen2.5vl:7b`                         |
| Heavy reasoning            | `deepseek-2.5`                         |

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

| Directive | Description |
|-----------|-------------|
| `/model (list\|get\|set\|install\|uninstall\|unload\|ps\|set-global\|alias\|unalias\|update)` | Manage Ollama models |
| `/session (name\|list\|public\|private\|drop\|clear\|load\|reload\|restore\|save\|new\|reset\|copy)` | Manage named sessions |
| `/skills (show\|list\|available\|load\|unload)` | Manage skill modules |
| `/routine (name\|show\|list\|drop\|clear\|load\|unload\|run)` | Manage and execute routines |
| `/history (list\|all\|drop\|clear\|trim)` | Inspect or prune the conversation history |
| `/content (agents\|system\|skills\|user\|classpath\|project\|folder\|all)` | Display the active context content by source |
| `/mode (show\|list\|set)` | View or change the execution mode |
| `/if /elseif /else /end` | Conditional control flow (routines) |
| `/while /for` | Loop control flow (routines) |
| `/set` | Set a variable — e.g. `/set VAR = value` |
| `/system` | Display or modify the system prompt |
| `/go` | Submit the current input buffer to the model |
| `/save` | Save the current session |
| `/restore` | Restore the last saved session |
| `/reset` | Reset the session to a clean state |
| `/new` | Start a new empty session |
| `/reload` | Reload the current session from disk |
| `/tools` | List all available tools |
| `/cat` | Print the content of a file |
| `/ls` | List directory contents |
| `/cd` | Change directory |
| `/pwd` | Print the current directory |
| `/sh` | Run a shell command directly |
| `/buffer` | Inspect the current input buffer |
| `/stats` | Display session statistics (token usage, etc.) |
| `/help` | Show help |
| `/exit` | Exit NARU |

---

## Sessions

A session is the complete execution state of a NARU interaction. It includes:

- **Conversation history** — the full exchange between user and model
- **System history** — system-level messages injected into the context
- **Active model & mode** — which model and execution mode are currently selected
- **Working directory & project directory** — filesystem context
- **Loaded skills** — which skill modules are active
- **Variables** — all values set via `/set`
- **Input buffer** — the current pending input
- **Routine manager state** — the currently loaded routine
- **Metering data** — token usage and cost tracking
- **Global state map** — arbitrary key-value state used by tools and directives

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

A skill is a set of domain-specific instructions that is injected into the model context as an `ACTIVE SKILL DIRECTIVE` system message. Skills give the model specialized knowledge (e.g. Java coding conventions, framework patterns, project-specific rules) without permanently inflating the system prompt.

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

Lines are numbered (conventionally in steps of 10). Each line is one of:

- **Natural language prompt** — forwarded to the active model
- **Directive** — any `/`-prefixed command (`/set`, `/model`, `/skills`, `/history`, `/if`, `/while`, etc.)
- **Comment** — lines starting with `#`

**Variables** are set with `/set VAR = value` and referenced as `$VAR` or `${VAR}`.  
**History references** let you capture model output: `$history[-1].content` is the last model response.

### Example: Automated Bug-Fix Workflow

This routine illustrates a multi-phase agentic loop: a lightweight model identifies problem locations, history is pruned to save tokens, then a heavier reasoning model generates a precise patch.

```
10  /print "=== Starting Agentic Bug-Fix Workflow ==="
20  /set TARGET_FILE = "src/main/java/com/project/core/OrderProcessor.java"
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

## Examples

### Assess a codebase as a senior architect

```bash
naru
> /model set qwen2.5-coder:7b
> Can you assess the current directory as a senior architect?
```

### Delegate an image analysis subtask to a vision model

```bash
> /model set qwen3-coder:30b
> Analyze the project structure and then use delegate_to_model to inspect the architecture diagram at docs/arch.png
```

### Run a saved routine

```bash
naru
> /routine load bug-fix-workflow
> /routine run
```

### Inspect what is currently in context

```bash
> /content all        # Show all context sources
> /content agents     # Show only agent file content
> /content skills     # Show only loaded skills
> /stats              # Token usage summary
> /history list       # Show conversation history
```

### Work with sessions across restarts

```bash
# First session
naru
> /model set qwen3-coder:30b
> /skills load java
> /session save refactor-session

# Later, resume exactly where you left off
naru
> /session load refactor-session
```sources
> /content agents     # Show only agent file content
> /content skills     # Show only loaded skills
> /stats              # Token usage summary
> /history list       # Show conversation history
```

### Work with sessions across restarts

```bash
# First session
naru
> /model set qwen3-coder:30b
> /skills load java
> /session save refactor-session

# Later, resume exactly where you left off
naru
> /session load refactor-session
```