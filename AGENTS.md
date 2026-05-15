# NARU — Nuts AI Reasoning Unit

NARU is a multi-model, tool-based AI agent designed to work with local language models (via Ollama) for software engineering tasks. It's built on the Nuts framework and operates in a project directory, using available tools to understand, analyze, and modify code.

## Project Structure

```
core/
├── naru-api/              # API interfaces
│   ├── src/main/java/net/thevpc/naru/api/
│   │   ├── agent/
│   │   │   ├── NaruAgentConfig.java
│   │   │   ├── NaruAgentContext.java
│   │   │   ├── NaruAgentRunner.java
│   │   │   ├── NaruScript.java
│   │   │   ├── NaruScriptManager.java
│   │   │   └── NaruSessionContext.java
│   │   ├── model/
│   │   │   ├── NaruMessage.java
│   │   │   ├── NaruModelProvider.java
│   │   │   ├── NaruResponse.java
│   │   │   ├── NaruToolCall.java
│   │   │   └── NaruToolDefinition.java
│   │   └── tool/
│   │       ├── NaruTool.java
│   │       ├── NaruToolCallContext.java
│   │       ├── NaruToolParameter.java
│   │       └── NaruToolRegistry.java
├── narul-impl/            # Implementation
│   ├── src/main/java/net/thevpc/naru/impl/
│   │   ├── agent/
│   │   │   ├── NaruAgentRunnerImpl.java
│   │   │   ├── NaruAgentContextImpl.java
│   │   │   ├── NaruSessionContextImpl.java
│   │   │   └── script/
│   │   │       ├── NaruScriptImpl.java
│   │   │       └── NaruScriptManagerImpl.java
│   │   ├── tool/
│   │   │   ├── NaruToolRegistryImpl.java
│   │   │   ├── ListFilesTool.java
│   │   │   ├── ReadFileTool.java
│   │   │   ├── WriteFileTool.java
│   │   │   ├── RunShellTool.java
│   │   │   ├── MavenCompileTool.java
│   │   │   ├── MavenTestTool.java
│   │   │   ├── RunScriptTool.java
│   │   │   └── WriteScriptLineTool.java
│   │   ├── model/
│   │   │   └── OllamaProviderNaru.java
│   │   └── cmd/
│   │       └── NaruCommand.java
└── app/
    ├── pom.xml
    └── src/main/java/net/thevpc/naru/NaruApp.java
```

## Core Components

### 1. Agent Architecture
- **NaruAgentRunner**: The core loop that orchestrates agent execution, manages conversation history, and handles tool calls
- **NaruAgentConfig**: Configuration for agent behavior including model selection, maximum steps, and verbosity
- **NaruAgentContext**: Contains execution context including project directory, extra context, and script manager
- **NaruModelProvider**: Interface for different AI model providers (currently only Ollama implemented)
- **NaruToolRegistry**: Manages tool registration, definition, and execution
- **NaruScriptManager**: Manages script execution and editing capabilities
- **NaruSessionContext**: Handles the current state during session execution

### 2. Tool System
NARU provides these built-in tools:
- `read_file`: Read content from any file in the project directory
- `write_file`: Create or replace a file with given content  
- `list_files`: List files and directories in the project
- `run_shell`: Execute shell commands
- `maven_compile`: Compile a Maven project 
- `maven_test`: Run Maven tests
- `run_script`: Execute scripts
- `write_script_line`: Add a line to the current script
- `inspect_image`: Analyze images using vision model (if enabled)

### 3. Model Providers
- **OllamaProviderNaru**: Connects to local Ollama models at `http://localhost:11434`
- Supports various Qwen and Llama models configured in `.crush.json` and `opencode.json`

## Enhanced Features: Malleable Scripting

The refactored version now supports malleable scripting:

1. **Interactive Mode**: When no `--task` is provided, NARU enters REPL mode
2. **Script Management**: 
   - `/load-script [name]` - Load a script context
   - `/unload-script` - Return to main context
   - `/list` - List current script lines
   - `/run` - Execute the current script
3. **Script Editing**: Direct input lines are added to current script
4. **Script Execution**: Lines can be executed one at a time or as a complete script
5. **Persistent Scripting**: Scripts are stored and can be edited during interaction sessions

