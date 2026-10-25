import json
import zipfile

import pytest

from datapilot_eval import dataset
from datapilot_eval.dataset import DatasetError
from datapilot_eval.loader import plan_moves


def mini_dev_items(n=500):
    dbs = sorted(dataset.DB_TABLES)
    levels = ["simple"] * 3 + ["moderate"] * 5 + ["challenging"] * 2
    return [{
        "question_id": 1000 + i, "db_id": dbs[i % len(dbs)], "question": f"q{i}?", "evidence": "",
        "SQL": "SELECT 1", "difficulty": levels[i % len(levels)],
    } for i in range(n)]


@pytest.fixture
def archive(tmp_path):
    path = tmp_path / "minidev.zip"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("minidev/MINIDEV/mini_dev_mysql.json", json.dumps(mini_dev_items()))
        zf.writestr("minidev/MINIDEV/mini_dev_sqlite.json", "[]")
        zf.writestr("minidev/MINIDEV_mysql/BIRD_dev.sql", "CREATE TABLE x (a int);")
        zf.writestr("minidev/MINIDEV_postgresql/BIRD_dev.sql", "-- postgres")
        zf.writestr("__MACOSX/minidev/._mini_dev_mysql.json", "junk")
    return path


def test_extract_locate_and_verify(archive, tmp_path):
    dest = tmp_path / "out"
    dataset.extract(archive, dest)
    layout = dataset.locate(dest)
    assert layout.questions == dest / "minidev/MINIDEV/mini_dev_mysql.json"
    assert layout.mysql_dump == dest / "minidev/MINIDEV_mysql/BIRD_dev.sql"
    assert not (dest / "__MACOSX").exists()
    questions = dataset.load_questions(layout.questions)
    assert dataset.verify_questions(questions) == []
    assert questions[0] == dataset.Question(1000, "california_schools", "q0?", "", "SELECT 1", "simple")


def test_refuses_path_traversal(tmp_path):
    evil = tmp_path / "evil.zip"
    with zipfile.ZipFile(evil, "w") as zf:
        zf.writestr("../escape.txt", "x")
    with pytest.raises(DatasetError, match="outside"):
        dataset.extract(evil, tmp_path / "out")
    assert not (tmp_path / "escape.txt").exists()


def test_locate_reports_missing_files(tmp_path):
    with pytest.raises(DatasetError, match="mini_dev_mysql.json not found"):
        dataset.locate(tmp_path)


def test_verify_finds_problems():
    items = mini_dev_items(499)
    items[1]["question_id"] = items[0]["question_id"]
    items[2]["db_id"] = "unknown_db"
    items[3]["difficulty"] = "hard"
    items[4]["SQL"] = " "
    problems = dataset.verify_questions([dataset.Question(**{
        "question_id": i["question_id"], "db_id": i["db_id"], "question": i["question"],
        "evidence": i["evidence"], "gold_sql": i["SQL"], "difficulty": i["difficulty"]}) for i in items])
    assert len(problems) == 5
    assert "expected 500 questions, found 499" in problems


def test_malformed_question_file(tmp_path):
    path = tmp_path / "q.json"
    path.write_text(json.dumps([{"question_id": 1}]))
    with pytest.raises(DatasetError, match="entry 0"):
        dataset.load_questions(path)


def test_sha256_pin(tmp_path):
    archive = tmp_path / "a.zip"
    archive.write_bytes(b"hello")
    pin = tmp_path / "pin"
    sha, pinned = dataset.check_sha256(archive, pin)
    assert sha == "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" and not pinned
    pin.write_text(f"{sha}  a.zip\n")
    assert dataset.check_sha256(archive, pin) == (sha, True)
    pin.write_text("0" * 64)
    with pytest.raises(DatasetError, match="pins"):
        dataset.check_sha256(archive, pin)


def test_plan_moves_matches_case_insensitively():
    staging = [t for tables in dataset.DB_TABLES.values() for t in tables]
    staging = [t.lower() if t == "Patient" else t for t in staging] + ["stray"]
    staging.remove("trans")
    moves, missing, unexpected = plan_moves(staging)
    assert "patient" in moves["thrombosis_prediction"]
    assert missing == ["financial.trans"]
    assert unexpected == ["stray"]


def test_table_names_are_unique_across_databases():
    # the official dump keeps all databases in one schema, so splitting relies on this
    names = [t.lower() for tables in dataset.DB_TABLES.values() for t in tables]
    assert len(names) == len(set(names))


def test_download_command_pins_and_verifies(archive, tmp_path, monkeypatch, capsys):
    from datapilot_eval import cli

    pin = tmp_path / "minidev.zip.sha256"
    monkeypatch.setattr(cli, "PIN_FILE", pin)
    monkeypatch.setattr(cli, "REPO_ROOT", tmp_path)
    data = tmp_path / "data"
    url = archive.as_uri()

    assert cli.main(["download", "--data-dir", str(data), "--url", url]) == 0
    assert "no pin yet" in capsys.readouterr().out and not pin.exists()
    assert cli.main(["download", "--data-dir", str(data), "--pin"]) == 0
    sha = dataset.sha256_of(archive)
    assert pin.read_text().split()[0] == sha
    assert (data / "minidev.zip.sha256").read_text().strip() == sha
    assert "ok: 500 questions" in capsys.readouterr().out

    (data / "minidev.zip").write_bytes(b"tampered")
    assert cli.main(["download", "--data-dir", str(data)]) == 1
    assert "pins" in capsys.readouterr().err
