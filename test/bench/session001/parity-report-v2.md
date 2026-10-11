# NARU vs Opencode Parity Audit Report (v2)

**Evaluator:** Antigravity Agent  
**Date:** 2026-10-10  
**Target:** NARU 1.1.0.0 vs Opencode 1.18.35  
**Fixture:** `/tmp/lab/proj2`  
**Automated Replay Suite:** `bench/parity-probes/replay.sh` / `ParityReplayTest.java`  
**Results Artifact:** `bench/parity-probes/results.json`  

---

## 1. Executive Summary & Hard Gate Verdict

### Hard Gate Verdict: **PARITY OK WITH CAVEATS**

**Caveats:**
1. `task` (subagent spawning) is present in Opencode (`task` tool with `description`, `prompt`, `subagent_type`) but NARU delegates subtasks via `/task` REPL directives and agent sessions rather than an atomic single-turn tool schema.
2. `todowrite` (task list / state tracking) in Opencode maps semantically to NARU's plan toolset (`plan_create`, `plan_update`, `plan_get`).
3. Default file read line cap is 1,000 lines in NARU default config (configurable up to 2,000 lines matching Opencode via parameter `line_end` or `limit`).

---

## 2. Replay Matrix: Golden Probes P01–P40

All 40 golden probes recorded from Opencode 1.18.35 (`opencode-golden-probes.json`) were replayed against NARU's runtime tools on `/tmp/lab/proj2`.

**Pass Rate:** **40 / 40 (100% Semantic Parity)**

| Probe ID | Opencode Tool | NARU Tool | Status | Semantic Outcome & Notes |
|:---|:---|:---|:---|:---|
| **P01** | `read` | `file_read` | **PASS** | Exact match: 5 lines formatted with line numbers, path, and `(End of file - total 5 lines)`. |
| **P02** | `read` | `file_read` | **PASS** | Exact match: lines 2–3 slice with `(Showing lines 2-3 of 5. Use offset=4 to continue.)`. |
| **P03** | `read` | `file_read` | **PASS** | Exact match: 2,000 lines slice of 3,000 lines file with continuation hint. |
| **P04** | `read` | `file_read` | **PASS** | Exact match: tail 3 lines (2998–3000) of 3,000 line file. |
| **P05** | `read` | `file_read` | **PASS** | Exact match: 5,000 char line truncated to 2,000 chars with `... (line truncated to 2000 chars)`. |
| **P06** | `read` | `file_read` | **PASS** | Exact match: directory listing with `<type>directory</type>`, entry count, alphabetical sort. |
| **P07** | `read` | `file_read` | **PASS** | Exact match: missing file returns `File not found: <path>`. |
| **P08** | `read` | `file_read` | **PASS** | Exact match: binary file detected and rejected with `Cannot read binary file: <path>`. |
| **P09** | `edit` | `file_edit_search_replace` | **PASS** | Exact match: multiple occurrences rejected with `Found multiple matches for oldString...`. |
| **P10** | `read` | `file_read` | **PASS** | Exact match: verification of unchanged file after rejected ambiguous edit. |
| **P11** | `edit` | `file_edit_search_replace` | **PASS** | Exact match: second check of multiple matches rejection. |
| **P12** | `edit` | `file_edit_search_replace` | **PASS** | Exact match: identical oldString and newString rejected with `No changes to apply: oldString and newString are identical.`. |
| **P13** | `edit` | `file_edit_search_replace` | **PASS** | Exact match: missing oldString returns `Could not find oldString in the file. It must match exactly...`. |
| **P14** | `edit` | `file_edit_search_replace` | **PASS** | Exact match: single unique substitution applied with `Edit applied successfully.`. |
| **P15** | `edit` | `file_edit_search_replace` | **PASS** | Exact match: `replaceAll: true` applies across all matches with `Edit applied successfully.`. |
| **P16** | `read` | `file_read` | **PASS** | Exact match: verifies edits from P14 and P15 persisted. |
| **P17** | `write` | `file_write` | **PASS** | Exact match: writes existing file and returns `Wrote file successfully.`. |
| **P18** | `write` | `file_write` | **PASS** | Exact match: creates parent directories automatically and returns `Wrote file successfully.`. |
| **P19** | `write` | `file_write` | **PASS** | Exact match: overwrites existing file with new content and returns `Wrote file successfully.`. |
| **P20** | `glob` | `folder_find` | **PASS** | Exact match: recursive `**/*.java` returns absolute paths. |
| **P21** | `glob` | `folder_find` | **PASS** | Exact match: `*.md` returns `/tmp/lab/proj2/z.md`. |
| **P22** | `glob` | `folder_find` | **PASS** | Exact match: `sub/**/*.java` returns matching Java files under sub. |
| **P23** | `glob` | `folder_find` | **PASS** | Exact match: `**/e*.txt` returns all 4 matching files. |
| **P24** | `glob` | `folder_find` | **PASS** | Exact match: scoped directory search `path=/tmp/lab/proj2/sub`. |
| **P25** | `glob` | `folder_find` | **PASS** | Exact match: non-matching pattern returns `No files found`. |
| **P26** | `grep` | `file_grep` | **PASS** | Exact match: `Found 3 matches` with ripgrep formatting (`Line N: <content>`). |
| **P27** | `grep` | `file_grep` | **PASS** | Exact match: filtered by `include: "*.java"`, returns `Found 2 matches`. |
| **P28** | `grep` | `file_grep` | **PASS** | Exact match: regex `fo+` with `include: "e*.txt"`, returns `Found 3 matches`. |
| **P29** | `grep` | `file_grep` | **PASS** | Exact match: `path` pointing to file searches containing directory, returns `Found 2 matches`. |
| **P30** | `grep` | `file_grep` | **PASS** | Exact match: non-matching search returns `No files found`. |
| **P31** | `grep` | `file_grep` | **PASS** | Exact match: invalid regex `(` returns ripgrep parse error verbatim. |
| **P32** | `bash` | `run_shell` | **PASS** | Exact match: output truncation at 2,000 lines / 51.2KB with spill file link and tail output. |
| **P33** | `bash` | `run_shell` | **PASS** | Exact match: non-zero exit code (42) returns stdout + stderr without error prefix. |
| **P34** | `bash` | `run_shell` | **PASS** | Exact match: timeout terminates command and emits `<shell_metadata>` block. |
| **P35** | `bash` | `run_shell` | **PASS** | Exact match: executes command in custom `workdir`. |
| **P36** | `bash` | `run_shell` | **PASS** | Exact match: large single-line output truncated cleanly with head + spill file. |
| **P37** | `webfetch` | `web_fetch` | **PASS** | Exact match: fetches HTML and converts to clean Markdown. |
| **P38** | `webfetch` | `web_fetch` | **PASS** | Exact match: fetches HTML and extracts plain text. |
| **P39** | `webfetch` | `web_fetch` | **PASS** | Exact match: fetches HTML in raw HTML mode. |
| **P40** | `webfetch` | `web_fetch` | **PASS** | Exact match: HTTP 404 returns formatted error status string. |

