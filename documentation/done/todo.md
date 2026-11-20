# TODO LIST

# ----------------------------

# -----------------------------

# Feature Request: Dynamic Hardware-Aware Model Timeouts & Coding Agent Extension

Implement two modular enhancements in the NARU codebase adhering strictly to NARU's decoupled architecture: (1) dynamic, hardware-aware timeouts calculated at the provider factory level, and (2) a modular coding agent extension that provides autonomous coding instructions without polluting the task-agnostic core runtime.

---

## Task 1: Hardware- & Locality-Aware Dynamic Model Timeouts

### Background
Currently, `NaruModelProtocolBase` defaults to a fixed 120-second read timeout. On local models (e.g. Ollama, local endpoints) running on CPU or constrained hardware, prefill (Time-To-First-Token) for multi-turn contexts (4k+ tokens) regularly exceeds 120s, causing artificial socket timeouts and task aborts.

### Requirements
1. **Locality & Hardware Detection at Provider Level**:
    - `NaruModelProvider` is the factory that instantiates `NaruModelProtocol` via `getProtocol(model, session)`.
    - The provider must determine whether it is **local**:
        - `NaruOllamaProvider` is local by default.
        - Custom/OpenAI providers are local if their configured `--url` targets `localhost`, `127.0.0.1`, or `[::1]`, or if explicit parameter `--local=true` is set.
        - Other remote providers (Gemini, Claude, OpenRouter, OpenAI cloud) are remote.
2. **Dynamic Timeout Calculation**:
    - If `timeout` or `readTimeout` is explicitly configured by the user/session, that explicit value must always be respected.
    - Otherwise, when the provider constructs the protocol, it calculates the default timeout using Nuts platform detection (`net.thevpc.nuts.platform.NEnv`):
        - **Remote Providers**: `120s` (2 minutes).
        - **Local Provider with Dedicated GPU** (`!NEnv.of().gpus().isEmpty()`): `300s` (5 minutes).
        - **Local Provider without GPU (CPU/RAM offload)** (`NEnv.of().gpus().isEmpty()`): `600s` (10 minutes).
3. **Pass to Protocol**:
    - The provider passes the resolved default timeout (or `isLocal` flag) directly to the protocol instance upon creation so `NaruModelProtocolBase` does not perform ad-hoc host lookups.

---

## Task 2: Modular Coding Agent Extension

### Background
NARU core (`naru-impl` / `naru-api`) is a task-agnostic actor runtime. Its base system prompt is intentionally minimal (~2 lines). For software engineering tasks, the LLM needs explicit autonomous execution rules (explore -> edit -> test -> finish) so it does not prematurely terminate after merely reading files or violate constraints. These rules must be contributed via an extension, keeping the core runtime generic.

### Requirements
1. **Create/Update the Coding Agent Extension**:
    - In `extensions/` (e.g. in `naru-tools-coder` or a dedicated extension implementing `NaruSessionExtension`), contribute a system prompt message when coding intent is active.
2. **Relevance Gating (`isRelevant(NaruTask task)`)**:
    - The extension must only inject guidance when coding capabilities or executing intent are present (e.g., when tool tags `write`, `exec`, or `fs` are enabled, or when `task.promptMode().modeIntent() == ModeIntent.EXECUTING`).
    - It must NOT pollute simple chat or read-only tasks.
3. **Contributed System Prompt**:
    - Provide the following compact (~350 tokens) system prompt via `extension.contribute(task)`:

   ```text
   You are an expert autonomous software engineer. Your objective is to completely solve the user's request in the project directory.

   ## Execution Workflow
   1. Explore: Find and read relevant files to understand context, architecture, and existing conventions before editing.
   2. Implement: Make precise, working code modifications using the provided tools. Never leave placeholders or incomplete implementations.
   3. Verify: Always run tests or validation commands (via shell) to confirm your changes pass and introduce no regressions.
   4. Iterate: If tests fail or errors occur, inspect the failure, fix the root cause, and re-verify until all pass.

   ## Core Rules & Constraints
   - Autonomy: Do not stop after merely exploring or reading files. Continue using tools until the task is completely implemented and verified. Only provide a final text response when all work is finished.
   - Strict Constraints: Respect all negative constraints in the prompt (e.g., if asked "Do not edit files in tests/", never touch test files).
   - Non-Destructive: Preserve existing code style, formatting, and unrelated functionality. Avoid overwriting entire files when surgical edits suffice.
   ```
Verification & Acceptance
Ensure the reactor compiles cleanly (mvn clean test-compile or nuts-dev build).
Add unit tests verifying:
Provider calculates 120s for remote URLs and 300s/600s for local URLs based on NEnv.of().gpus().
these values should be configurable (task level, session level, agent level, settings level) being indexed or not on the model family
The coding extension only contributes messages when coding tags/intent are present, and contributes zero messages when in a generic or read-only task.
