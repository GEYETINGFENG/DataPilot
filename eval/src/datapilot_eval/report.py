"""Summaries of a run: EX by difficulty, errors, latency, tokens and cost."""

from __future__ import annotations

import json
import statistics
from collections import Counter
from pathlib import Path

from .dataset import DIFFICULTIES


def read_records(path: Path) -> list[dict]:
    """Read predictions.jsonl, ignoring a torn last line from an interrupted run."""
    if not path.exists():
        return []
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return records


def _pct(part: int, whole: int) -> float | None:
    return round(100 * part / whole, 2) if whole else None


def summarize(records: list[dict], price_in: float | None = None, price_out: float | None = None) -> dict:
    by_difficulty = {}
    for level in (*DIFFICULTIES, "total"):
        rows = records if level == "total" else [r for r in records if r["difficulty"] == level]
        correct = sum(1 for r in rows if r["correct"])
        by_difficulty[level] = {"count": len(rows), "correct": correct, "ex": _pct(correct, len(rows))}

    latencies = [r["latency_s"] for r in records if r.get("latency_s") is not None]
    prompt_tokens = [r["prompt_tokens"] for r in records if r.get("prompt_tokens") is not None]
    completion_tokens = [r["completion_tokens"] for r in records if r.get("completion_tokens") is not None]
    attempts = [r["attempts"] for r in records if r.get("attempts") is not None]

    cost = None
    if price_in is not None and price_out is not None and prompt_tokens:
        cost = round((sum(prompt_tokens) * price_in + sum(completion_tokens) * price_out) / 1e6, 4)

    return {
        "by_difficulty": by_difficulty,
        "errors": dict(sorted(Counter(r["error_kind"] for r in records if r.get("error_kind")).items())),
        "latency_s": {
            "total": round(sum(latencies), 1),
            "mean": round(statistics.fmean(latencies), 2) if latencies else None,
            "p50": round(statistics.median(latencies), 2) if latencies else None,
            "p90": round(_quantile(latencies, 0.9), 2) if latencies else None,
        },
        "tokens": {
            "prompt": sum(prompt_tokens) if prompt_tokens else None,
            "completion": sum(completion_tokens) if completion_tokens else None,
        },
        "attempts_mean": round(statistics.fmean(attempts), 2) if attempts else None,
        "cost_usd": cost,
    }


def _quantile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(q * len(ordered)))]


def _fmt(value, suffix: str = "") -> str:
    return "n/a" if value is None else f"{value}{suffix}"


def render_markdown(summary: dict, meta: dict) -> str:
    lines = [
        f"# {meta.get('name', 'run')}",
        "",
        f"- runner: `{meta.get('runner')}`, model: `{meta.get('model')}`, evidence: `{meta.get('evidence')}`",
        f"- compare: `{meta.get('compare')}`, query timeout: {meta.get('timeout_s')} s, dataset sha256: `{meta.get('dataset_sha256')}`",
        f"- started: {meta.get('started_at')}, git: `{meta.get('git_commit')}`",
        f"- command: `{meta.get('command')}`",
        "",
        "| | simple | moderate | challenging | total |",
        "|---|---|---|---|---|",
    ]
    levels = (*DIFFICULTIES, "total")
    bd = summary["by_difficulty"]
    lines.append("| count | " + " | ".join(str(bd[x]["count"]) for x in levels) + " |")
    lines.append("| correct | " + " | ".join(str(bd[x]["correct"]) for x in levels) + " |")
    lines.append("| EX (%) | " + " | ".join(_fmt(bd[x]["ex"]) for x in levels) + " |")
    lat, tok = summary["latency_s"], summary["tokens"]
    lines += [
        "",
        f"- errors: {summary['errors'] or 'none'}",
        f"- latency per question: mean {_fmt(lat['mean'], ' s')}, p50 {_fmt(lat['p50'], ' s')}, "
        f"p90 {_fmt(lat['p90'], ' s')}, sum {_fmt(lat['total'], ' s')}",
        f"- tokens: prompt {_fmt(tok['prompt'])}, completion {_fmt(tok['completion'])}; "
        f"estimated cost: {_fmt(summary['cost_usd'], ' USD')}",
        f"- SQL attempts per question (mean): {_fmt(summary['attempts_mean'])}",
        "",
    ]
    return "\n".join(lines)
