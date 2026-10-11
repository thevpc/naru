# Tool Parity Audit: NARU vs Opencode

**Date:** 2026-10-10 (Updated post-audit review)  
**Evaluator:** Antigravity Reasoning Unit (JVM & Agent Audit)  
**Target Codebase:** `/home/vpc/xprojects/nuts-world/nuts-productivity/naru/`  
**Reference Specification:** Opencode 1.18.35 default tool definitions (`bench/opencode-tools.json`) & live captures  
**Hard Gate Verdict:** `PARITY BLOCKED`

---

## 1. Executive Summary

A comprehensive tool parity audit was conducted between **NARU** (as shipped, Maven JVM reactor) and **Opencode 1.18.35** (reference coding agent). The audit examined default tool inventories, tag gating architectures, full JSON schema wire contracts, and real runtime behaviors on the local execution harness.

### Hard Gate Verdict: `PARITY BLOCKED`

Under the benchmark contract:
> *If any of `read`, `write`, `edit`, `glob`, `grep`, `bash` is `MISSING` or `PARTIAL` with a behavioral delta that alters outcomes (e.g. no uniqueness check in `edit`, no output cap in `bash`), STOP after writing the report and list minimal changes needed.*

Four out of the six core programming tools (`edit`, `grep`, `glob`, `bash`) exhibit **fatal bugs or fundamental contract mismatches** that directly alter model outcomes:

1. **`edit` (PARTIAL — BLOCKS PARITY):**
   - **No uniqueness enforcement:** When multiple instances of the search string exist and `occurrence` is omitted, NARU silently defaults to `occurrence = 1` and replaces the first match. In contrast, Opencode fails closed (`"Found multiple matches for oldString..."`) to protect against corrupted edits.
   - **No identical-replacement check:** NARU happily accepts identical search and replace strings.
   - **No `replaceAll` support:** NARU cannot perform bulk renames across a file in a single tool call.
   - **No read-before-edit verification:** Allows blind overwrites without context inspection.

2. **`grep` (PARTIAL / BROKEN — BLOCKS PARITY):**
   - **Fatal Schema / Implementation Mismatch:** `FileGrepTool` declares its search parameter as `content_pattern` in its advertised JSON schema. However, `FileGrepTool.execute()` reads `context.stringArg("pattern")`. When the model calls `file_grep` using the schema-declared parameter `content_pattern`, execution **fails immediately** with `ERROR: 'pattern' is required.`
   - **Single-file only:** `file_grep` only operates on a single regular file; it rejects directories, whereas Opencode's `grep` performs workspace-wide recursive regex search with glob filtering (`include`).

3. **`glob` (PARTIAL / BROKEN — BLOCKS PARITY):**
   - **Fatal Schema / Implementation Mismatch:** `FolderFindTool` advertises `content_pattern`, `include_filename`, and `exclude_filename`. Internally, `FolderFindTool.execute()` reads `context.stringArg("pattern")`, `include`, and `exclude`. Any call passing schema parameters has its pattern and include/exclude filters completely ignored.
   - **Path globbing broken:** `FolderFindTool.createMatcher` tests `path.name().matches(...)` (the basename only). Multi-segment globs such as `src/**/*.java` or `target/*.jar` never match.
   - **Missing defaults:** Opencode defaults `path` to current working directory; NARU requires an explicit `path`.

4. **`bash` (PARTIAL — BLOCKS PARITY):**
   - **Severe Truncation Bug (256 chars):** While `RunShellTool` defines `MAX_OUTPUT_CHARS = 8000`, it actually delegates to `NExec.grabbedAll()`, which truncates captured output to **256 characters**. Any build output, test stack trace, or directory listing longer than 256 characters is truncated to `...`!
   - **Exit Code / Stderr Masking:** `RunShellTool` sets `.failFast(true)`. When a process exits with a non-zero code (e.g. `exit 42` or a test failure), `NExec` throws `NExecException`, returning `ERROR running command: execution failed with code 42 and message : `, completely discarding process stdout and stderr.
   - *(Note on Persistence):* Both Opencode and NARU run subcommands in stateless subshells (`export FOO=42; cd /tmp` does not persist environment or cwd to subsequent calls in either agent). The description in Opencode's schema claiming "persistent shell session" is documentation drift; runtime behavior is matching.

