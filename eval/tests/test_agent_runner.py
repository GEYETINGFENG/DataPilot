import socket

import pytest

from helpers import make_question, sse
from datapilot_eval.db import InfraError
from datapilot_eval.runners.agent import AgentRunner


def test_takes_final_sql_from_done_and_counts_attempts(backend):
    backend.body = sse(
        ("conversation", {"conversationId": 7}),
        ("plan", {"text": "look at loans"}),
        ("tables", {"tables": ["loan", "account"]}),
        ("sql", {"step": 1, "attempt": 1, "sql": "SELECT amout FROM loan"}),
        ("sql_error", {"step": 1, "attempt": 1, "message": "Unknown column 'amout'"}),
        ("sql", {"step": 1, "attempt": 2, "sql": "SELECT amount FROM loan"}),
        ("result", {"step": 1, "attempt": 2, "columns": ["amount"], "rows": [[1]], "truncated": False, "elapsedMs": 3}),
        ("token", {"text": "The answer"}),
        ("done", {"finalSql": "SELECT amount FROM loan", "conclusion": "The answer"}),
    )
    generation = AgentRunner(backend.url, use_hint=True).generate(make_question(db_id="financial", evidence="e"))

    assert generation.sql == "SELECT amount FROM loan"
    assert generation.error is None
    assert generation.attempts == 2
    assert generation.extra == {"sql_errors": 1, "tables": [["loan", "account"]]}
    assert backend.requests == [{
        "path": "/api/analysis/stream",
        "json": {"question": "question 1?", "conversationId": None, "database": "financial", "hint": "e"},
    }]


def test_hint_can_be_turned_off(backend):
    AgentRunner(backend.url, use_hint=False).generate(make_question(evidence="secret"))
    assert backend.requests[0]["json"]["hint"] is None


def test_empty_evidence_is_sent_as_null(backend):
    AgentRunner(backend.url, use_hint=True).generate(make_question(evidence=""))
    assert backend.requests[0]["json"]["hint"] is None


def test_error_event_is_a_recorded_failure(backend):
    backend.body = sse(("conversation", {"conversationId": 1}), ("sql", {"sql": "x"}), ("error", {"message": "gave up"}))
    generation = AgentRunner(backend.url, use_hint=True).generate(make_question())
    assert generation.sql is None and generation.error == "agent error: gave up" and generation.attempts == 1


def test_stream_without_done(backend):
    backend.body = sse(("conversation", {"conversationId": 1}))
    generation = AgentRunner(backend.url, use_hint=True).generate(make_question())
    assert generation.sql is None and "without a done event" in generation.error


def test_done_without_final_sql(backend):
    backend.body = sse(("done", {"finalSql": None, "conclusion": "no data"}))
    generation = AgentRunner(backend.url, use_hint=True).generate(make_question())
    assert generation.sql is None and "finalSql" in generation.error


def test_http_error_is_infrastructure(backend):
    backend.status = 400
    backend.body = b'{"title": "Bad Request", "detail": "schema not allowed"}'
    with pytest.raises(InfraError, match="400.*schema not allowed"):
        AgentRunner(backend.url, use_hint=True).generate(make_question())


def test_unreachable_backend_is_infrastructure():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    with pytest.raises(InfraError, match="unreachable"):
        AgentRunner(f"http://127.0.0.1:{port}", use_hint=True).generate(make_question())
