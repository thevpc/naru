# NARU vs Opencode Parity Audit Report (v3 Close-Out)

**Evaluator:** Antigravity Agent  
**Date:** 2026-10-10  
**Scope:** Resolution of final items prior to benchmark dry run: wire tool gating & safety, wire-declared schemas, P29 intentional delta, and model preflight.

---

## 1. Wire Tool Gating & Named Configurations

### 1.1 Gating Code Analysis (OR vs AND)
In `v1 §2.1`, it was claimed that tool tag gating is fail-closed with "all tags of a tool required" (AND matching). **That report claim was incorrect.**

Inspection of `NaruTaskImpl.java` (lines 1175, 1202–1207) reveals the actual runtime mechanism:
```java
boolean effectAnd = sessionImpl != null && sessionImpl.isEffectTagAndEnabled();
...
if (effectAnd) {
    if (matchesToolTagsAnd(tt)) {
        toolDefinitions.add(toToolDefinition(naruTool));
    }
} else if (matchesToolTagsOr(tt)) {
    toolDefinitions.add(toToolDefinition(naruTool));
}
```
By default, `isEffectTagAndEnabled()` is `false`. NARU defaults to **`matchesToolTagsOr(tt)` (ANY-tag matching)**. Consequently, granting tags `fs`, `write`, `exec`, and `network` exposed all tools that possessed *at least one* of those tags, including:
- `git_commit` (tags: `[git, write]`)
- `ollama_*` (tags: `[ai, exec]`, `[ai, network]`)
- `routine_*` (tags: `[routine, exec]`, `[routine, write]`)
- `maven_*` (tags: `[dev, java, exec]`)
- `web_search` (tags: `[ai, network]`)

### 1.2 Safety Enforcement & Named NARU Configurations
To ensure safety in benchmark runs—specifically preventing a model from calling `ollama_stop` (which kills the local Ollama server), `ollama_install`, or `git_commit`—unwanted tools are excluded using NARU's session directive:
```text
/tools exclude git_commit ollama_status ollama_start ollama_stop ollama_install ollama_ps routine_run routine_add_line maven_compile maven_test maven_package web_search
```

Two configurations were captured on the wire against mock server `127.0.0.1:8089` using the prompt `"Reply with the single word OK."`:

#### A. `naru-min` (Primary Benchmark Configuration)
- **Tool count:** **14 tools**
- **Tool names:** `['web_fetch', 'run_shell', 'file_read', 'file_write', 'file_append', 'file_edit_lines', 'file_edit_search_replace', 'file_create', 'file_grep', 'diff', 'pwd', 'folder_find', 'cd', 'think']`
- **System prompt length:** **151 characters**
- **Tool schema length:** **9,346 characters**
- **Total request body length:** **9,705 characters**
- **Token estimate (chars / 4):** **2,426 tokens**

#### B. `naru-granted` (T0 Reference Configuration)
- **Tool count:** **26 tools**
- **Tool names:** `['web_search', 'web_fetch', 'run_shell', 'routine_run', 'routine_add_line', 'maven_compile', 'maven_test', 'maven_package', 'file_read', 'file_write', 'file_append', 'file_edit_lines', 'file_edit_search_replace', 'file_create', 'file_grep', 'diff', 'pwd', 'folder_find', 'cd', 'think', 'git_commit', 'ollama_status', 'ollama_start', 'ollama_stop', 'ollama_install', 'ollama_ps']`
- **System prompt length:** **154 characters**
- **Tool schema length:** **13,666 characters**
- **Total request body length:** **14,028 characters**
- **Token estimate (chars / 4):** **3,507 tokens**

*Note on `think`:* The `think` tool is always visible in NARU as shipped (no tag required). Opencode has no equivalent tool. The benchmark harness will keep `think` visible and explicitly log call counts for it.

---

## 2. Wire Schemas for Core Tools & Replay with Wire Parameters

### 2.1 Wire Schemas (Captured from Wire JSON)

