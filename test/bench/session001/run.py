#!/usr/bin/env python3
"""
bench/run.py - Automated Benchmark Runner for NARU and Opencode
Runs matrix cells sequentially: (agent, model, task, run_index)
Collects metrics via TeeProxy and saves per-run JSON records.
"""

import os
import sys
import time
import json
import shutil
import tempfile
import subprocess
from pathlib import Path

REPO_DIR = Path(__file__).resolve().parent.parent
BENCH_DIR = REPO_DIR / "bench"
DATA_DIR = BENCH_DIR / "data"
RESULTS_DIR = BENCH_DIR / "results"
FIXTURES_DIR = BENCH_DIR / "fixtures"

PROMPTS = {
    "t0": "Reply with the single word OK.",
    "t1": "Count the TODO comments under src/ and write todo-report.txt: first line the count, then the sorted relative file paths, one per line.",
    "t2": "One unit test in tests/ is failing. Find the root cause, fix the source code in evaluator/, and run tests until all pass. Do not edit files in tests/.",
    "t3": "Add a --format=json option to the tool: validate input records in validator.py, format output as a JSON string in formatter.py, expose the option in cli.py, and add unit tests for it under tests/.",
    "t4": "Rename the public function calculate_hash to compute_digest across all files, tests, and documentation. Preserve existing behavior and ensure all tests pass.",
    "t5": "Fetch and read the specification at http://127.0.0.1:8099/spec.html, and implement the module in slugifier/slug.py matching all requirements from the spec."
}

def get_versions():
    # Opencode version
    try:
        oc_v = subprocess.check_output(["opencode", "--version"], text=True).strip()
    except Exception:
        oc_v = "unknown"
    # NARU git sha
    try:
        naru_sha = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=str(REPO_DIR), text=True).strip()
    except Exception:
        naru_sha = "unknown"
    return oc_v, naru_sha

