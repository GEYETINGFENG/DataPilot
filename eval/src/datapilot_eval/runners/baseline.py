"""Baseline: one LLM call with the full schema of the database, no retries, no agent.

The prompt follows the official BIRD Mini-Dev LLM baseline (bird-bench/mini_dev, llm/src/prompt.py
and llm/src/table_schema.py) with the dialect set to MySQL: the schema of every table rendered
from DESCRIBE, the question (and, when enabled, the evidence as "External Knowledge"), and the
instruction to return only SQL. Differences: a chat model is called with temperature 0 and no stop
sequences, and Markdown fences are stripped from the answer.
"""

from __future__ import annotations

import re
import threading

import openai

from ..dataset import DB_TABLES, Question
from ..db import InfraError, MySQLConfig
from . import Generation

DIALECT = "MySQL"


def format_create_table(table: str, columns: list[tuple]) -> str:
    """Same rendering as `format_mysql_create_table` in the official table_schema.py."""
    lines = [f"CREATE TABLE {table}\n("]
    primary_key_defined = False
    for column_name, data_type, nullable, key, *_ in columns:
        null_type = "not null" if nullable == "NO" else "null"
        primary_key_part = "primary key" if "PRI" in key and not primary_key_defined else ""
        primary_key_defined = primary_key_defined or "PRI" in key
        lines.append(f"    `{column_name}` {str.upper(data_type)} {null_type} {primary_key_part},".strip())
    lines[-1] = lines[-1].rstrip(",")
    lines.append(");")
    return "\n".join(lines)


def build_prompt(schema: str, question: str, evidence: str | None) -> str:
    knowledge_text = " and understanding External Knowledge" if evidence else ""
    knowledge_prompt = f"-- External Knowledge: {evidence}" if evidence else ""
    comment = (
        f"-- Using valid {DIALECT}{knowledge_text}, answer the following questions for the tables provided above.\n"
        f"-- {question}\n"
        f"{knowledge_prompt}"
    )
    cot = f"\nGenerate the {DIALECT} for the above question after thinking step by step: "
    instruction = f"""
        \nIn your response, you do not need to mention your intermediate steps.
        Do not include any comments in your response.
        Do not need to start with the symbol ```
        You only need to return the result {DIALECT} SQL code
        start from SELECT
        """
    return "\n\n".join([schema, comment, cot, instruction])


_FENCE = re.compile(r"```(?:sql|mysql)?\s*(.*?)```", re.IGNORECASE | re.DOTALL)


def extract_sql(text: str) -> str:
    fenced = _FENCE.search(text)
    sql = fenced.group(1) if fenced else text
    return sql.strip().rstrip(";").strip()


class SchemaProvider:
    """Schema prompt per database, read once from the warehouse."""

    def __init__(self, config: MySQLConfig):
        self.config = config
        self._cache: dict[str, str] = {}
        self._lock = threading.Lock()

    def schema(self, db_id: str) -> str:
        with self._lock:
            if db_id not in self._cache:
                self._cache[db_id] = self._read(db_id)
            return self._cache[db_id]

    def _read(self, db_id: str) -> str:
        conn = self.config.connect(db_id)
        with conn, conn.cursor() as cur:
            cur.execute("SHOW TABLES")
            actual = {row[0].lower(): row[0] for row in cur.fetchall()}
            # official table order first, then anything else the schema has
            order = [actual.pop(t.lower()) for t in DB_TABLES.get(db_id, []) if t.lower() in actual]
            order += sorted(actual.values())
            parts = []
            for table in order:
                cur.execute(f"DESCRIBE `{table}`")
                parts.append(format_create_table(table, list(cur.fetchall())))
        return "\n\n".join(parts)


class BaselineRunner:
    def __init__(self, schemas: SchemaProvider, client: openai.OpenAI, model: str,
                 use_evidence: bool, max_tokens: int = 1024):
        self.schemas = schemas
        self.client = client
        self.model = model
        self.use_evidence = use_evidence
        self.max_tokens = max_tokens

    def generate(self, question: Question) -> Generation:
        prompt = build_prompt(
            self.schemas.schema(question.db_id),
            question.question,
            question.evidence if self.use_evidence else None,
        )
        try:
            response = self.client.chat.completions.create(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                temperature=0,
                max_tokens=self.max_tokens,
            )
        except openai.APIError as e:
            raise InfraError(f"LLM call failed: {e}") from e
        text = response.choices[0].message.content or ""
        usage = response.usage
        return Generation(
            sql=extract_sql(text) or None,
            error=None if text.strip() else "empty LLM response",
            attempts=1,
            prompt_tokens=usage.prompt_tokens if usage else None,
            completion_tokens=usage.completion_tokens if usage else None,
            extra={"finish_reason": response.choices[0].finish_reason},
        )
