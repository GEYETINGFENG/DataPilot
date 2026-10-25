# DataPilot evaluation: BIRD Mini-Dev

This directory measures how often DataPilot answers a question with the right data, using the
public [BIRD](https://bird-bench.github.io/) **Mini-Dev** set: 500 curated text-to-SQL questions
over 11 databases, in its MySQL version. The metric is execution accuracy (EX), computed the way
the official BIRD scorer computes it.

The numbers are for tracking what each agent capability adds on one fixed setup. They are not
comparable with BIRD leaderboard entries (different prompt, model, dialect, and the agent can
inspect the database), so they are never presented side by side.

## Status

**No numbers yet.** The harness is built and tested, but it has not been run on the real
dataset: the session that built it had no network access to the BIRD download hosts
(`bird-bench.oss-cn-beijing.aliyuncs.com`, `huggingface.co`) and no `LLM_API_KEY`. Until someone
runs the steps below, every number in this file and in the main README is TBD. Also still to do
on the first real run:

- pin the archive hash (`download --pin`, then commit `eval/minidev.zip.sha256`);
- confirm the loader against the real `BIRD_dev.sql` and that `check-gold` reports 500/500.

## Ablation plan

Each row adds one capability; every row is run on all 500 questions with the same model.

| Row | Command (after setup) | simple | moderate | challenging | total |
|---|---|---|---|---|---|
| Baseline: one LLM call, full schema | `run --runner baseline` | TBD | TBD | TBD | TBD |
| + table selection | `run --runner agent` (agent with retries off) | TBD | TBD | TBD | TBD |
| + self-correction | `run --runner agent` | TBD | TBD | TBD | TBD |
| + hint (BIRD evidence) | `run --runner agent --evidence` | TBD | TBD | TBD | TBD |

The "+ table selection" row needs a backend switch that turns off retries; see
[Backend requirements](#backend-requirements). For the record, `run --runner baseline --evidence`
gives the baseline with evidence too.

## Data source and license

- Dataset: BIRD Mini-Dev, from the official repository
  [bird-bench/mini_dev](https://github.com/bird-bench/mini_dev) (read at commit
  `abd11b6db92a1c9f809b32f7564c7c71b34d67f0`).
- Download URL used by `download`: <https://bird-bench.oss-cn-beijing.aliyuncs.com/minidev.zip>
  (the "Download MiniDev" link in that README and in `llm/mini_dev_data/README.md`). The README
  also lists a Hugging Face copy (`birdsql/bird_mini_dev`) and a Google Drive package; pass
  `--url` to use another mirror of the same zip.
- License: **CC BY-SA 4.0**, as stated by the BIRD Mini-Dev repository. The data is not
  redistributed here: it is downloaded into `eval/data/`, which is git-ignored, and run outputs
  go to `eval/runs/`, also git-ignored.

Files used from the archive: `mini_dev_mysql.json` (question, evidence, gold MySQL SQL,
difficulty, db_id) and `MINIDEV_mysql/BIRD_dev.sql` (the MySQL dump of all 11 databases). They
are found by name anywhere inside the extracted archive.

## Setup

Needs Docker, [uv](https://docs.astral.sh/uv/) and Python 3.11+ (uv installs it if missing).
All commands run from `eval/`.

```bash
docker compose -f ../docker-compose.yml up -d mysql   # MySQL 8.4 on localhost:3307
uv sync

uv run datapilot-eval download          # download, check hash, extract, verify 500 questions / 11 dbs
uv run datapilot-eval download --pin    # first time only: record the SHA-256 in minidev.zip.sha256
uv run datapilot-eval load              # import the dump, one schema per database, then check-gold
```

`load` imports `BIRD_dev.sql` with the `mysql` client inside the compose container into a staging
schema, then moves each database's tables into its own schema named after its `db_id`
(`financial`, `formula_1`, ...) with `RENAME TABLE`. The table-to-database map is copied from the
official harness (`llm/src/table_schema.py`, `db_table_map`); a table the dump lacks is an error.
It also:

- grants `SELECT` on the 11 schemas to `dp_reader`, the backend's read-only warehouse user, which
  the scorer also uses, so predicted SQL can never change data;
- runs `SET PERSIST sql_mode` without `ONLY_FULL_GROUP_BY`, as the BIRD README recommends for
  MySQL, so the backend's connections behave like the scorer's (`--keep-sql-mode` to skip; this
  setting is server-wide and also applies to `demo_shop`);
- finishes with `check-gold`, which runs every gold query and lists any that fail.

Running `load` again drops and recreates the 11 schemas. Use `--mysql-cli` to import with a
different client, e.g. `--mysql-cli "mysql -h 127.0.0.1 -P 3307 -uroot -proot"`.

Connection settings come from the environment: `EVAL_MYSQL_HOST`, `EVAL_MYSQL_PORT`,
`EVAL_MYSQL_USER`, `EVAL_MYSQL_PASSWORD` (defaults `127.0.0.1`, `3307`, `dp_reader`, `dp_reader`)
for scoring and schema reading, and `EVAL_MYSQL_ADMIN_*` (default `root`/`root`) for `load`.

## Running

```bash
# Baseline: needs LLM_API_KEY; LLM_BASE_URL defaults to DeepSeek, model from LLM_MODEL or --model
LLM_API_KEY=... uv run datapilot-eval run --runner baseline --model deepseek-chat

# Agent: needs the backend on :8080 (DATAPILOT_URL or --backend-url)
uv run datapilot-eval run --runner agent              # hint = null
uv run datapilot-eval run --runner agent --evidence   # hint = BIRD evidence
```

Useful flags:

- `--limit N` only the first N questions in dataset order; `--db financial` only one database.
- `--concurrency N` questions in flight at once (default 4).
- `--timeout S` per-query timeout for scoring (default 30 s, BIRD's `meta_time_out`).
- `--price-in`/`--price-out` USD per million prompt/completion tokens, for the cost estimate.
  There is no built-in price list; look up the provider's current price on the day of the run.
- `--out DIR` run directory. By default it is `runs/<runner>-<model>-<evidence|noevidence>`.

**Resume**: results are appended to `predictions.jsonl` as each question finishes. Running the
same command again skips questions already recorded, so an interrupted run just continues. The
run directory's `run.json` stores its configuration (runner, model, evidence, compare mode,
timeout, backend URL, dataset hash) and a resume with different settings is refused.

Failures are split into two kinds:

- The system's failures count as wrong and are recorded: SQL that errors or times out, an empty
  answer, an `error` event from the agent, an agent that stops sending data for `--agent-timeout`
  seconds.
- Harness failures are not recorded, so a resume retries them: MySQL unreachable, LLM API errors
  (after the client's 3 retries on connection errors and 429/5xx), backend unreachable or
  answering non-200 (e.g. 400 for a schema that is not allowed). The run stops after
  `--max-infra-errors` (default 5) of them and exits non-zero.

## Outputs

Each run directory holds:

- `predictions.jsonl`, one line per question: `question_id`, `db_id`, `difficulty`,
  `predicted_sql`, `correct`, `error_kind` (`empty_sql`, `no_sql`, `pred_error`, `pred_timeout`,
  `gold_error`, `gold_timeout`), `error`, `latency_s` (time to get the SQL), `exec_s` (scoring),
  `attempts` (SQL executions: 1 for the baseline, count of `sql` events for the agent),
  `prompt_tokens`/`completion_tokens` (baseline only; the API contract has no token counts) and
  `extra` (agent: `sql_errors`, selected `tables`).
- `summary.md` and `summary.json`: EX by difficulty and total, error counts, latency (mean, p50,
  p90, sum), tokens and estimated cost, with the model, date, git commit, dataset hash and the
  exact command. `uv run datapilot-eval summarize runs/<name>` rebuilds them.

## How EX is scored

Like the official scorer (`evaluation/evaluation_ex.py` in bird-bench/mini_dev,
`calculate_ex`): run the gold and the predicted SQL against the same database and count the
prediction correct iff `set(predicted_rows) == set(gold_rows)`, where rows are the tuples from
`cursor.fetchall()`. So:

- row order is ignored, **even when the gold SQL has ORDER BY**;
- duplicate rows are ignored (it is a set comparison, not a multiset);
- column order matters, column names do not;
- values compare by Python equality, so `Decimal('2.0') == 2`;
- a prediction that errors or exceeds the timeout is wrong.

Each query gets its own connection with `max_execution_time` and a client read timeout; on a
timeout the query is also killed on the server. A gold query that fails counts as wrong and is
reported separately as `gold_error`/`gold_timeout`, so a broken database load is visible rather
than silently lowering the score.

`--compare strict` is an optional, stricter check for analysis only: rows compared as a
multiset, and in order when the gold SQL ends with a top-level ORDER BY. Reported numbers use
the default `--compare bird`.

## Baseline prompt

The baseline follows the official BIRD LLM baseline (`llm/src/prompt.py` and
`llm/src/table_schema.py`) with the dialect set to MySQL: every table rendered from `DESCRIBE`
exactly like the official `format_mysql_create_table`, the question as a SQL comment, the
evidence as `-- External Knowledge:` only with `--evidence`, and the official "think step by step
... only return SQL" instruction. One chat completion per question with temperature 0 and
`max_tokens` 1024. Differences from the official script: no stop sequences (the official
`["--", "\n\n", ";", "#"]` would end a chat model's answer at its first blank line or `#`) and Markdown code fences are
stripped from the answer. There is no SQL retry.

## Backend requirements

The agent runner follows `docs/analysis-api.md`: `POST /api/analysis/stream` with
`{question, conversationId: null, database: db_id, hint}`, reading `finalSql` from the `done`
event. For it to work the backend needs:

- the 11 BIRD schemas in `datapilot.warehouse.allowed-schemas`: `california_schools`,
  `card_games`, `codebase_community`, `debit_card_specializing`, `european_football_2`,
  `financial`, `formula_1`, `student_club`, `superhero`, `thrombosis_prediction`, `toxicology`
  (with Spring's relaxed binding, e.g. the environment variable
  `DATAPILOT_WAREHOUSE_ALLOWEDSCHEMAS=demo_shop,california_schools,...`);
- `dp_reader` to have SELECT on them (done by `load`);
- for the "+ table selection" row: a way to turn self-correction off (e.g. a max-attempts
  setting of 1);
- optional: token usage in the `done` event, so agent runs get a cost estimate too.

## Tests

```bash
uv run pytest -q                                  # unit tests: no network, no MySQL, no LLM (CI runs these)
EVAL_MYSQL_TESTS=1 uv run pytest -q -m mysql      # also against the compose MySQL
```

The unit tests cover the EX comparison, error and timeout handling, SSE parsing, the agent runner
against a local fake SSE server, the baseline prompt and runner with a fake client, dataset
extraction and verification, resume and summaries. The MySQL tests load a small dump through the
real loader and check splitting into schemas, read-only access, the relaxed `sql_mode` and
query timeouts.
