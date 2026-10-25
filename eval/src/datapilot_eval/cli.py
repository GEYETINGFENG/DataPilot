"""Command line: download, load, check-gold, run, summarize."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import shlex
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import dataset, loader, report
from .db import Executor, InfraError, MySQLConfig, QueryError
from .run import RunConfigMismatch, prepare_run_dir, run, write_summary
from .scorer import COMPARE_MODES

EVAL_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = EVAL_ROOT.parent
DEFAULT_DATA_DIR = Path(os.environ.get("EVAL_DATA_DIR", EVAL_ROOT / "data"))
PIN_FILE = EVAL_ROOT / "minidev.zip.sha256"
ARCHIVE_NAME = "minidev.zip"


def _extracted(data_dir: Path) -> Path:
    return data_dir / "extracted"


def _questions(data_dir: Path) -> list[dataset.Question]:
    return dataset.load_questions(dataset.locate(_extracted(data_dir)).questions)


def _dataset_sha(data_dir: Path) -> str | None:
    marker = data_dir / f"{ARCHIVE_NAME}.sha256"
    return marker.read_text().strip() if marker.exists() else None


def cmd_download(args) -> int:
    archive = args.data_dir / ARCHIVE_NAME
    if archive.exists() and not args.force:
        print(f"{archive} exists, verifying (use --force to download again)")
    else:
        print(f"downloading {args.url} ...")
        dataset.download(args.url, archive)
    sha, pinned = dataset.check_sha256(archive, PIN_FILE)
    print(f"sha256 {sha} ({'matches ' + PIN_FILE.name if pinned else 'no pin yet'})")
    if not pinned:
        if args.pin:
            PIN_FILE.write_text(f"{sha}  {ARCHIVE_NAME}\n")
            print(f"pinned in {PIN_FILE}; commit it so later downloads are verified")
        else:
            print(f"run again with --pin to record it in {PIN_FILE.relative_to(REPO_ROOT)}")

    extracted = _extracted(args.data_dir)
    dataset.extract(archive, extracted)
    layout = dataset.locate(extracted)
    questions = dataset.load_questions(layout.questions)
    problems = dataset.verify_questions(questions)
    if problems:
        print("dataset verification failed:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    (args.data_dir / f"{ARCHIVE_NAME}.sha256").write_text(sha + "\n")
    counts = {d: sum(q.difficulty == d for q in questions) for d in dataset.DIFFICULTIES}
    print(f"ok: {len(questions)} questions {counts} over {len({q.db_id for q in questions})} databases")
    print(f"questions: {layout.questions}\nmysql dump: {layout.mysql_dump}")
    return 0


def cmd_load(args) -> int:
    layout = dataset.locate(_extracted(args.data_dir))
    admin = MySQLConfig.from_env("EVAL_MYSQL_ADMIN_", user="root", password="root")
    counts = loader.load(layout.mysql_dump, admin, args.mysql_cli, staging=args.staging,
                         reader=args.reader, persist_sql_mode=not args.keep_sql_mode)
    for db_id, rows in counts.items():
        print(f"  {db_id:26} {rows:>10} rows")
    print("loaded; checking that every gold query runs ...")
    return cmd_check_gold(args)


def cmd_check_gold(args) -> int:
    executor = Executor(MySQLConfig.from_env(), args.timeout)
    questions = _questions(args.data_dir)

    def check(q):
        try:
            executor.execute(q.db_id, q.gold_sql)
            return None
        except QueryError as e:
            return f"[{q.question_id} {q.db_id}] {type(e).__name__}: {e}"

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        failures = [f for f in pool.map(check, questions) if f]
    for failure in failures:
        print(failure)
    print(f"{len(questions) - len(failures)}/{len(questions)} gold queries ran")
    return 1 if failures else 0


def _git_commit() -> str | None:
    try:
        out = subprocess.run(["git", "-C", str(REPO_ROOT), "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, check=True).stdout.strip()
        dirty = subprocess.run(["git", "-C", str(REPO_ROOT), "status", "--porcelain"],
                               capture_output=True, text=True).stdout.strip()
        return out + ("-dirty" if dirty else "")
    except (OSError, subprocess.CalledProcessError):
        return None


def build_runner(args):
    if args.runner == "baseline":
        import openai

        from .runners.baseline import BaselineRunner, SchemaProvider

        api_key = os.environ.get("LLM_API_KEY")
        if not api_key:
            raise SystemExit("LLM_API_KEY is not set")
        client = openai.OpenAI(api_key=api_key, base_url=args.llm_base_url, timeout=120, max_retries=3)
        return BaselineRunner(SchemaProvider(MySQLConfig.from_env()), client, args.model, args.evidence)
    from .runners.agent import AgentRunner

    return AgentRunner(args.backend_url, args.evidence, args.agent_timeout)


def cmd_run(args) -> int:
    questions = _questions(args.data_dir)
    if args.db:
        questions = [q for q in questions if q.db_id in args.db]
    if args.limit:
        questions = questions[: args.limit]
    model = args.model if args.runner == "baseline" else "backend"
    name = args.name or f"{args.runner}-{model if args.runner == 'baseline' else 'datapilot'}-" \
                        f"{'evidence' if args.evidence else 'noevidence'}"
    out = args.out or EVAL_ROOT / "runs" / name
    meta = {
        "name": name,
        "runner": args.runner,
        "model": model,
        "evidence": args.evidence,
        "compare": args.compare,
        "timeout_s": args.timeout,
        "backend_url": args.backend_url if args.runner == "agent" else None,
        "dataset_sha256": _dataset_sha(args.data_dir),
        "started_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "git_commit": _git_commit(),
        "command": "uv run datapilot-eval " + shlex.join(sys.argv[1:]),
        "price_in": args.price_in,
        "price_out": args.price_out,
    }
    runner = build_runner(args)
    try:
        meta = prepare_run_dir(out, meta)
    except RunConfigMismatch as e:
        print(e, file=sys.stderr)
        return 2
    executor = Executor(MySQLConfig.from_env(), args.timeout)
    print(f"{len(questions)} questions -> {out}", file=sys.stderr)
    records, infra_errors = run(questions, runner, executor, out, compare=args.compare,
                                concurrency=args.concurrency, max_infra_errors=args.max_infra_errors)
    print(write_summary(out, records, meta, meta.get("price_in"), meta.get("price_out")))
    missing = len(questions) - len(records)
    if missing:
        print(f"{missing} questions not evaluated ({infra_errors} infrastructure errors); "
              "rerun the same command to resume", file=sys.stderr)
        return 1
    return 0


def cmd_summarize(args) -> int:
    meta = json.loads((args.run_dir / "run.json").read_text())
    records = report.read_records(args.run_dir / "predictions.jsonl")
    price_in = args.price_in if args.price_in is not None else meta.get("price_in")
    price_out = args.price_out if args.price_out is not None else meta.get("price_out")
    print(write_summary(args.run_dir, records, meta, price_in, price_out))
    return 0


def _add_common(p, *, timeout=True, concurrency=True):
    p.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR, help="where the dataset lives (git-ignored)")
    if timeout:
        p.add_argument("--timeout", type=float, default=30.0, help="per-query timeout in seconds (BIRD: 30)")
    if concurrency:
        p.add_argument("--concurrency", type=int, default=4)


def _add_prices(p):
    p.add_argument("--price-in", type=float, help="USD per 1M prompt tokens, for the cost estimate")
    p.add_argument("--price-out", type=float, help="USD per 1M completion tokens, for the cost estimate")


def parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="datapilot-eval", description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("download", help="download, verify and extract BIRD Mini-Dev")
    _add_common(p, timeout=False, concurrency=False)
    p.add_argument("--url", default=dataset.SOURCE_URL)
    p.add_argument("--force", action="store_true", help="download even if the archive exists")
    p.add_argument("--pin", action="store_true", help=f"record the archive hash in {PIN_FILE.name}")
    p.set_defaults(func=cmd_download)

    p = sub.add_parser("load", help="load the MySQL dump, one schema per database, then check-gold")
    _add_common(p)
    p.add_argument("--mysql-cli", default=f"docker compose -f {REPO_ROOT / 'docker-compose.yml'} "
                                         "exec -T -e MYSQL_PWD=root mysql mysql -uroot",
                   help="command that runs the mysql client against the warehouse server")
    p.add_argument("--staging", default="BIRD", help="temporary schema the dump is imported into")
    p.add_argument("--reader", default="dp_reader", help="read-only user to grant SELECT on the BIRD schemas")
    p.add_argument("--keep-sql-mode", action="store_true", help="do not persist sql_mode without ONLY_FULL_GROUP_BY")
    p.set_defaults(func=cmd_load)

    p = sub.add_parser("check-gold", help="run every gold query to verify the databases")
    _add_common(p)
    p.set_defaults(func=cmd_check_gold)

    p = sub.add_parser("run", help="generate, score and summarize")
    _add_common(p)
    p.add_argument("--runner", choices=("baseline", "agent"), required=True)
    p.add_argument("--evidence", action="store_true",
                   help="give the model BIRD's evidence (baseline: in the prompt; agent: as `hint`)")
    p.add_argument("--limit", type=int, help="only the first N questions (dataset order)")
    p.add_argument("--db", action="append", help="only questions of this db_id (repeatable)")
    p.add_argument("--compare", choices=COMPARE_MODES, default="bird",
                   help="bird = official set comparison (default); strict = multiset, ordered if gold has ORDER BY")
    p.add_argument("--out", type=Path, help="run directory (default eval/runs/<name>); reused to resume")
    p.add_argument("--name", help="run name (default derived from runner, model and evidence)")
    p.add_argument("--model", default=os.environ.get("LLM_MODEL", "deepseek-chat"), help="baseline model (env LLM_MODEL)")
    p.add_argument("--llm-base-url", default=os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1"))
    p.add_argument("--backend-url", default=os.environ.get("DATAPILOT_URL", "http://localhost:8080"))
    p.add_argument("--agent-timeout", type=float, default=300.0, help="seconds without SSE data before giving up")
    p.add_argument("--max-infra-errors", type=int, default=5, help="stop after this many harness-side failures")
    _add_prices(p)
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("summarize", help="rebuild summary.md from a run directory")
    p.add_argument("run_dir", type=Path)
    _add_prices(p)
    p.set_defaults(func=cmd_summarize)
    return ap


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        return args.func(args)
    except (dataset.DatasetError, InfraError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
