"""A stand-in for Anthropic's Messages API on the PC, for the phone's plumbing check (`ai-check`, free).

    python scripts/claude_stub.py [port]          # 8089 by default
    adb reverse tcp:8089 tcp:8089
    adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd ai-check --es base http://127.0.0.1:8089

Every POST gets a small, well-formed message whose text is an empty JSON object, so each reader on the
phone sends through the real client and reads an answer back, which its own parser then finds empty.
Nothing reaches Anthropic and nothing is kept: each request is logged by its path and size only, never
its body, which can hold the tasks' text. Stop it with Ctrl+C.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

ANSWER = {
    "id": "msg_stand_in",
    "type": "message",
    "role": "assistant",
    "model": "claude-opus-5-5",
    "content": [{"type": "text", "text": "{}"}],
    "stop_reason": "end_turn",
    "stop_sequence": None,
    "usage": {"input_tokens": 1, "output_tokens": 1},
}


class StandIn(BaseHTTPRequestHandler):
    def do_POST(self):
        size = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(size)
        body = json.dumps(ANSWER).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # The path and the request's size alone: never a header (the key) or the body (the tasks).
        print(f"{self.command} {self.path} {self.headers.get('Content-Length') or 0} bytes", flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8089
    print(f"A stand-in for the Messages API on http://127.0.0.1:{port}", flush=True)
    HTTPServer(("127.0.0.1", port), StandIn).serve_forever()