def run_cell(agent, model, task, run_index):
    run_id = f"{agent}_{model.replace(':', '_').replace('/', '_')}_{task}_{run_index}"
    print(f"\n========================================================")
    print(f"STARTING RUN: {run_id}")
    print(f"Agent: {agent} | Model: {model} | Task: {task} | Index: {run_index}")
    print(f"========================================================")

    oc_v, naru_sha = get_versions()
    agent_version = naru_sha if agent == "naru" else oc_v

    # 1. Fresh temp dir
    work_dir = Path(tempfile.mkdtemp(prefix=f"bench-{agent}-{task}-{run_index}-"))
    fixture_src = FIXTURES_DIR / task

    # Copy fixture files if any (excluding accept.sh)
    if fixture_src.exists():
        for item in fixture_src.iterdir():
            if item.name == "accept.sh":
                continue
            dest = work_dir / item.name
            if item.is_dir():
                shutil.copytree(item, dest)
            else:
                shutil.copy2(item, dest)

    # Git init
    subprocess.run(["git", "init", "-q"], cwd=str(work_dir), check=True)
    subprocess.run(["git", "add", "."], cwd=str(work_dir), check=True)
    subprocess.run(["git", "commit", "--allow-empty", "-m", "initial", "-q"], cwd=str(work_dir), check=True)

    proxy_url = f"http://127.0.0.1:8088/{run_id}/v1"
    prompt = PROMPTS[task]

    start_wall = time.time()
    exit_code = 0
    hit_cap = False

    # 2. Execute agent
    if agent == "opencode":
        # Create opencode.json in work_dir
        oc_cfg = {
            "$schema": "https://opencode.ai/config.json",
            "provider": {
                "bench": {
                    "name": "Benchmark via TeeProxy",
                    "npm": "@ai-sdk/openai-compatible",
                    "options": {
                        "baseURL": proxy_url
                    },
                    "models": {
                        model: {
                            "name": model
                        }
                    }
                }
            }
        }
        cfg_dir = work_dir / ".config" / "opencode"
        cfg_dir.mkdir(parents=True, exist_ok=True)
        with open(cfg_dir / "opencode.json", "w") as f:
            json.dump(oc_cfg, f, indent=2)

        env = os.environ.copy()
        env["HOME"] = str(work_dir)
        env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        env["OPENCODE_DISABLE_AUTOCOMPACT"] = "1"

        cmd = [
            "opencode", "run",
            "--standalone",
            "--auto",
            "--format", "json",
            "-m", f"bench/{model}",
            prompt
        ]
        wall_timeout = 600 if "gemini" in model.lower() else 1200
        print(f"Running command (timeout {wall_timeout}s): {' '.join(cmd)}")
        try:
            proc = subprocess.run(cmd, cwd=str(work_dir), env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=wall_timeout)
            exit_code = proc.returncode
            print(f"Opencode finished with exit code {exit_code}")
            if exit_code != 0:
                print("Opencode stderr:", proc.stderr[:500])
        except subprocess.TimeoutExpired:
            print(f"Opencode TIMED OUT after {wall_timeout}s cap")
            exit_code = -1
            hit_cap = True

    elif agent == "naru":
        cp_cmd = subprocess.check_output(["bash", str(BENCH_DIR / "full_cp.sh")], text=True).strip()
        full_cp = f"{cp_cmd}:{str(REPO_DIR)}"

        cmd = [
            "java", "-cp", full_cp,
            "bench.NaruHeadlessRunner",
            str(work_dir),
            proxy_url,
            model,
            "min",
            prompt
        ]
        wall_timeout = 600 if "gemini" in model.lower() else 1200
        print(f"Running NARU headless (timeout {wall_timeout}s)...")
        env = os.environ.copy()
        if "gemini" not in model.lower():
            env.pop("GEMINI_API_KEY", None)
            env.pop("GOOGLE_API_KEY", None)
        try:
            proc = subprocess.run(cmd, cwd=str(work_dir), env=env, capture_output=True, text=True, timeout=wall_timeout)
            exit_code = proc.returncode
            print(f"NARU finished with exit code {exit_code}")
            if exit_code != 0:
                print("NARU stderr:", proc.stderr[:500])
        except subprocess.TimeoutExpired:
            print(f"NARU TIMED OUT after {wall_timeout}s cap")
            exit_code = -1
            hit_cap = True

    wall_s = round(time.time() - start_wall, 3)

    # 3. Acceptance verification
    accept_script = fixture_src / "accept.sh"
    pass_status = False
    accept_output = ""
    if accept_script.exists():
        acc_proc = subprocess.run(["bash", str(accept_script)], cwd=str(work_dir), capture_output=True, text=True)
        pass_status = (acc_proc.returncode == 0)
        accept_output = (acc_proc.stdout + acc_proc.stderr).strip()
    else:
        pass_status = (exit_code == 0)
        accept_output = "No accept script"

    print(f"Task {task} Pass: {pass_status} | Output: {accept_output}")

    # 4. Diff / changes in work_dir
    diff_proc = subprocess.run(["git", "status", "--short"], cwd=str(work_dir), capture_output=True, text=True)
    files_changed = len([l for l in diff_proc.stdout.splitlines() if l.strip()])

    # 5. Parse teeproxy jsonl logs
    log_file = DATA_DIR / f"{run_id}.jsonl"
    requests_main = 0
    requests_title = 0
    requests_other = 0
    prompt_tokens = 0
    completion_tokens = 0
    reasoning_tokens = 0
    cached_prompt_tokens = 0
    peak_prompt_tokens = 0
    request_bytes_total = 0
    tool_calls_by_name = {}
    final_text = ""

    if log_file.exists():
        with open(log_file, "r", encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                entry = json.loads(line)
                kind = entry.get("kind", "other")
                if kind == "main":
                    requests_main += 1
                elif kind == "title":
                    requests_title += 1
                else:
                    requests_other += 1

                req_bytes = entry.get("request_bytes", 0)
                request_bytes_total += req_bytes

                resp_sum = entry.get("response_summary", {})
                usage = resp_sum.get("usage", {})
                p_tok = usage.get("prompt_tokens", 0)
                c_tok = usage.get("completion_tokens", 0)
                prompt_tokens += p_tok
                completion_tokens += c_tok
                if p_tok > peak_prompt_tokens:
                    peak_prompt_tokens = p_tok

                tcs = resp_sum.get("tool_calls", [])
                for tc in tcs:
                    tname = tc.get("function", {}).get("name", "unknown")
                    tool_calls_by_name[tname] = tool_calls_by_name.get(tname, 0) + 1

                txt = resp_sum.get("text", "")
                if txt:
                    final_text = txt

    result_record = {
        "run_id": run_id,
        "agent": agent,
        "agent_version": agent_version,
        "model": model,
        "task": task,
        "run_index": run_index,
        "pass": pass_status,
        "accept_output": accept_output,
        "wall_s": wall_s,
        "exit_code": exit_code,
        "hit_cap": hit_cap,
        "requests_main": requests_main,
        "requests_title": requests_title,
        "requests_other": requests_other,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "reasoning_tokens": reasoning_tokens,
        "cached_prompt_tokens": cached_prompt_tokens,
        "peak_prompt_tokens_single_request": peak_prompt_tokens,
        "tool_calls_by_name": tool_calls_by_name,
        "request_bytes_total": request_bytes_total,
        "files_changed": files_changed,
        "final_text": final_text[:500]
    }

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    res_path = RESULTS_DIR / f"{run_id}.json"
    with open(res_path, "w", encoding="utf-8") as f:
        json.dump(result_record, f, indent=2)

    print(f"Saved result: {res_path}")
    print(json.dumps(result_record, indent=2))

    # Cleanup temp dir
    shutil.rmtree(work_dir, ignore_errors=True)
    return result_record

import argparse

def main():
    parser = argparse.ArgumentParser(description="Run benchmark matrix")
    parser.add_argument("--tasks", nargs="+", default=["t0", "t1", "t2", "t3", "t4", "t5"], help="Tasks to run")
    parser.add_argument("--models", nargs="+", default=["qwen3.8:32k"], help="Models to run")
    parser.add_argument("--agents", nargs="+", default=["naru", "opencode"], help="Agents to run")
    parser.add_argument("--runs", type=int, default=1, help="Number of repetitions per cell")
    parser.add_argument("--skip-existing", action="store_true", help="Skip runs that already have a result file")
    args = parser.parse_args()

    summary_path = BENCH_DIR / "benchmark_summary.json"
    existing_run_ids = {}
    if summary_path.exists():
        try:
            with open(summary_path) as f:
                for r in json.load(f):
                    existing_run_ids[r["run_id"]] = r
        except Exception:
            pass

    for task in args.tasks:
        for run_idx in range(args.runs):
            for model in args.models:
                for agent in args.agents:
                    run_id = f"{agent}_{model.replace(':', '_').replace('/', '_')}_{task}_{run_idx}"
                    res_file = RESULTS_DIR / f"{run_id}.json"
                    if args.skip_existing and res_file.exists():
                        print(f"\n[SKIP] Result already exists for {run_id}")
                        try:
                            with open(res_file) as f:
                                existing_run_ids[run_id] = json.load(f)
                        except Exception:
                            pass
                        continue

                    res = run_cell(agent, model, task, run_idx)
                    existing_run_ids[run_id] = res

                    with open(summary_path, "w") as f:
                        json.dump(list(existing_run_ids.values()), f, indent=2)
                    time.sleep(2)

    print("\n\n========================================================")
    print("BENCHMARK MATRIX COMPLETED")
    print("========================================================")
    print(f"Summary saved to {summary_path}")

if __name__ == "__main__":
    main()
