import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from datapilot_eval.dataset import Question


def make_question(qid=1, db_id="financial", difficulty="simple", evidence="ratio = a / b", gold="SELECT 1"):
    return Question(qid, db_id, f"question {qid}?", evidence, gold, difficulty)


def sse(*events):
    """Encode (event, data) pairs as an SSE body."""
    return "".join(f"event: {e}\ndata: {json.dumps(d)}\n\n" for e, d in events).encode()


class FakeBackend:
    """A tiny stand-in for DataPilot's POST /api/analysis/stream."""

    def __init__(self):
        self.requests = []
        self.status = 200
        self.body = sse(("conversation", {"conversationId": 1}), ("done", {"finalSql": "SELECT 1", "conclusion": ""}))
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                backend.requests.append({"path": self.path, "json": json.loads(self.rfile.read(length))})
                self.send_response(backend.status)
                ctype = "text/event-stream" if backend.status == 200 else "application/problem+json"
                self.send_header("Content-Type", ctype)
                self.end_headers()
                self.wfile.write(backend.body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
