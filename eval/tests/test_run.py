import json

import pytest

from helpers import make_question
from datapilot_eval import report
from datapilot_eval.db import InfraError, QueryError
from datapilot_eval.run import RunConfigMismatch, prepare_run_dir, run, write_summary
from datapilot_eval.runners import Generation


class ScriptedRunner:
    def __init__(self, answers):
        self.answers = answers  # question_id -> Generation or exception
        self.seen = []

    def generate(self, question):
        self.seen.append(question.question_id)
        answer = self.answers[question.question_id]
        if isinstance(answer, Exception):
            raise answer
        return answer


class TableExecutor:
    """SELECT <n> returns [(n,)]; anything else is a SQL error."""

    def execute(self, database, sql):
        if sql.startswith("SELECT "):
            return [(sql.removeprefix("SELECT "),)]
        raise QueryError(f"syntax error near {sql!r}")


QUESTIONS = [
    make_question(1, difficulty="simple", gold="SELECT 1"),
    make_question(2, difficulty="moderate", gold="SELECT 2"),
    make_question(3, difficulty="moderate", gold="SELECT 3"),
    make_question(4, difficulty="challenging", gold="SELECT 4"),
]


def test_run_scores_and_writes_jsonl(tmp_path):
    runner = ScriptedRunner({
        1: Generation("SELECT 1", attempts=1, prompt_tokens=100, completion_tokens=10),
        2: Generation("SELECT 9", attempts=1, prompt_tokens=100, completion_tokens=10),
        3: Generation("DELETE x", attempts=1, prompt_tokens=100, completion_tokens=10),
        4: Generation(None, error="agent error: gave up", attempts=3),
    })
    records, infra = run(QUESTIONS, runner, TableExecutor(), tmp_path, concurrency=2, log=lambda m: None)

    assert infra == 0
    by_id = {r["question_id"]: r for r in records}
    assert [by_id[i]["correct"] for i in (1, 2, 3, 4)] == [True, False, False, False]
    assert by_id[2]["error_kind"] is None
    assert by_id[3]["error_kind"] == "pred_error"
    assert by_id[4]["error_kind"] == "no_sql" and by_id[4]["error"] == "agent error: gave up"
    line = json.loads((tmp_path / "predictions.jsonl").read_text().splitlines()[0])
    assert {"question_id", "db_id", "difficulty", "predicted_sql", "correct", "error", "latency_s",
            "attempts", "prompt_tokens", "completion_tokens"} <= line.keys()

    summary = report.summarize(records, price_in=1.0, price_out=2.0)
    assert summary["by_difficulty"]["simple"] == {"count": 1, "correct": 1, "ex": 100.0}
    assert summary["by_difficulty"]["moderate"] == {"count": 2, "correct": 0, "ex": 0.0}
    assert summary["by_difficulty"]["total"] == {"count": 4, "correct": 1, "ex": 25.0}
    assert summary["errors"] == {"no_sql": 1, "pred_error": 1}
    assert summary["tokens"] == {"prompt": 300, "completion": 30}
    assert summary["cost_usd"] == pytest.approx((300 * 1.0 + 30 * 2.0) / 1e6, abs=1e-4)
    assert summary["attempts_mean"] == 1.5


def test_resume_skips_done_questions_and_retries_infra_errors(tmp_path):
    first = ScriptedRunner({1: Generation("SELECT 1"), 2: InfraError("LLM down"),
                            3: Generation("SELECT 3"), 4: Generation("SELECT 4")})
    records, infra = run(QUESTIONS, first, TableExecutor(), tmp_path, concurrency=1, log=lambda m: None)
    assert infra == 1 and sorted(r["question_id"] for r in records) == [1, 3, 4]

    # simulate a crash halfway through writing a line
    with (tmp_path / "predictions.jsonl").open("a") as f:
        f.write('{"question_id": 2, "corr')

    second = ScriptedRunner({2: Generation("SELECT 2")})
    records, infra = run(QUESTIONS, second, TableExecutor(), tmp_path, concurrency=1, log=lambda m: None)
    assert second.seen == [2]
    assert infra == 0 and len(records) == 4 and all(r["correct"] for r in records)


def test_stops_after_too_many_infra_errors(tmp_path):
    questions = [make_question(i) for i in range(1, 21)]
    runner = ScriptedRunner({i: InfraError("database down") for i in range(1, 21)})
    records, infra = run(questions, runner, TableExecutor(), tmp_path, concurrency=1,
                         max_infra_errors=3, log=lambda m: None)
    assert records == [] and infra == 3 and len(runner.seen) == 3


def test_limit_only_summarizes_selected_questions(tmp_path):
    runner = ScriptedRunner({i: Generation(f"SELECT {i}") for i in (1, 2, 3, 4)})
    run(QUESTIONS, runner, TableExecutor(), tmp_path, log=lambda m: None)
    records, _ = run(QUESTIONS[:2], runner, TableExecutor(), tmp_path, log=lambda m: None)
    assert [r["question_id"] for r in sorted(records, key=lambda r: r["question_id"])] == [1, 2]


def test_resume_refuses_a_different_configuration(tmp_path):
    meta = {"runner": "baseline", "model": "a", "evidence": False, "compare": "bird", "timeout_s": 30.0}
    prepare_run_dir(tmp_path, meta)
    assert prepare_run_dir(tmp_path, {**meta, "started_at": "later"}) == meta
    with pytest.raises(RunConfigMismatch, match="evidence"):
        prepare_run_dir(tmp_path, {**meta, "evidence": True})


def test_summary_markdown(tmp_path):
    records = [
        {"question_id": 1, "difficulty": "simple", "correct": True, "error_kind": None, "latency_s": 1.0},
        {"question_id": 2, "difficulty": "challenging", "correct": False, "error_kind": "pred_timeout", "latency_s": 3.0},
    ]
    markdown = write_summary(tmp_path, records, {"name": "t", "runner": "baseline", "model": "m"})
    assert "| EX (%) | 100.0 | n/a | 0.0 | 50.0 |" in markdown
    assert "pred_timeout" in markdown and "estimated cost: n/a" in markdown
    assert json.loads((tmp_path / "summary.json").read_text())["latency_s"]["total"] == 4.0