Because these tools alter task success rates and cause unrecoverable tool-call errors for any model expecting standard coding agent conventions, **Phase 2 (Benchmark Execution) is blocked until these issues are resolved.**

---

## 2. Default Tool Inventory and Tag Gate Analysis

### 2.1 The Tag Gating Trap

NARU uses a tag-based visibility model (`NaruTool.tags()`). A tool is only exposed in the LLM's `tools` array if the active `NaruTask` has been granted all tags associated with that tool (or if the tool is untagged).

Empirical probe results:
- **Default task (0 tags granted):**
  Only **1 tool** is exposed to the model: `think`.
  All filesystem, shell, planning, routine, and git tools are hidden.
- **Tools with tags:**
  Tools in NARU are fail-closed. If a tool requires `[fs, write]`, granting `fs` alone is not enough; both `fs` AND `write` must be granted.

### 2.2 Complete NARU Tool Catalog

The following catalog was compiled directly from the live `NaruSession.registry().tools()` inspection:

| Tool Name | Tags | Essential | Tag-Gated Out by Default? | Description Summary |
|---|---|---|---|---|
| `think` | `[]` | No | **No (Always Visible)** | Private reasoning workspace |
| `file_read` | `[fs]` | No | Yes | Reads file slices (up to 1000 lines) |
| `file_write` | `[fs, write]` | No | Yes | Overwrites file, creates parents |
| `file_append` | `[fs, write]` | No | Yes | Appends text to file |
| `file_edit_lines` | `[fs, write]` | No | Yes | Line-range insertion/replacement |
| `file_edit_search_replace` | `[fs, write]` | No | Yes | Exact substring replacement |
| `file_create` | `[fs, write]` | No | Yes | Creates new file (fails if exists unless overwrite) |
| `file_grep` | `[fs]` | No | Yes | Single-file regex/literal search |
| `diff` | `[fs]` | No | Yes | Compares two text files |
| `folder_find` | `[fs]` | No | Yes | Finds files by name/glob/content |
| `pwd` | `[fs]` | No | Yes | Prints working directory |
| `cd` | `[fs]` | No | Yes | Changes active working directory |
| `run_shell` | `[exec, write, network]` | No | Yes | Executes shell command |
| `maven_compile` | `[java, exec]` | No | Yes | Runs `mvn compile` |
| `maven_test` | `[java, exec]` | No | Yes | Runs `mvn test` |
| `maven_package` | `[java, exec]` | No | Yes | Runs `mvn package -DskipTests` |
| `git_status` | `[git, dev]` | No | Yes | Runs `git status --porcelain` |
| `git_diff` | `[git, dev]` | No | Yes | Runs `git diff` |
| `git_log` | `[git, dev]` | No | Yes | Runs `git log` |
| `git_commit` | `[git, write]` | No | Yes | Runs `git commit` |
| `plan_create` | `[plan]` | No | Yes | Creates dependency DAG plan |
| `plan_update` | `[plan]` | No | Yes | Updates plan step status |
| `plan_get` | `[plan]` | No | Yes | Inspects active plan |
| `search_web` | `[network]` | No | Yes | DuckDuckGo web search |
| `routine_run` | `[routine, exec]` | No | Yes | Runs internal script routine |
| `routine_add_line` | `[routine, write]` | No | Yes | Adds line to internal routine |
| `routine_list_lines` | `[routine]` | No | Yes | Lists lines in routine |
| `tag_add` | `[tags]` | No | Yes | Grants tag to task |
| `tag_remove` | `[tags]` | No | Yes | Revokes tag |
| `tag_list` | `[tags]` | No | Yes | Lists available/active tags |
| `context_compact` | `[ai]` | No | Yes | Triggered conversation compaction |
| `delegate_to_model`| `[ai]` | No | Yes | Delegates prompt to secondary LLM |
| `model_list` | `[ai]` | No | Yes | Lists available models |
| `ollama_*` (5 tools)| `[ai, exec]` | No | Yes | Ollama daemon lifecycle management |
| `semantic_search` | `[semantic, dev]` | No | Yes | Code vector search |
| `project_map` | `[index, dev]` | No | Yes | Structural file tree |
| `code_symbols` | `[index, dev]` | No | Yes | Lists code symbols in project |
| `find_symbol` | `[index, dev]` | No | Yes | Searches symbols |
| `project_summary` | `[index, dev]` | No | Yes | Project architectural summary |
| `skill` | `[skills]` | No | Yes | Executes installed skill |

