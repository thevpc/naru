# NARU — Nuts AI Reasoning Unit

NARU is a multi-model, tool-based AI agent that works with local language models (via Ollama) to assist with software engineering tasks. It provides both single-task execution and interactive malleable scripting capabilities.

## Features

- **Single Task Execution**: Execute specific tasks with AI agent
- **Interactive REPL Mode**: Full command-line interface with malleable scripting
- **Script Management**: Create, edit, and execute scripts interactively
- **Multi-Model Support**: Works with various Qwen and Llama models
- **Tool Integration**: Rich set of tools for file operations, shell commands, compilation, and testing
- **Persistent Sessions**: Scripts and session state can be maintained across interactions

## Quick Start

1. Install [Ollama](https://ollama.com/download)
2. Pull required models:
```bash
ollama pull qwen2.5-coder:7b
ollama pull qwen2.5vl:7b
```
3. Build the project:
```bash
mvn clean package
```
4. Run with a task:
```bash
java -jar target/naru-*.jar --task "List all Java files in the project"
```
5. Run in interactive mode:
```bash
java -jar target/naru-*.jar
```

## Interactive Mode Usage

In interactive mode (`naru` without task), you can:
- Type regular commands which are added to the current script
- Use slash commands for session control:
  - `/load-script [name]` - Load a script context
  - `/unload-script` - Return to main context  
  - `/list` - List current script lines
  - `/run` - Execute the current script
  - `/help` - Show help information
  - `/models` - List available models
  - `/pwd` - Show current working directory
  - `/cd [dir]` - Change directory

## Project Structure

```
core/
├── naru-api/              # API interfaces
│   ├── src/main/java/net/thevpc/naru/api/
│   │   ├── agent/         # Agent interfaces
│   │   ├── model/         # Model interfaces
│   │   └── tool/          # Tool interfaces
├── narul-impl/            # Implementation
│   ├── src/main/java/net/thevpc/naru/impl/
│   │   ├── agent/         # Agent implementations
│   │   ├── registry/      # Tool and directive registry
│   │   ├── tool/          # Tool implementations
│   │   ├── model/         # Model provider implementations
│   │   └── cmdline/       # Command line processing
└── app/
    └── src/main/java/net/thevpc/naru/NaruApp.java
```

## Requirements

- **Ollama**: Local LLM server running at `http://localhost:11434`
- **Java 11+**: Required for running the application  
- **Maven**: For building the project

## Available Models

The application is configured with various Qwen and Llama models:
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

## Examples

### Single Task Execution:
```bash
# List files in project
naru --task "List all Java files in the project" --project-dir ./my-app

# Add error handling to function
naru --task "Add error handling to the login function" --project-dir ./src/main/java

# Compile and test
naru --task "Run all tests for the project" --project-dir ./my-project
```

### Interactive Mode:
```bash
# Enter REPL mode
naru

# In REPL mode:
> /load-script myscript
> Write a function to calculate Fibonacci numbers
> /run
```

## License

MIT License

Copyright (c) 2026 Nuts World

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.