"""BIRD Mini-Dev: download, verify and read the MySQL version of the dataset."""

from __future__ import annotations

import hashlib
import json
import shutil
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

# Official download link from https://github.com/bird-bench/mini_dev (README badge "Download-MiniDev"
# and llm/mini_dev_data/README.md).
SOURCE_URL = "https://bird-bench.oss-cn-beijing.aliyuncs.com/minidev.zip"

QUESTIONS_FILE = "mini_dev_mysql.json"
MYSQL_DUMP_FILE = "BIRD_dev.sql"

DIFFICULTIES = ("simple", "moderate", "challenging")
EXPECTED_QUESTIONS = 500

# Which tables belong to which database. The official MySQL dump puts all 11 databases into one
# schema (`BIRD`); this map is copied from the official harness
# (bird-bench/mini_dev, llm/src/table_schema.py, `db_table_map`) and is used to split it into one
# schema per database.
DB_TABLES: dict[str, list[str]] = {
    "debit_card_specializing": ["customers", "gasstations", "products", "transactions_1k", "yearmonth"],
    "student_club": ["major", "member", "attendance", "budget", "event", "expense", "income", "zip_code"],
    "thrombosis_prediction": ["Patient", "Examination", "Laboratory"],
    "european_football_2": ["League", "Match", "Player", "Player_Attributes", "Team", "Team_Attributes"],
    "formula_1": [
        "circuits", "seasons", "races", "constructors", "constructorResults", "constructorStandings",
        "drivers", "driverStandings", "lapTimes", "pitStops", "qualifying", "status", "results",
    ],
    "superhero": [
        "alignment", "attribute", "colour", "gender", "publisher", "race", "superpower", "superhero",
        "hero_attribute", "hero_power",
    ],
    "codebase_community": ["posts", "users", "badges", "comments", "postHistory", "postLinks", "tags", "votes"],
    "card_games": ["cards", "foreign_data", "legalities", "rulings", "set_translations", "sets"],
    "toxicology": ["molecule", "atom", "bond", "connected"],
    "california_schools": ["satscores", "frpm", "schools"],
    "financial": ["district", "account", "client", "disp", "card", "loan", "order", "trans"],
}


class DatasetError(Exception):
    pass


@dataclass(frozen=True)
class Question:
    question_id: int
    db_id: str
    question: str
    evidence: str
    gold_sql: str
    difficulty: str


@dataclass(frozen=True)
class Layout:
    questions: Path
    mysql_dump: Path


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> str:
    """Download `url` to `target` (atomically) and return its SHA-256."""
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as f:
        shutil.copyfileobj(response, f, 1 << 20)
    partial.replace(target)
    return sha256_of(target)


def check_sha256(archive: Path, pin_file: Path) -> tuple[str, bool]:
    """Return the archive's hash and whether it was checked against a pinned value.

    Raises if a pin exists and does not match.
    """
    actual = sha256_of(archive)
    if not pin_file.exists():
        return actual, False
    expected = pin_file.read_text().split()[0].strip().lower()
    if actual != expected:
        raise DatasetError(
            f"{archive} has SHA-256 {actual}, but {pin_file} pins {expected}. "
            "Delete the archive and download again, or update the pin if upstream changed on purpose."
        )
    return actual, True


def extract(archive: Path, dest: Path) -> None:
    """Extract a zip, refusing entries that would escape `dest` and skipping macOS metadata."""
    dest.mkdir(parents=True, exist_ok=True)
    root = dest.resolve()
    with zipfile.ZipFile(archive) as zf:
        for member in zf.infolist():
            if member.filename.startswith("__MACOSX/") or "/._" in member.filename:
                continue
            target = (root / member.filename).resolve()
            if not target.is_relative_to(root):
                raise DatasetError(f"Refusing to extract {member.filename!r}: outside {dest}")
            zf.extract(member, root)


def locate(root: Path) -> Layout:
    """Find the MySQL question file and MySQL dump inside an extracted archive."""
    questions = _find_one(root, QUESTIONS_FILE)
    dump = _find_one(root, MYSQL_DUMP_FILE, must_contain="mysql")
    return Layout(questions=questions, mysql_dump=dump)


def _find_one(root: Path, name: str, must_contain: str | None = None) -> Path:
    found = sorted(
        p for p in root.rglob(name)
        if "__MACOSX" not in p.parts and (must_contain is None or must_contain in str(p.parent).lower())
    )
    if not found:
        raise DatasetError(f"{name} not found under {root}")
    if len(found) > 1:
        raise DatasetError(f"{name} found more than once under {root}: {found}")
    return found[0]


def load_questions(path: Path) -> list[Question]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    questions = []
    for i, item in enumerate(raw):
        try:
            questions.append(Question(
                question_id=int(item["question_id"]),
                db_id=item["db_id"],
                question=item["question"],
                evidence=item.get("evidence") or "",
                gold_sql=item["SQL"],
                difficulty=item["difficulty"],
            ))
        except (KeyError, TypeError, ValueError) as e:
            raise DatasetError(f"{path}: entry {i} is malformed ({e!r})") from e
    return questions


def verify_questions(questions: list[Question], expected_count: int = EXPECTED_QUESTIONS) -> list[str]:
    """Return a list of problems; empty means the question file looks like Mini-Dev."""
    problems = []
    if len(questions) != expected_count:
        problems.append(f"expected {expected_count} questions, found {len(questions)}")
    ids = [q.question_id for q in questions]
    if len(set(ids)) != len(ids):
        problems.append("question_id values are not unique")
    unknown_dbs = sorted({q.db_id for q in questions} - DB_TABLES.keys())
    if unknown_dbs:
        problems.append(f"unknown db_id values: {unknown_dbs}")
    bad_difficulty = sorted({q.difficulty for q in questions} - set(DIFFICULTIES))
    if bad_difficulty:
        problems.append(f"unknown difficulty values: {bad_difficulty}")
    empty = [q.question_id for q in questions if not q.gold_sql.strip() or not q.question.strip()]
    if empty:
        problems.append(f"questions with empty text or SQL: {empty[:10]}")
    return problems
