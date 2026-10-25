from decimal import Decimal

import pytest

from datapilot_eval.db import QueryError, QueryTimeout
from datapilot_eval.scorer import has_top_level_order_by, results_match, score


class TestBirdMode:
    """Mirrors `set(predicted_res) == set(ground_truth_res)` in BIRD's evaluation_ex.py."""

    def test_row_order_is_ignored(self):
        assert results_match([(1, "a"), (2, "b")], [(2, "b"), (1, "a")])

    def test_row_order_is_ignored_even_when_gold_orders(self):
        assert results_match([(1,), (2,)], [(2,), (1,)], gold_sql="SELECT x FROM t ORDER BY x DESC")

    def test_duplicates_are_ignored(self):
        assert results_match([(1,), (1,), (2,)], [(1,), (2,)])

    def test_column_order_matters(self):
        assert not results_match([("a", 1)], [(1, "a")])

    def test_different_values(self):
        assert not results_match([(1,)], [(2,)])

    def test_extra_or_missing_rows(self):
        assert not results_match([(1,), (2,)], [(1,)])
        assert not results_match([], [(1,)])

    def test_both_empty(self):
        assert results_match([], [])

    def test_numeric_types_compare_by_value(self):
        # pymysql returns DECIMAL as Decimal; Python's equal numbers hash equally
        assert results_match([(Decimal("2.0"),)], [(2,)])
        assert results_match([(Decimal("0.5"),)], [(0.5,)])
        assert not results_match([(Decimal("0.3333"),)], [(Decimal("0.33333"),)])

    def test_null_and_strings(self):
        assert results_match([(None, "x")], [(None, "x")])
        assert not results_match([(None,)], [("",)])
        assert not results_match([("1",)], [(1,)])


class TestStrictMode:
    def test_duplicates_matter(self):
        assert not results_match([(1,), (1,)], [(1,)], "strict", "SELECT a FROM t")

    def test_order_ignored_without_order_by(self):
        assert results_match([(2,), (1,)], [(1,), (2,)], "strict", "SELECT a FROM t")

    def test_order_matters_with_top_level_order_by(self):
        gold = "SELECT a FROM t ORDER BY a"
        assert results_match([(1,), (2,)], [(1,), (2,)], "strict", gold)
        assert not results_match([(2,), (1,)], [(1,), (2,)], "strict", gold)

    def test_unknown_mode(self):
        with pytest.raises(ValueError):
            results_match([], [], "fuzzy")


@pytest.mark.parametrize("sql,expected", [
    ("SELECT a FROM t ORDER BY a", True),
    ("select a from t order\n  by a limit 1", True),
    ("SELECT a FROM (SELECT a FROM t ORDER BY a LIMIT 3) x", False),
    ("SELECT 'ORDER BY' FROM t", False),
    ("SELECT `order` FROM `order` BY_x", False),
    ("SELECT a FROM t WHERE b = 'it''s' ORDER BY a", True),
    ("SELECT ROW_NUMBER() OVER (ORDER BY a) FROM t", False),
    ("SELECT a FROM t", False),
])
def test_has_top_level_order_by(sql, expected):
    assert has_top_level_order_by(sql) is expected


class FakeExecutor:
    def __init__(self, results):
        self.results = results  # sql -> rows or exception
        self.calls = []

    def execute(self, database, sql):
        self.calls.append((database, sql))
        result = self.results[sql]
        if isinstance(result, Exception):
            raise result
        return result


class TestScore:
    def test_correct(self):
        ex = FakeExecutor({"gold": [(1,)], "pred": [(1,)]})
        result = score(ex, "financial", "pred", "gold")
        assert result.correct and result.error_kind is None
        assert {db for db, _ in ex.calls} == {"financial"}

    def test_wrong_result(self):
        result = score(FakeExecutor({"gold": [(1,)], "pred": [(2,)]}), "db", "pred", "gold")
        assert not result.correct and result.error_kind is None

    @pytest.mark.parametrize("pred", [None, "", "   "])
    def test_empty_prediction(self, pred):
        assert score(FakeExecutor({}), "db", pred, "gold").error_kind == "empty_sql"

    def test_failing_prediction_is_wrong(self):
        result = score(FakeExecutor({"gold": [(1,)], "pred": QueryError("Unknown column")}), "db", "pred", "gold")
        assert not result.correct and result.error_kind == "pred_error" and "Unknown column" in result.error

    def test_timed_out_prediction_is_wrong(self):
        result = score(FakeExecutor({"gold": [(1,)], "pred": QueryTimeout("timed out")}), "db", "pred", "gold")
        assert not result.correct and result.error_kind == "pred_timeout"

    def test_gold_failure_is_flagged(self):
        result = score(FakeExecutor({"gold": QueryError("boom"), "pred": [(1,)]}), "db", "pred", "gold")
        assert not result.correct and result.error_kind == "gold_error"
