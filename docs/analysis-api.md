# Analysis API contract

The agent (backend), the chat UI (frontend) and the evaluation harness (`eval/`) are built in
parallel against this contract. Change it only together with every consumer.

## `POST /api/analysis/stream`

Runs one analysis turn and streams progress as Server-Sent Events.

Request body:

```json
{
  "question": "2026 年第三季度哪个品类的复购率下降最多？为什么？",
  "conversationId": null,
  "database": null,
  "hint": null
}
```

| Field | Type | Notes |
|---|---|---|
| `question` | string, required | The user's question |
| `conversationId` | number or null | Continue an existing conversation; null starts a new one |
| `database` | string or null | Warehouse schema to analyse. Null means the default (`datapilot.warehouse.schema`). Must be in `datapilot.warehouse.allowed-schemas`, otherwise 400 |
| `hint` | string or null | Extra domain knowledge, e.g. a metric definition. BIRD's `evidence` field is passed here |

Each SSE event has `event:` set to one of the types below and `data:` set to a JSON object.
Events arrive in order; `sql`, `sql_error` and `result` repeat for each attempt and each drill-down step.

| Event | Data | Meaning |
|---|---|---|
| `conversation` | `{ "conversationId": 12 }` | Always first |
| `plan` | `{ "text": "..." }` | What the agent intends to do next |
| `tables` | `{ "tables": ["orders", "users"] }` | Tables selected for the next query |
| `sql` | `{ "step": 1, "attempt": 1, "sql": "SELECT ..." }` | SQL about to run, after the guard rewrote it |
| `sql_error` | `{ "step": 1, "attempt": 1, "message": "Unknown column ..." }` | Guard rejection or database error; the agent will retry |
| `result` | `{ "step": 1, "attempt": 2, "columns": [...], "rows": [[...]], "truncated": false, "elapsedMs": 31 }` | Successful query result |
| `chart` | `{ "title": "...", "option": { ...ECharts option... } }` | A chart to render |
| `token` | `{ "text": "..." }` | Streamed chunk of the final conclusion (Markdown) |
| `done` | `{ "finalSql": "SELECT ...", "conclusion": "...full Markdown..." }` | Always last on success. `finalSql` is the SQL whose result answers the question (used by evaluation) |
| `error` | `{ "message": "..." }` | Unrecoverable failure; always last on failure |

## `GET /api/conversations/{id}/messages`

Returns the stored messages of a conversation, oldest first:

```json
[
  { "role": "user", "content": "...", "createdAt": "2026-10-26T10:00:00Z" },
  { "role": "assistant", "content": "...conclusion Markdown...", "trace": [ { "event": "sql", "data": { } } ], "createdAt": "..." }
]
```

`trace` is the list of SSE events of that turn (excluding `token`), so the UI can replay a past turn.

## Errors

Non-streaming errors are RFC 9457 problem details, as elsewhere in the API.
