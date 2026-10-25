"""Runners turn a BIRD question into a predicted SQL query."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from ..dataset import Question


@dataclass
class Generation:
    sql: str | None
    error: str | None = None  # why no usable SQL came back (recorded, counts as wrong)
    attempts: int | None = None  # SQL executions the system tried before answering
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    extra: dict = field(default_factory=dict)


class Runner(Protocol):
    def generate(self, question: Question) -> Generation:
        """Return the prediction. Raise db.InfraError when the failure is the harness's, not the system's."""
        ...