#### `file_read`
```json
{
  "name": "file_read",
  "description": "Read contents of a text file. If line constraints are provided, it performs a strict logical 'AND' to slice the exact line range (0-indexed or 1-indexed depending on preference, standardizing on 1-based here). Omitting bounds reads from line 1 up to a safe threshold (2000 lines).",
  "parameters": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "File path to read"
      },
      "line_start": {
        "type": "integer",
        "description": "First line to read (1-based, optional, defaults to 1)",
        "default": 1
      },
      "line_end": {
        "type": "integer",
        "description": "Last line to read (inclusive, optional, defaults to start + 2000)"
      }
    },
    "required": [
      "path"
    ]
  }
}
```

#### `file_edit_search_replace`
```json
{
  "name": "file_edit_search_replace",
  "description": "Search for an exact string in a text file and replace it with new content. Optionally specify occurrence number (1-based, default 1). Use dry=true to preview changes without writing.",
  "parameters": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "File path to edit"
      },
      "search": {
        "type": "string",
        "description": "Exact text string to find"
      },
      "replace": {
        "type": "string",
        "description": "Replacement text string"
      },
      "occurrence": {
        "type": "integer",
        "description": "Which occurrence to replace (1-based, default 1)"
      },
      "replaceAll": {
        "type": "boolean",
        "description": "Replace all occurrences of search string (default false)"
      },
      "dry": {
        "type": "boolean",
        "description": "If true, preview changes without modifying the file"
      }
    },
    "required": [
      "path",
      "search",
      "replace"
    ]
  }
}
```

#### `file_grep`
```json
{
  "name": "file_grep",
  "description": "Search for lines in a text file matching a pattern. Supports literal and regex search with surrounding context.",
  "parameters": {
    "type": "object",
    "properties": {
      "pattern": {
        "type": "string",
        "description": "Search pattern"
      },
      "path": {
        "type": "string",
        "description": "File or directory to search (optional, defaults to current working directory)"
      },
      "include": {
        "type": "string",
        "description": "File pattern to include in the search (e.g. *.js, *.java)"
      },
      "regex": {
        "type": "boolean",
        "description": "Treat pattern as regex",
        "default": true
      },
      "case_sensitive": {
        "type": "boolean",
        "description": "Case-sensitive match",
        "default": true
      },
      "context_lines": {
        "type": "integer",
        "description": "Lines of context before/after match",
        "default": 2
      },
      "max_matches": {
        "type": "integer",
        "description": "Maximum matches to return",
        "default": 50
      }
    },
    "required": [
      "pattern"
    ]
  }
}
```

#### `folder_find`
```json
{
  "name": "folder_find",
  "description": "Find files by name/glob patterns and optionally search their contents. CRITICAL: A strict logical 'AND' is performed across ALL filters. Files must simultaneously match: Location Path AND Glob Filters (include/exclude) AND Time Window Constraints (modified_after/before) AND Content Text Pattern (if provided). Only files satisfying EVERY layer of this constraint chain are processed.",
  "parameters": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "Directory to search"
      },
      "content_pattern": {
        "type": "string",
        "description": "Search text pattern inside file contents (optional)"
      },
      "regex": {
        "type": "boolean",
        "description": "Treat pattern as regex",
        "default": false
      },
      "case_sensitive": {
        "type": "boolean",
        "description": "Case-sensitive match",
        "default": false
      },
      "context_lines": {
        "type": "integer",
        "description": "Lines of context before/after match",
        "default": 2
      },
      "max_matches": {
        "type": "integer",
        "description": "Max matches per file",
        "default": 50
      },
      "max_files": {
        "type": "integer",
        "description": "Max files to scan",
        "default": 50
      },
      "include_filename": {
        "type": "string",
        "description": "Glob patterns to include file by name (comma-separated, e.g. '*.java,*.xml')"
      },
      "exclude_filename": {
        "type": "string",
        "description": "Glob patterns to exclude file by name (comma-separated, e.g. '*.java,*.xml')"
      },
      "recursive": {
        "type": "boolean",
        "description": "Search subdirectories",
        "default": true
      },
      "modified_after": {
        "type": "string",
        "description": "ISO-8601 timestamp (e.g. 2026-05-01T00:00:00Z) or relative duration (e.g. -24h, -7d)"
      },
      "modified_before": {
        "type": "string",
        "description": "ISO-8601 timestamp or relative duration"
      }
    },
    "required": [
      "path"
    ]
  }
}
```

