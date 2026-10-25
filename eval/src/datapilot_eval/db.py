"""MySQL access for scoring: one short-lived connection per query, with a hard timeout."""

from __future__ import annotations

import os
import time
from dataclasses import dataclass

import pymysql

# BIRD's README tells MySQL users to drop ONLY_FULL_GROUP_BY, otherwise some gold queries fail.
RELAX_SQL_MODE = (
    "SET SESSION sql_mode = TRIM(BOTH ',' FROM "
    "REPLACE(REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', ''), ',,', ','))"
)

# MySQL error codes that mean "the query ran out of time"
_ER_QUERY_TIMEOUT = 3024  # max_execution_time exceeded
_CR_SERVER_LOST = 2013  # read timeout on the client side


class QueryError(Exception):
    """The SQL itself failed (syntax error, unknown column, ...)."""


class QueryTimeout(QueryError):
    pass


class InfraError(Exception):
    """The harness could not do its job (database down, backend unreachable, LLM API failing).

    Questions hitting this are not recorded, so a resumed run retries them.
    """


@dataclass(frozen=True)
class MySQLConfig:
    host: str = "127.0.0.1"
    port: int = 3307
    user: str = "dp_reader"
    password: str = "dp_reader"

    @classmethod
    def from_env(cls, prefix: str = "EVAL_MYSQL_", **defaults) -> MySQLConfig:
        base = cls(**defaults)
        return cls(
            host=os.environ.get(prefix + "HOST", base.host),
            port=int(os.environ.get(prefix + "PORT", base.port)),
            user=os.environ.get(prefix + "USER", base.user),
            password=os.environ.get(prefix + "PASSWORD", base.password),
        )

    def connect(self, database: str | None = None, read_timeout: float | None = None, **kwargs):
        try:
            return pymysql.connect(
                host=self.host, port=self.port, user=self.user, password=self.password,
                database=database, charset="utf8mb4", connect_timeout=10,
                read_timeout=read_timeout, **kwargs,
            )
        except pymysql.err.OperationalError as e:
            raise InfraError(f"cannot connect to MySQL {self.host}:{self.port} as {self.user}: {e}") from e


class Executor:
    def __init__(self, config: MySQLConfig, timeout_s: float = 30.0):
        self.config = config
        self.timeout_s = timeout_s

    def execute(self, database: str, sql: str) -> list[tuple]:
        conn = self.config.connect(database, read_timeout=self.timeout_s + 2)
        started = time.monotonic()
        try:
            with conn.cursor() as cur:
                cur.execute(RELAX_SQL_MODE)
                cur.execute(f"SET SESSION max_execution_time = {int(self.timeout_s * 1000)}")
                try:
                    cur.execute(sql)
                    rows = cur.fetchall()
                except pymysql.err.MySQLError as e:
                    code = e.args[0] if e.args else None
                    if code == _ER_QUERY_TIMEOUT or (
                        code == _CR_SERVER_LOST and time.monotonic() - started >= self.timeout_s
                    ):
                        self._kill(conn.thread_id())
                        raise QueryTimeout(f"timed out after {self.timeout_s:g}s") from e
                    raise QueryError(str(e)) from e
            return [tuple(row) for row in rows]
        finally:
            try:
                conn.close()
            except pymysql.err.Error:
                pass

    def _kill(self, thread_id: int) -> None:
        # max_execution_time only covers SELECT; make sure nothing keeps running on the server
        try:
            admin = self.config.connect()
            with admin, admin.cursor() as cur:
                cur.execute(f"KILL QUERY {int(thread_id)}")
        except Exception:
            pass
