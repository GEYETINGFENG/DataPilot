"""Integration tests against a real MySQL (the compose service). Opt in with EVAL_MYSQL_TESTS=1."""

import os
import time

import pytest

from datapilot_eval import loader
from datapilot_eval.db import Executor, MySQLConfig, QueryError, QueryTimeout

pytestmark = [
    pytest.mark.mysql,
    pytest.mark.skipif(os.environ.get("EVAL_MYSQL_TESTS") != "1", reason="set EVAL_MYSQL_TESTS=1 to run"),
]

ADMIN = MySQLConfig.from_env("EVAL_MYSQL_ADMIN_", user="root", password="root")
READER = MySQLConfig.from_env()
MYSQL_CLI = os.environ.get(
    "EVAL_MYSQL_CLI", "docker compose -f ../docker-compose.yml exec -T -e MYSQL_PWD=root mysql mysql -uroot")

DUMP = """
DROP TABLE IF EXISTS `Patient`;
CREATE TABLE `Patient` (`ID` int PRIMARY KEY, `SEX` text);
INSERT INTO `Patient` VALUES (1, 'F'), (2, 'M'), (3, 'F');
CREATE TABLE `order` (`order_id` int PRIMARY KEY, `amount` decimal(10, 2));
INSERT INTO `order` VALUES (1, 10.50), (2, 20.00);
"""


@pytest.fixture(scope="module")
def loaded(tmp_path_factory, monkeypatch_module):
    monkeypatch_module.setattr(loader, "DB_TABLES", {"it_thrombosis": ["Patient"], "it_financial": ["order"]})
    dump = tmp_path_factory.mktemp("dump") / "BIRD_dev.sql"
    dump.write_text(DUMP)
    counts = loader.load(dump, ADMIN, MYSQL_CLI, staging="it_bird_staging", persist_sql_mode=False, log=lambda m: None)
    yield counts
    conn = ADMIN.connect()
    with conn, conn.cursor() as cur:
        cur.execute("DROP DATABASE IF EXISTS it_thrombosis")
        cur.execute("DROP DATABASE IF EXISTS it_financial")


@pytest.fixture(scope="module")
def monkeypatch_module():
    mp = pytest.MonkeyPatch()
    yield mp
    mp.undo()


def test_loader_splits_into_schemas_and_grants_reader(loaded):
    assert loaded == {"it_thrombosis": 3, "it_financial": 2}
    executor = Executor(READER, timeout_s=5)
    assert sorted(executor.execute("it_thrombosis", "SELECT SEX, COUNT(*) FROM Patient GROUP BY SEX")) == [("F", 2), ("M", 1)]
    assert executor.execute("it_financial", "SELECT SUM(amount) FROM `order`")[0][0] == 30.5


def test_reader_cannot_write(loaded):
    with pytest.raises(QueryError):
        Executor(READER, timeout_s=5).execute("it_financial", "DELETE FROM `order`")


def test_only_full_group_by_is_relaxed(loaded):
    rows = Executor(READER, timeout_s=5).execute("it_thrombosis", "SELECT ID, SEX FROM Patient GROUP BY SEX")
    assert len(rows) == 2


def test_errors_and_timeouts(loaded):
    executor = Executor(READER, timeout_s=1)
    with pytest.raises(QueryError, match="Unknown column"):
        executor.execute("it_thrombosis", "SELECT nope FROM Patient")
    started = time.monotonic()
    with pytest.raises(QueryTimeout):
        executor.execute("it_thrombosis", "SELECT SLEEP(10) FROM Patient")
    assert time.monotonic() - started < 5
    # the server is not left running the query
    conn = ADMIN.connect()
    with conn, conn.cursor() as cur:
        time.sleep(0.5)
        cur.execute("SELECT COUNT(*) FROM information_schema.processlist WHERE info LIKE 'SELECT SLEEP(10)%'")
        assert cur.fetchone()[0] == 0