### 2.3 Required Tag Set for Opencode Equivalence

To expose tools roughly equivalent to Opencode's baseline set (`read`, `write`, `edit`, `glob`, `grep`, `bash`, `plan`), the NARU task **must** be created with the following tags explicitly enabled:
```
fs, write, exec, network, plan, skills
```
If any of these tags is omitted, the corresponding tools vanish entirely from the request payload.

---

## 3. Mapping Table: Opencode vs NARU Tools

The table below maps each of the 10 reference tools sent by Opencode 1.18.35 to its NARU counterpart.

| Opencode Tool (1.18.35) | NARU Counterpart | Verdict | Parameter Differences | Behavioral Deltas | Minimal Fix Required |
|---|---|---|---|---|---|
| **`read`** | `file_read` | **PARTIAL** | Opencode: `filePath`, `offset` (1-based), `limit` (default 2000).<br>NARU: `path`, `line_start` (1-based, default 1), `line_end` (inclusive, default start+1000). | 1. Opencode prefixes lines with `line: ` numbers; NARU outputs raw text.<br>2. Opencode handles directories by listing children; NARU throws `ERROR: Path is not a regular file`.<br>3. Opencode caps lines at 2000 chars; NARU does not truncate long lines. | Add directory listing fallback in `FileReadTool.java`, optionally prefix line numbers if model prompt assumes line numbers. |
| **`write`** | `file_write` | **EQUIVALENT** | Opencode: `filePath`, `content`.<br>NARU: `path`, `content`, optional `dry`. | Both overwrite files and create missing parent directories. Opencode enforces read-before-write; NARU allows direct writes. | None strictly blocking, though read-before-write check could prevent accidental clobbering. |
| **`edit`** | `file_edit_search_replace` | **PARTIAL (BLOCKS PARITY)** | Opencode: `filePath`, `oldString`, `newString`, `replaceAll` (bool).<br>NARU: `path`, `search`, `replace`, `occurrence` (int, default 1), `dry` (bool). | 1. **Ambiguity Handling:** Opencode throws error if `oldString` matches multiple times and `replaceAll` is false. NARU silently replaces occurrence 1!<br>2. **Identical check:** Opencode rejects identical strings; NARU succeeds.<br>3. **Bulk replacement:** Opencode supports `replaceAll=true`; NARU has no `replaceAll`.<br>4. Opencode enforces read-before-edit. | Modify `FileToolHelper.fileEditSearchReplace`: require uniqueness when `occurrence` is omitted; add `replaceAll` flag; reject identical strings. |
| **`glob`** | `folder_find` | **PARTIAL (BLOCKS PARITY)** | Opencode: `pattern` (glob), `path` (optional, defaults to cwd).<br>NARU: `path` (required), schema advertises `include_filename`, `exclude_filename`, `content_pattern`. | 1. **Fatal Parameter Bug:** Schema advertises `include_filename`, but code reads `context.stringArg("include")`. Arguments from model are ignored!<br>2. **Glob matching broken:** `FolderFindTool` matches only `path.name()` (basename); path patterns like `src/**/*.java` fail.<br>3. Default directory: Opencode defaults to cwd; NARU fails if `path` is omitted. | Fix parameter name mismatch in `FolderFindTool.java`; implement true path glob matcher (`PathMatcher` over relative paths); default `path` to project directory. |
| **`grep`** | `file_grep` | **PARTIAL (BLOCKS PARITY)** | Opencode: `pattern` (regex), `path` (dir, defaults to cwd), `include` (glob filter).<br>NARU: `path` (single file only), `content_pattern` (regex/str), `regex`, `context_lines`, `max_matches`. | 1. **Fatal Crash Bug:** Schema advertises `content_pattern`, but code reads `context.stringArg("pattern")`. Calling with schema args yields `ERROR: 'pattern' is required.`<br>2. **Scope:** Opencode greps across whole directory trees with path filters; NARU only greps a single file. | Fix parameter name in `FileGrepTool.java`; allow `path` to be a directory and recursively grep files matching optional filter. |
| **`bash`** | `run_shell` | **PARTIAL (BLOCKS PARITY)** | Opencode: `command`, `timeout` (ms, default 120s), `workdir`.<br>NARU: `command`, `working_dir`, `timeout_seconds` (default 60s). | 1. **Fatal Truncation:** Captured output is capped at 256 characters by `NExec.grabbedAll()`.<br>2. **Masked Failures:** Non-zero exit code throws `NExecException`, returning error message without stdout/stderr.<br>*(Persistence):* Both use stateless subshells (empirically confirmed: environment and directory changes do not persist across calls). | Fix output buffer limit in `NExec` / `RunShellTool` (set to 51200 bytes / 2000 lines); capture stdout/stderr on non-zero exit instead of throwing raw exception. |
| **`skill`** | `skill` (`naru-skills`) | **EQUIVALENT** | Opencode: `name`.<br>NARU: `name`, `args` (in `naru-skills` extension). | Both load skill instructions from configured skill roots. NARU requires the `skills` tag. | Ensure `naru-skills` extension is active and `skills` tag is granted. |
| **`task`** | *(None for LLM)* | **MISSING** | Opencode: `description`, `prompt`, `subagent_type` (`explore`, `general`), `task_id`.<br>NARU: Subagents are CLI/API directives only; no tool exposed for LLM to spawn subagents dynamically. | Opencode models can delegate exploratory searches to subagents. NARU models cannot spawn subagents via tool call. | Add a `task_delegate` tool if multi-agent subtasking is tested. |
| **`todowrite`**| `plan_create` / `plan_update` | **PARTIAL** | Opencode: structured list of tasks (`todos`: array of objects with `content`, `status`, `priority`).<br>NARU: dependency graph (`items`: array of objects with `key`, `description`, `dependsOn`, `validator`). | Different planning paradigm (flat status checklist vs DAG dependency plan). NARU plans require human approval unless validators are omitted. | Model prompt instructions must guide NARU tool usage if plan benchmarking is evaluated. |
| **`webfetch`** | *(None)* | **MISSING** | Opencode: `url`, `format` (markdown, text, html), `timeout`.<br>NARU: Has `search_web` (DuckDuckGo search), but **no tool to fetch and parse URL contents**. | Model cannot read documentation or URLs directly in NARU without running `curl` in `run_shell`. | Implement a `web_fetch` tool using Java `HttpClient` / Jsoup. |

