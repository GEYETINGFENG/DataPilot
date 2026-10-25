"""Load the official MySQL dump into the warehouse MySQL, one schema per BIRD database.

The dump (`BIRD_dev.sql`) holds all 11 databases in one schema. It is imported into a staging
schema with the `mysql` client, then each database's tables are moved into a schema named after
its `db_id` with RENAME TABLE (a metadata-only operation), so the backend can be pointed at, for
example, `database: "financial"`.
"""

from __future__ import annotations

import shlex
import subprocess
from pathlib import Path

from .dataset import DB_TABLES, DatasetError
from .db import MySQLConfig


def _q(identifier: str) -> str:
    return "`" + identifier.replace("`", "``") + "`"


def import_dump(dump: Path, mysql_cli: str, staging: str) -> None:
    command = shlex.split(mysql_cli) + ["--default-character-set=utf8mb4", staging]
    with dump.open("rb") as stdin:
        result = subprocess.run(command, stdin=stdin, capture_output=True)
    if result.returncode != 0:
        raise DatasetError(f"`{' '.join(command)}` failed: {result.stderr.decode(errors='replace')[-2000:]}")


def plan_moves(staging_tables: list[str]) -> tuple[dict[str, list[str]], list[str], list[str]]:
    """Map each BIRD database to its tables in staging (matching case-insensitively).

    Returns (moves, missing expected tables, unexpected staging tables).
    """
    actual = {t.lower(): t for t in staging_tables}
    moves, missing = {}, []
    for db_id, tables in DB_TABLES.items():
        moves[db_id] = []
        for table in tables:
            found = actual.pop(table.lower(), None)
            if found is None:
                missing.append(f"{db_id}.{table}")
            else:
                moves[db_id].append(found)
    return moves, missing, sorted(actual.values())


def load(dump: Path, admin: MySQLConfig, mysql_cli: str, *, staging: str = "BIRD",
         reader: str | None = "dp_reader", persist_sql_mode: bool = True, log=print) -> dict[str, int]:
    conn = admin.connect()
    with conn, conn.cursor() as cur:
        cur.execute(f"DROP DATABASE IF EXISTS {_q(staging)}")
        cur.execute(f"CREATE DATABASE {_q(staging)} DEFAULT CHARACTER SET utf8mb4")
        log(f"importing {dump} into staging schema {staging} (this takes a while) ...")
        import_dump(dump, mysql_cli, staging)

        cur.execute(f"SHOW TABLES FROM {_q(staging)}")
        moves, missing, unexpected = plan_moves([row[0] for row in cur.fetchall()])
        if missing:
            raise DatasetError(f"tables missing from the dump: {missing}")
        if unexpected:
            log(f"warning: tables in the dump that belong to no BIRD database, dropped: {unexpected}")

        cur.execute("SET SESSION foreign_key_checks = 0")
        for db_id, tables in moves.items():
            cur.execute(f"DROP DATABASE IF EXISTS {_q(db_id)}")
            cur.execute(f"CREATE DATABASE {_q(db_id)} DEFAULT CHARACTER SET utf8mb4")
            cur.execute("RENAME TABLE " + ", ".join(
                f"{_q(staging)}.{_q(t)} TO {_q(db_id)}.{_q(t)}" for t in tables))
        cur.execute(f"DROP DATABASE {_q(staging)}")

        if reader:
            cur.execute("SELECT COUNT(*) FROM mysql.user WHERE user = %s", (reader,))
            if cur.fetchone()[0]:
                for db_id in moves:
                    cur.execute(f"GRANT SELECT ON {_q(db_id)}.* TO %s@'%%'", (reader,))
            else:
                log(f"warning: MySQL user {reader!r} does not exist, no grants given")

        if persist_sql_mode:
            # BIRD's README: some gold queries need ONLY_FULL_GROUP_BY off. Persist it so the
            # backend's own connections behave the same as the scorer's.
            cur.execute(
                "SET PERSIST sql_mode = TRIM(BOTH ',' FROM "
                "REPLACE(REPLACE(@@GLOBAL.sql_mode, 'ONLY_FULL_GROUP_BY', ''), ',,', ','))"
            )

        counts = {}
        for db_id, tables in moves.items():
            total = 0
            for t in tables:
                cur.execute(f"SELECT COUNT(*) FROM {_q(db_id)}.{_q(t)}")
                total += cur.fetchone()[0]
            counts[db_id] = total
    return counts
