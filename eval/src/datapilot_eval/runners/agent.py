"""Agent: ask the DataPilot backend (`POST /api/analysis/stream`, see docs/analysis-api.md) and
take `finalSql` from the `done` event."""

from __future__ import annotations

import json
from collections.abc import Iterable, Iterator

import httpx

from ..dataset import Question
from ..db import InfraError
from . import Generation


def iter_sse(lines: Iterable[str]) -> Iterator[tuple[str, str]]:
    """Parse a text/event-stream into (event, data) pairs, per the WHATWG EventSource rules."""
    event, data = "", []
    for line in lines:
        line = line.rstrip("\r\n")
        if not line:
            if data:
                yield event or "message", "\n".join(data)
            event, data = "", []
            continue
        if line.startswith(":"):
            continue
        name, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if name == "event":
            event = value
        elif name == "data":
            data.append(value)
    if data:
        yield event or "message", "\n".join(data)


class AgentRunner:
    def __init__(self, base_url: str, use_hint: bool, timeout_s: float = 300.0,
                 transport: httpx.BaseTransport | None = None):
        self.url = base_url.rstrip("/") + "/api/analysis/stream"
        self.use_hint = use_hint
        self.timeout_s = timeout_s
        self.transport = transport

    def generate(self, question: Question) -> Generation:
        body = {
            "question": question.question,
            "conversationId": None,
            "database": question.db_id,
            "hint": (question.evidence or None) if self.use_hint else None,
        }
        attempts = sql_errors = 0
        tables: list[list[str]] = []
        try:
            with httpx.Client(timeout=httpx.Timeout(self.timeout_s, connect=10), transport=self.transport) as client, \
                    client.stream("POST", self.url, json=body, headers={"Accept": "text/event-stream"}) as response:
                if response.status_code != 200:
                    raise InfraError(f"backend answered {response.status_code}: {response.read().decode(errors='replace')[:500]}")
                for event, raw in iter_sse(response.iter_lines()):
                    data = json.loads(raw)
                    if event == "sql":
                        attempts += 1
                    elif event == "sql_error":
                        sql_errors += 1
                    elif event == "tables":
                        tables.append(data.get("tables", []))
                    elif event in ("done", "error"):
                        extra = {"sql_errors": sql_errors, "tables": tables}
                        if event == "error":
                            return Generation(None, f"agent error: {data.get('message')}", attempts, extra=extra)
                        sql = data.get("finalSql")
                        return Generation(sql or None, None if sql else "done event without finalSql", attempts, extra=extra)
        except httpx.ReadTimeout:
            return Generation(None, f"agent timed out after {self.timeout_s:g}s", attempts)
        except (httpx.ConnectError, httpx.RemoteProtocolError) as e:
            raise InfraError(f"backend unreachable at {self.url}: {e}") from e
        except json.JSONDecodeError as e:
            return Generation(None, f"malformed SSE data: {e}", attempts)
        return Generation(None, "stream ended without a done event", attempts)