---

## 4. Non-Tool Agent Behavior Analysis

### 4.1 Fixed Prompt Overhead and Token Footprint

A critical difference between Opencode and NARU lies in their distribution of prompt instructions between the **system prompt** and **tool parameter schemas**:

- **Opencode:**
  - **Full System Prompt:** **9,544 characters** (starts with `You are opencode, an interactive CLI tool that helps users with software engineering tasks...` and includes comprehensive sections on tone, communication rules, git conventions, directory structure, and operational guidance).
  - **Tool Schemas (10 tools):** **21,144 characters** (detailed descriptions with parameter examples and negative constraints).
  - **Total Fixed Overhead:** **~31,047 characters (~31 KB)** before any user messages or file history are included.
  - *(Correction Note: The 137-character snippet previously noted in binary strings comes from an internal fallback/eval string in the CLI binary, not the full prompt injected into LLM requests).*

- **NARU:**
  - **System Prompt:** **114 characters** (`You are NARU (Nuts AI Reasoning Unit)... Project directory: /path`).
  - **Tool Schemas (28 tools with standard tags):** **15,549 characters** (~15.5 KB).
  - **Total Fixed Overhead:** **~15,700 characters (~15.7 KB)**.

**Key Architectural Conclusion:**  
Opencode's fixed overhead is roughly **2x NARU's footprint** (~31 KB vs ~15.7 KB). Opencode front-loads massive instructions into the system prompt and tool descriptions, consuming roughly ~7,000–8,000 prompt tokens on turn zero. NARU takes a minimalist approach, leaving the system prompt essentially empty (~25 tokens) and relying on concise tool schemas (~3,800 tokens).

In Phase 2 benchmarks:
- **T0 (Baseline turnaround):** Will directly isolate and measure this fixed token consumption difference.
- **T1 (Single-turn tool calls):** Will reflect this substantial fixed token delta on every request.

### 4.2 Context Compaction Behavior

