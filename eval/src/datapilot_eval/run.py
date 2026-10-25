"""Run a runner over the questions, score each prediction, and write results as they complete.

A run directory holds:
  run.json            configuration of the run (checked when resuming)
  predictions.jsonl   one line per scored question, appended as soon as it is done
  summary.md/.json    written at the end
"""

from __future__ import annotations

import json
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from . import report
from .dataset import Question
from .db import InfraError
from .runners import Runner
from .scorer import SqlExecutor, score

# Fields that must match for a run directory to be resumed
RESUME_KEYS = ("runner", "model", "evidence", "compare", "timeout_s", "backend_url", "dataset_sha256")


class RunConfigMismatch(Exception):
    pass


def prepare_run_dir(out: Path, meta: dict) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    meta_path = out / "run.json"
    if meta_path.exists():
        previous = json.loads(meta_path.read_text())
        diff = {k: (previous.get(k), meta.get(k)) for k in RESUME_KEYS if previous.get(k) != meta.get(k)}
        if diff:
            raise RunConfigMismatch(
                f"{out} holds a run with a different configuration {diff}; use another --out or delete it"
            )
        return previous
    meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n")
    return meta


def evaluate_one(question: Question, runner: Runner, executor: SqlExecutor, compare: str) -> dict:
    started = time.monotonic()
    generation = runner.generate(question)
    latency = time.monotonic() - started
    if generation.error and not generation.sql:
        correct, error_kind, error, exec_s = False, "no_sql", generation.error, 0.0
    else:
        result = score(executor, question.db_id, generation.sql, question.gold_sql, compare)
        correct, error_kind, error, exec_s = result.correct, result.error_kind, result.error, result.exec_s
    return {
        "question_id": question.question_id,
        "db_id": question.db_id,
        "difficulty": question.difficulty,
        "predicted_sql": generation.sql,
        "correct": correct,
        "error_kind": error_kind,
        "error": error,
        "latency_s": round(latency, 3),
        "exec_s": round(exec_s, 3),
        "attempts": generation.attempts,
        "prompt_tokens": generation.prompt_tokens,
        "completion_tokens": generation.completion_tokens,
        "extra": generation.extra,
    }


def _drop_torn_tail(path: Path) -> None:
    """Cut a partial last line left by an interrupted run, so appended records stay on their own line."""
    if not path.exists():
        return
    data = path.read_bytes()
    if data and not data.endswith(b"\n"):
        path.write_bytes(data[: data.rfind(b"\n") + 1])


def run(questions: list[Question], runner: Runner, executor: SqlExecutor, out: Path, *,
        compare: str = "bird", concurrency: int = 4, max_infra_errors: int = 5,
        log=lambda msg: print(msg, file=sys.stderr)) -> tuple[list[dict], int]:
    """Evaluate every question not yet in predictions.jsonl. Returns (records of `questions`, infra errors)."""
    predictions = out / "predictions.jsonl"
    _drop_torn_tail(predictions)
    done = {r["question_id"] for r in report.read_records(predictions)}
    todo = [q for q in questions if q.question_id not in done]
    if done:
        log(f"resuming: {len(questions) - len(todo)} of {len(questions)} already done")

    lock = threading.Lock()
    stop = threading.Event()
    infra_errors = finished = 0

    def task(q: Question) -> dict | None:
        nonlocal infra_errors
        if stop.is_set():
            return None
        try:
            return evaluate_one(q, runner, executor, compare)
        except InfraError as e:
            with lock:
                infra_errors += 1
                log(f"[{q.question_id}] not recorded, will be retried on resume: {e}")
                if infra_errors >= max_infra_errors and not stop.is_set():
                    log(f"stopping after {infra_errors} infrastructure errors")
                    stop.set()
            return None

    with predictions.open("a", encoding="utf-8") as f, ThreadPoolExecutor(max_workers=concurrency) as pool:
        for future in as_completed([pool.submit(task, q) for q in todo]):
            record = future.result()
            if record is None:
                continue
            f.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")
            f.flush()
            finished += 1
            if finished % 10 == 0 or finished == len(todo):
                log(f"{finished}/{len(todo)} done")

    wanted = {q.question_id for q in questions}
    records = [r for r in report.read_records(predictions) if r["question_id"] in wanted]
    return records, infra_errors


def write_summary(out: Path, records: list[dict], meta: dict,
                  price_in: float | None = None, price_out: float | None = None) -> str:
    summary = report.summarize(records, price_in, price_out)
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    markdown = report.render_markdown(summary, meta)
    (out / "summary.md").write_text(markdown)
    return markdown
