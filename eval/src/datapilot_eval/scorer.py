"""Execution accuracy (EX).

The official BIRD Mini-Dev scorer (bird-bench/mini_dev, evaluation/evaluation_ex.py,
`calculate_ex`) runs the predicted and the gold SQL against the same database and counts the
prediction correct iff

    set(predicted_res) == set(ground_truth_res)

where each result is the list of row tuples from `cursor.fetchall()`. So the official comparison
ignores row order (even when the gold SQL has ORDER BY) and ignores duplicate rows, but column
order matters and column names do not. Any exception or a timeout (`meta_time_out`, 30 s) counts
as wrong. `bird` mode below reproduces that exactly and is what every reported number uses.

`strict` mode is an optional, stricter check for analysis: rows are compared as multisets, and
in order when the gold SQL ends with a top-level ORDER BY.
"""

from __future__ import annotations

import time
from collections import Counter
from dataclasses import dataclass
from typing import Protocol

from .db import QueryError, QueryTimeout

COMPARE_MODES = ("bird", "strict")


class SqlExecutor(Protocol):
    def execute(self, database: str, sql: str) -> list[tuple]: ...


def results_match(predicted: list[tuple], gold: list[tuple], mode: str = "bird", gold_sql: str = "") -> bool:
    if mode == "bird":
        return set(predicted) == set(gold)
    if mode == "strict":
        if has_top_level_order_by(gold_sql):
            return list(predicted) == list(gold)
        return Counter(predicted) == Counter(gold)
    raise ValueError(f"unknown compare mode {mode!r}")


def has_top_level_order_by(sql: str) -> bool:
    """True if ORDER BY appears outside parentheses, string literals and quoted identifiers."""
    depth = 0
    quote: str | None = None
    tokens: list[str] = []
    word = []
    i = 0
    while i < len(sql):
        c = sql[i]
        if quote:
            if c == "\\" and quote != "`":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "'\"`":
            quote = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif depth == 0 and (c.isalnum() or c == "_"):
            word.append(c)
            i += 1
            continue
        if word:
            tokens.append("".join(word).upper())
            word = []
        i += 1
    if word:
        tokens.append("".join(word).upper())
    return any(a == "ORDER" and b == "BY" for a, b in zip(tokens, tokens[1:]))


@dataclass(frozen=True)
class Score:
    correct: bool
    error_kind: str | None = None  # empty_sql | pred_error | pred_timeout | gold_error | gold_timeout
    error: str | None = None
    exec_s: float = 0.0


def score(executor: SqlExecutor, database: str, predicted_sql: str | None, gold_sql: str, mode: str = "bird") -> Score:
    if not predicted_sql or not predicted_sql.strip():
        return Score(False, "empty_sql", "no SQL was produced")
    started = time.monotonic()
    try:
        gold = executor.execute(database, gold_sql)
    except QueryTimeout as e:
        return Score(False, "gold_timeout", str(e), time.monotonic() - started)
    except QueryError as e:
        return Score(False, "gold_error", str(e), time.monotonic() - started)
    try:
        predicted = executor.execute(database, predicted_sql)
    except QueryTimeout as e:
        return Score(False, "pred_timeout", str(e), time.monotonic() - started)
    except QueryError as e:
        return Score(False, "pred_error", str(e), time.monotonic() - started)
    return Score(results_match(predicted, gold, mode, gold_sql), exec_s=time.monotonic() - started)