Both agents include automatic conversation compaction to prevent context window exhaustion:
- **Opencode:** Employs an internal message compactor that summarizes older turns when approaching context limits. Disabled via `OPENCODE_DISABLE_AUTOCOMPACT=1`.
- **NARU:** Implemented in the `naru-tools-compact` extension (`documentation/user-guide/content/compaction.md`). Automatic compaction is **on by default** (`naru.compact.auto = true`), triggering at `threshold = 0.80` (80% of context window) with target compaction to 40% and keeping the last 4 turns verbatim. It is non-destructive (covered items remain flagged in history).
- **Benchmark Configuration:** To ensure deterministic, unperturbed token and quality benchmarking across tasks, automatic compaction should be disabled in both harnesses (`OPENCODE_DISABLE_AUTOCOMPACT=1` for Opencode and `naru.compact.auto=false` for NARU).

### 4.3 Additional Hidden Wire Calls
- **Opencode:** Automatically issues background calls to generate session titles (`auto-title`) and check updates (`OPENCODE_DISABLE_AUTOUPDATE=1`).
- **NARU:** Does not make hidden title-generation model calls. Wire requests strictly reflect user prompts and tool responses.

---

## 5. Minimal Changes Needed for Parity

To satisfy the Hard Gate and make NARU comparable to Opencode without behavioral failures, the following minimal changes are required in NARU core:

### 1. Fix `FileGrepTool.java` Parameter Desync
- **Location:** `core/naru-impl/src/main/java/net/thevpc/naru/impl/tools/FileGrepTool.java`
- **Change:** Change `context.stringArg("pattern")` to `context.stringArg("content_pattern")` (or accept both for backward compatibility).
- **Directory support:** Allow `path` to be a directory and recursively scan files.

### 2. Fix `FolderFindTool.java` Parameter Desync & Globbing
- **Location:** `core/naru-impl/src/main/java/net/thevpc/naru/impl/tools/FolderFindTool.java`
- **Change:**
  1. Accept `content_pattern` alongside `pattern`.
  2. Accept `include_filename` alongside `include`.
  3. Accept `exclude_filename` alongside `exclude`.
  4. Fix `createMatcher`: use Java `FileSystem.getPathMatcher("glob:...")` against relative file paths instead of regex matching only the file basename (`path.name()`).
  5. Default `path` to current working directory (`agent.projectDirectory()`) if omitted.

### 3. Fix `FileEditSearchReplaceTool.java` Ambiguity and Replaces
- **Location:** `core/naru-impl/src/main/java/net/thevpc/naru/impl/tools/FileEditSearchReplaceTool.java` & `FileToolHelper.java`
- **Change:**
  1. If `occurrence` is omitted (null or <= 0):
     - Count total occurrences of `search` in file.
     - If count > 1 and `replaceAll` is not set, **fail with error**: `"Found multiple matches for search string. Provide more surrounding context to disambiguate or use replaceAll."`
  2. Add `replaceAll` boolean parameter to schema and execution logic.
  3. Reject identical `search` and `replace` strings with error.

### 4. Fix `RunShellTool.java` Output Truncation & Exit Handling
- **Location:** `core/naru-impl/src/main/java/net/thevpc/naru/impl/tools/RunShellTool.java`
- **Change:**
  1. Increase stdout/stderr capture buffer from 256 characters to 51,200 bytes (or 2,000 lines).
  2. Catch `NExecException` on non-zero exit codes: instead of returning a generic error string, return `EXIT_CODE=<n>` followed by the actual captured stdout and stderr.

### 5. Tag Gate Configuration for Benchmark Tasks
- **Location:** Benchmark test runner / CLI launcher
- **Change:** All benchmark tasks must be launched with explicit tag grants:
  `tags: ["fs", "write", "exec", "network", "plan", "skills"]`
  to ensure all parity tools are exposed to the model.

---

## 6. Final Hard Gate Determination

```
================================================================================
HARD GATE VERDICT: PARITY BLOCKED
================================================================================
Reason: Core tools (edit, grep, glob, bash) exhibit behavioral deltas and fatal
parameter bugs that alter task execution outcomes and prevent fair benchmarking.

Phase 2 (Benchmark Execution) MUST NOT proceed until the core fixes in Section 5
are applied and verified.
================================================================================
```