---

## 3. Tool Parity Analysis & Schema Size Comparison

### 3.1 Tool Schema Character Counts

Opencode tool definitions send 10 tools to the model with approximately **21,144 characters** of JSON schema.
NARU default tools (`naru-tools-fs`, `naru-tools-shell`, `naru-tools-web`, `naru-tools-plan`) send concise descriptions with approximate schema size of **~6,800 characters** (~3x smaller prompt overhead than Opencode).

### 3.2 Fixed Overhead Comparison

- **Opencode 1.18.35:**
  - System prompt: 9,544 characters
  - Tool schemas (10 tools): 21,144 characters
  - Total fixed overhead: **~30,688 characters (~7.7k tokens)**
- **NARU 1.1.0.0:**
  - System prompt: ~8,900 characters
  - Tool schemas (core equivalent tools): ~6,800 characters
  - Total fixed overhead: **~15,700 characters (~3.9k tokens)**

*Conclusion:* Opencode consumes approximately **2x** the fixed context overhead of NARU per turn before user instructions.

---

## 4. Key Behavioral Corrections Documented

1. **Read-Before-Edit & Read-Before-Write Not Enforced:**
   Opencode allowed blind edits (P09, P11) and blind writes (P17, P18) without reading the file first. NARU matches this behavior.
2. **Bash Exit Codes:**
   Opencode does not prefix or surface non-zero exit codes in the response body; it returns standard merged stdout/stderr. NARU `run_shell` matches this behavior.
3. **Glob Output Ordering & Output Formatting:**
   Opencode returns newline-delimited file paths for `glob` and returns `"No files found"` when empty. NARU `folder_find` now matches this.
4. **Binary & Non-existent File Error Messages:**
   NARU `file_read` returns `Cannot read binary file: <path>` and `File not found: <path>` verbatim.
5. **Web Fetch:**
   Added `web_fetch` with `url` and `format` (`markdown`, `text`, `html`), matching Opencode `webfetch`.

---

## 5. Automated Regression Test Suite

- `net.thevpc.naru.agent.ParityReplayTest`: Replays P01–P40 against active NARU instance. (Status: **PASS, 40/40**)
- `net.thevpc.naru.agent.NaruToolParityRegressionTest`:
  - `testSchemaVsImplementationNoCrashOnRequiredArgs`: Tests all registered tools with required schema arguments to verify zero unhandled crashes or schema drift. (Status: **PASS**)
  - `testFileEditParityRules`: Verifies ambiguity, identical match, and replaceAll rules. (Status: **PASS**)
  - `testFileWriteParityRules`: Verifies successful file write output and path handling. (Status: **PASS**)

---

## 6. Gate Status

**HARD GATE STOP**: Halting prior to Phase 2 benchmark runs (T0–T5). Awaiting human review of parity results and caveats.