## Usage

```
naru --task "What files are in the project?" [options]
```

### Options:
- `--task <text>`: Optional. The task description for the agent. If omitted, enters REPL mode
- `--model <name>`: Reasoning model (default: qwen2.5-coder:7b)
- `--vision-model <name>`: Vision model for inspect_image (default: qwen2.5vl:7b)
- `--provider <name>`: Provider: ollama (default: ollama)  
- `--provider-url <url>`: Provider base URL (default: http://localhost:11434)
- `--project-dir <path>`: Project directory tools operate in (default: .)
- `--max-steps <n>`: Max agent loop iterations (default: 20)
- `--no-vision`: Disable the inspect_image tool
- `--quiet`: Suppress step-by-step output
- `--help`: Show usage help

## How It Works

### Single Task Execution:
1. **Initialization**: Parses CLI arguments, configures model provider, registers tools
2. **Execution Loop**: 
   - Sends task with system prompt to model
   - If model returns tool calls: executes tools, appends results to conversation
   - If model returns text: final answer reached
   - Continues until max steps or final answer
3. **Tool Execution Context**: All tools operate within the specified project directory

### Interactive Mode (Malleable Scripting):
1. **REPL Mode**: When no task is provided, enters a command-line interface
2. **Script Management**: 
   - Lines are added to current script context
   - `/load-script [name]` to switch to a script
   - `/run` to execute current script
   - `/list` to show current script contents
3. **Command Mode**: 
   - `/load-script` - Load a script context
   - `/unload-script` - Return to main context
   - `/list` - List current script lines
   - `/run` - Execute the current script
4. **Line-by-line execution**: Individual lines of scripts are executed using the AI model

## Key Design Patterns

### 1. Tool-Based Architecture
- Uses a tool registry pattern to dynamically add/remove capabilities
- Tools are executed with a consistent interface (`dispatch(name, args, context)`)
- Tools operate within the project directory context

### 2. Agent-Model Interaction
- Uses the chat interface pattern where messages are exchanged in a history
- Supports both text responses and tool calls from the model
- Model response parsing handles both cases

### 3. Configuration-Driven
- Uses configuration objects (NaruAgentConfig) for easy parameterization
- Model provider abstraction makes it easy to add new providers in the future
- CLI argument parsing is kept simple and independent of complex frameworks

### 4. Session Management
- Session context maintains conversation state
- Script manager enables persistent scripting across sessions
- Supports both individual task execution and interactive script building

## Building and Running

### Build:
```bash
# Using Maven
mvn clean package
```

### Run:
```bash
# Single task execution
java -jar target/naru-*.jar --task "Describe the project"

# Interactive REPL mode (no task provided)
java -jar target/naru-*.jar

# Or via Nuts (if available)
nuts run net.thevpc:naru --task "Describe the project"
```

## Requirements

- **Ollama**: Local LLM server running at `http://localhost:11434`
- **Java 11+**: Required for running the application
- **Maven**: For building the project

## Available Models

The application is configured with various Qwen and Llama models including:
- qwen3.6:latest
- llama3.3:70b
- mistral-small:latest
- deepseek-coder-v2:16b
- qwen3-coder:30b
- qwen3-coder-32k
- qwen3-coder-64k
- qwen3-coder-30b-132k
- qwen3-coder-30b-256k
- qwen2.5:7b-instruct-q4_K_M
- gemma3:latest
- qwen3:latest

Note: Models must be pulled in Ollama before use (e.g., `ollama pull qwen2.5-coder:7b`).

## Examples

### Interactive Mode:
```bash
# Enter REPL mode
naru

# In REPL mode, type commands or script lines
> /load-script myscript
> Write a function to calculate Fibonacci numbers
> /run
```

### Single Task Execution:
```bash
# Simple file listing
naru --task "List all Java files in the project" --project-dir ./my-app

# Code modification task
naru --task "Add error handling to the login function" --project-dir ./src/main/java

# Compile and test
naru --task "Run all tests for the project" --project-dir ./my-project

# View and analyze
naru --task "Explain how the agent system works" --project-dir ./src
```