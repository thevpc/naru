#!/usr/bin/env python3
"""
Mock OpenAI server on port 8089 to capture exact request bodies for /min and /granted.
"""
import sys
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

captured = {}

class MockHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_len = int(self.headers.get('Content-Length', 0))
        post_body = self.rfile.read(content_len)
        data = json.loads(post_body.decode('utf-8'))

        if "/min" in self.path:
            captured["min"] = data
        elif "/granted" in self.path:
            captured["granted"] = data

        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        
        chunk1 = {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"OK"}}]}
        chunk2 = {"id":"chatcmpl-2","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
        self.wfile.write(f"data: {json.dumps(chunk1)}\n\n".encode('utf-8'))
        self.wfile.write(f"data: {json.dumps(chunk2)}\n\n".encode('utf-8'))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({"object":"list","data":[{"id":"qwen3.8:32k","object":"model"}]}).encode('utf-8'))

    def log_message(self, format, *args):
        pass

def run():
    server = HTTPServer(('127.0.0.1', 8089), MockHandler)
    server.timeout = 20
    # Wait until both 'min' and 'granted' requests are captured
    while len(captured) < 2:
        server.handle_request()
    
    with open('bench/captured_wire_min.json', 'w') as f:
        json.dump(captured["min"], f, indent=2)
    with open('bench/captured_wire_granted.json', 'w') as f:
        json.dump(captured["granted"], f, indent=2)
    print("Successfully captured both min and granted requests!")

if __name__ == '__main__':
    run()
