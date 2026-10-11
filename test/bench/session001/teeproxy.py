#!/usr/bin/env python3
"""
bench/teeproxy.py - HTTP Tee Proxy for LLM Agent Benchmarking
Python 3 stdlib only.

Listens on a local port. The first path segment is the run ID:
  baseURL = http://127.0.0.1:<port>/<run-id>/v1
Forwards to the configured upstream (injecting API key if needed).
Logs full request and reassembled SSE response to bench/data/<run-id>.jsonl
Streaming responses are forwarded in real time without full buffering.
"""

import sys
import os
import time
import json
import argparse
import urllib.request
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler

DEFAULT_PORT = 8088
DEFAULT_UPSTREAM = "http://127.0.0.1:11434"
DEFAULT_DATA_DIR = os.path.join(os.path.dirname(__file__), "data")

UPSTREAM_URL = DEFAULT_UPSTREAM
UPSTREAM_API_KEY = ""
DATA_DIR = DEFAULT_DATA_DIR


def classify_request(req_json):
    if not isinstance(req_json, dict):
        return "other"
    if "tools" in req_json and req_json.get("tools"):
        return "main"
    messages = req_json.get("messages", [])
    for m in messages:
        if m.get("role") == "system":
            content = m.get("content", "")
            if isinstance(content, str) and content.strip().startswith("You are a title generator"):
                return "title"
    return "other"


class TeeProxyHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        start_time = time.time()
        # Parse path: /<run-id>/<rest...>
        path_parts = self.path.lstrip("/").split("/", 1)
        if len(path_parts) == 2:
            run_id = path_parts[0]
            forward_path = "/" + path_parts[1]
        elif len(path_parts) == 1 and path_parts[0]:
            run_id = path_parts[0]
            forward_path = "/"
        else:
            run_id = "default"
            forward_path = self.path

        # Read request body
        content_length = int(self.headers.get("Content-Length", 0))
        req_bytes = self.rfile.read(content_length)
        orig_bytes = req_bytes
        orig_json = None
        removed_fields = []
        try:
            req_json = json.loads(req_bytes.decode("utf-8"))
            orig_json = json.loads(req_bytes.decode("utf-8"))
            if isinstance(req_json, dict) and "store" in req_json:
                removed_fields.append("store")
                del req_json["store"]
                req_bytes = json.dumps(req_json).encode("utf-8")
        except Exception:
            pass

        req_kind = classify_request(req_json)

        # Build upstream URL
        target_url = UPSTREAM_URL.rstrip("/") + forward_path

        # Prepare headers for upstream
        req_headers = {}
        for k, v in self.headers.items():
            k_lower = k.lower()
            if k_lower in ("host", "content-length", "accept-encoding"):
                continue
            req_headers[k] = v

        if UPSTREAM_API_KEY:
            req_headers["Authorization"] = f"Bearer {UPSTREAM_API_KEY}"

        upstream_req = urllib.request.Request(
            target_url,
            data=req_bytes,
            headers=req_headers,
            method="POST"
        )

        status_code = 500
        resp_bytes_total = 0
        sse_events = []
        final_text = ""
        reasoning_text = ""
        tool_calls_map = {}
        usage_data = {}
        error_msg = None

        try:
            with urllib.request.urlopen(upstream_req, timeout=600) as upstream_resp:
                status_code = upstream_resp.status
                content_type = upstream_resp.headers.get("Content-Type", "")

                # Send response headers to client
                self.send_response(status_code)
                for hk, hv in upstream_resp.headers.items():
                    if hk.lower() in ("transfer-encoding", "content-length"):
                        continue
                    self.send_header(hk, hv)
                self.end_headers()

                is_sse = "text/event-stream" in content_type

                if is_sse:
                    line_buffer = b""
                    while True:
                        chunk = upstream_resp.read(1024)
                        if not chunk:
                            break
                        resp_bytes_total += len(chunk)
                        self.wfile.write(chunk)
                        self.wfile.flush()

                        line_buffer += chunk
                        while b"\n" in line_buffer:
                            line, line_buffer = line_buffer.split(b"\n", 1)
                            line_str = line.decode("utf-8", errors="replace").strip()
                            if line_str.startswith("data: "):
                                data_payload = line_str[6:].strip()
                                if data_payload == "[DONE]":
                                    continue
                                try:
                                    event_obj = json.loads(data_payload)
                                    sse_events.append(event_obj)
                                    # Accumulate usage
                                    if "usage" in event_obj and event_obj["usage"]:
                                        usage_data = event_obj["usage"]
                                    choices = event_obj.get("choices", [])
                                    if choices:
                                        delta = choices[0].get("delta", {})
                                        if "content" in delta and delta["content"]:
                                            final_text += delta["content"]
                                        if "reasoning" in delta and delta["reasoning"]:
                                            reasoning_text += delta["reasoning"]
                                        if "reasoning_content" in delta and delta["reasoning_content"]:
                                            reasoning_text += delta["reasoning_content"]
                                        if "tool_calls" in delta:
                                            for tc in delta["tool_calls"]:
                                                idx = tc.get("index", 0)
                                                if idx not in tool_calls_map:
                                                    tool_calls_map[idx] = {
                                                        "id": tc.get("id", ""),
                                                        "type": tc.get("type", "function"),
                                                        "function": {
                                                            "name": tc.get("function", {}).get("name", ""),
                                                            "arguments": tc.get("function", {}).get("arguments", "")
                                                        }
                                                    }
                                                else:
                                                    if "id" in tc and tc["id"]:
                                                        tool_calls_map[idx]["id"] = tc["id"]
                                                    fn = tc.get("function", {})
                                                    if "name" in fn and fn["name"]:
                                                        tool_calls_map[idx]["function"]["name"] += fn["name"]
                                                    if "arguments" in fn and fn["arguments"]:
                                                        tool_calls_map[idx]["function"]["arguments"] += fn["arguments"]
                                except Exception:
                                    pass
                else:
                    body = upstream_resp.read()
                    resp_bytes_total = len(body)
                    self.wfile.write(body)
                    self.wfile.flush()
                    try:
                        non_sse_obj = json.loads(body.decode("utf-8"))
                        if "usage" in non_sse_obj:
                            usage_data = non_sse_obj["usage"]
                        choices = non_sse_obj.get("choices", [])
                        if choices:
                            msg = choices[0].get("message", {})
                            final_text = msg.get("content", "") or ""
                            reasoning_text = msg.get("reasoning", "") or msg.get("reasoning_content", "") or ""
                            if "tool_calls" in msg and msg["tool_calls"]:
                                for i, tc in enumerate(msg["tool_calls"]):
                                    tool_calls_map[i] = tc
                    except Exception:
                        pass

        except urllib.error.HTTPError as e:
            status_code = e.code
            err_body = e.read()
            resp_bytes_total = len(err_body)
            try:
                self.send_response(status_code)
                for hk, hv in e.headers.items():
                    if hk.lower() in ("transfer-encoding", "content-length"):
                        continue
                    self.send_header(hk, hv)
                self.end_headers()
                self.wfile.write(err_body)
            except (BrokenPipeError, ConnectionResetError):
                pass
            error_msg = f"HTTP {status_code}: {err_body.decode('utf-8', errors='replace')}"
        except (BrokenPipeError, ConnectionResetError):
            status_code = 499
            error_msg = "Client closed connection"
        except Exception as e:
            status_code = 502
            error_msg = str(e)
            try:
                self.send_response(502)
                self.end_headers()
                self.wfile.write(f'{{"error": "{error_msg}"}}'.encode("utf-8"))
            except (BrokenPipeError, ConnectionResetError):
                pass

        latency = time.time() - start_time

        # Compile tool_calls list
        tool_calls = [tool_calls_map[k] for k in sorted(tool_calls_map.keys())]

        # Log entry
        log_entry = {
            "timestamp": time.time(),
            "run_id": run_id,
            "latency_s": round(latency, 3),
            "status_code": status_code,
            "kind": req_kind,
            "target_url": target_url,
            "request_bytes": len(orig_bytes),
            "response_bytes": resp_bytes_total,
            "request_body": orig_json,
            "removed_fields": removed_fields,
            "response_summary": {
                "text": final_text,
                "reasoning": reasoning_text,
                "tool_calls": tool_calls,
                "usage": usage_data,
                "error": error_msg
            }
        }

        os.makedirs(DATA_DIR, exist_ok=True)
        log_path = os.path.join(DATA_DIR, f"{run_id}.jsonl")
        try:
            with open(log_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(log_entry) + "\n")
        except Exception as ex:
            sys.stderr.write(f"Failed to log entry: {ex}\n")

    def do_GET(self):
        # Health check or model listing pass-through
        path_parts = self.path.lstrip("/").split("/", 1)
        forward_path = "/" + path_parts[1] if len(path_parts) == 2 else self.path
        target_url = UPSTREAM_URL.rstrip("/") + forward_path

        req_headers = {}
        for k, v in self.headers.items():
            if k.lower() not in ("host", "content-length"):
                req_headers[k] = v
        if UPSTREAM_API_KEY:
            req_headers["Authorization"] = f"Bearer {UPSTREAM_API_KEY}"

        try:
            req = urllib.request.Request(target_url, headers=req_headers, method="GET")
            with urllib.request.urlopen(req, timeout=30) as resp:
                self.send_response(resp.status)
                for hk, hv in resp.headers.items():
                    if hk.lower() not in ("transfer-encoding", "content-length"):
                        self.send_header(hk, hv)
                self.end_headers()
                self.wfile.write(resp.read())
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            self.end_headers()
            self.wfile.write(e.read())
        except Exception as e:
            self.send_response(502)
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))

    def log_message(self, format, *args):
        # Suppress noisy standard logging to stdout
        pass


def run_server(port, upstream, api_key, data_dir):
    global UPSTREAM_URL, UPSTREAM_API_KEY, DATA_DIR
    UPSTREAM_URL = upstream
    UPSTREAM_API_KEY = api_key
    DATA_DIR = data_dir

    server = HTTPServer(("127.0.0.1", port), TeeProxyHandler)
    sys.stderr.write(f"TeeProxy listening on 127.0.0.1:{port} -> {UPSTREAM_URL}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="TeeProxy for LLM benchmarks")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to listen on")
    parser.add_argument("--upstream", default=DEFAULT_UPSTREAM, help="Upstream base URL")
    parser.add_argument("--api-key", default=os.environ.get("GEMINI_API_KEY", ""), help="Upstream API key")
    parser.add_argument("--data-dir", default=DEFAULT_DATA_DIR, help="Directory to store jsonl logs")
    args = parser.parse_args()

    run_server(args.port, args.upstream, args.api_key, args.data_dir)