#### `run_shell`
```json
{
  "name": "run_shell",
  "description": "Execute a shell command and return its output (stdout + stderr). Use sparingly; prefer specialised tools like maven_compile when available.",
  "parameters": {
    "type": "object",
    "properties": {
      "command": {
        "type": "string",
        "description": "Shell command to execute"
      },
      "working_dir": {
        "type": "string",
        "description": "Directory to run the command in (defaults to project dir)"
      },
      "timeout_seconds": {
        "type": "integer",
        "description": "Max seconds to wait (default: 60)"
      }
    },
    "required": [
      "command"
    ]
  }
}
```

#### `web_fetch`
```json
{
  "name": "web_fetch",
  "description": "Fetches content from a specified URL and converts it to requested format (markdown, text, or html). Defaults to markdown.",
  "parameters": {
    "type": "object",
    "properties": {
      "url": {
        "type": "string",
        "description": "The URL to fetch content from"
      },
      "format": {
        "type": "string",
        "description": "The format to return the content in (markdown, text, or html). Defaults to markdown.",
        "default": "markdown"
      },
      "timeout": {
        "type": "integer",
        "description": "Optional timeout in seconds (max 120)"
      }
    },
    "required": [
      "url"
    ]
  }
}
```

### 2.2 Replay with Wire-Declared Parameter Names
`ParityReplayTest.java` was updated to pass **only wire-declared parameter names**:
- `path` (replacing `filePath`)
- `line_start` (replacing `offset`)
- `line_end` (computed from `offset + limit - 1`)
- `search` / `replace` (replacing `oldString` / `newString`)
- `working_dir` (replacing `workdir`)
- `timeout_seconds` (replacing `timeout`)

**Replay Outcome:**
- **39 / 40 Passed.**
- The single mismatch is **P29**, which failed intentionally due to dropping the file-path-parent accommodation (see Section 3).

---

## 3. P29 File Path Accommodation Dropped (Intentional Delta)

- In `FileToolHelper.java`, the code `if (targetPath.isRegularFile()) targetPath = targetPath.parent();` was **removed**.
- As a result, when `file_grep` is invoked with `path` pointing to a regular file (`/tmp/lab/proj2/sub/x.java`):
  - Opencode searches that file's parent directory (`sub/`).
  - NARU treats it as a non-directory search and returns `No files found` (or an error), refusing to silently retarget the search to the parent directory.
- **Classification:** Marked as an **`intentional delta`**.

---

## 4. Preflight Model Availability Check

- **Model:** `gemini-2.5-flash`
- **Verification Method:** Checked against `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash` with active `GEMINI_API_KEY`.
- **Preflight Results:**
  - Endpoint returned HTTP **200 OK**.
  - Direct 1-token test call (`"Reply with the single word OK."`) returned **"OK"**.
  - Model is alive and responsive. No 404 encountered.

---

## 5. Configuration Ready for Dry Run (T0 and T1)

The harness is configured as follows:
- **NARU settings:**
  - `naru.compact.auto=false`
  - `naru.fs.read.lineNumbers=true`
  - default read cap: `2000`
  - tool configuration: `naru-min` (14 tools)
- **Opencode settings:**
  - flags: `--auto`
  - environment: `OPENCODE_DISABLE_AUTOCOMPACT=1`, `OPENCODE_DISABLE_AUTOUPDATE=1`
  - stdin: `/dev/null`
