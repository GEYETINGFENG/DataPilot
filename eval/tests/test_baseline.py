from types import SimpleNamespace

import openai
import pytest

from helpers import make_question
from datapilot_eval.db import InfraError
from datapilot_eval.runners.baseline import BaselineRunner, build_prompt, extract_sql, format_create_table


def test_create_table_rendering_matches_official():
    # byte-for-byte what bird-bench/mini_dev's format_mysql_create_table produces, quirks included
    columns = [("id", "int", "NO", "PRI", None, ""), ("name", "text", "YES", "", None, ""),
               ("other_id", "int", "NO", "PRI", None, "")]
    assert format_create_table("t", columns) == (
        "CREATE TABLE t\n(\n`id` INT not null primary key,\n`name` TEXT null ,\n`other_id` INT not null \n);"
    )


def test_prompt_with_and_without_evidence():
    with_evidence = build_prompt("SCHEMA", "How many?", "count = COUNT(*)")
    assert with_evidence.startswith("SCHEMA\n\n-- Using valid MySQL and understanding External Knowledge, answer")
    assert "-- How many?\n-- External Knowledge: count = COUNT(*)" in with_evidence
    assert "start from SELECT" in with_evidence

    without = build_prompt("SCHEMA", "How many?", None)
    assert "External Knowledge" not in without
    assert "-- Using valid MySQL, answer the following questions for the tables provided above." in without


@pytest.mark.parametrize("text,sql", [
    ("SELECT 1;", "SELECT 1"),
    ("```sql\nSELECT a\nFROM t;\n```", "SELECT a\nFROM t"),
    ("Here you go:\n```\nSELECT 2\n```\nDone.", "SELECT 2"),
    ("  \n", ""),
])
def test_extract_sql(text, sql):
    assert extract_sql(text) == sql


class FakeSchemas:
    def schema(self, db_id):
        return f"CREATE TABLE {db_id}_t (a int);"


class FakeCompletions:
    def __init__(self, content="```sql\nSELECT a FROM t\n```", error=None):
        self.content, self.error, self.calls = content, error, []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=self.content), finish_reason="stop")],
            usage=SimpleNamespace(prompt_tokens=120, completion_tokens=8),
        )


def client(completions):
    return SimpleNamespace(chat=SimpleNamespace(completions=completions))


def test_single_call_with_schema_and_optional_evidence():
    completions = FakeCompletions()
    runner = BaselineRunner(FakeSchemas(), client(completions), "some-model", use_evidence=False)
    generation = runner.generate(make_question(db_id="toxicology", evidence="secret evidence"))

    assert generation.sql == "SELECT a FROM t"
    assert (generation.attempts, generation.prompt_tokens, generation.completion_tokens) == (1, 120, 8)
    [call] = completions.calls
    assert call["model"] == "some-model" and call["temperature"] == 0
    prompt = call["messages"][0]["content"]
    assert "CREATE TABLE toxicology_t" in prompt and "secret evidence" not in prompt

    BaselineRunner(FakeSchemas(), client(completions), "m", use_evidence=True).generate(
        make_question(evidence="secret evidence"))
    assert "secret evidence" in completions.calls[-1]["messages"][0]["content"]


def test_empty_answer_is_recorded():
    generation = BaselineRunner(FakeSchemas(), client(FakeCompletions(content="")), "m", False).generate(make_question())
    assert generation.sql is None and generation.error == "empty LLM response"


def test_api_failure_is_infrastructure():
    error = openai.APIConnectionError(request=None)
    with pytest.raises(InfraError):
        BaselineRunner(FakeSchemas(), client(FakeCompletions(error=error)), "m", False).generate(make_question())
