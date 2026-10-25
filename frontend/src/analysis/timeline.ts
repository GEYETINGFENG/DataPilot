import { markRaw } from 'vue'
import type { AnalysisEvent, StoredMessage } from '@/api/analysis'
import type { QueryResult } from '@/api/warehouse'

// Folds the events of one analysis turn into the state the step timeline renders.
// The same reducer drives a live stream and the replay of a stored trace.

export interface SqlAttempt {
  attempt: number
  sql: string
  status: 'running' | 'failed' | 'succeeded'
  error?: string
  result?: QueryResult
}

export type TimelineItem =
  | { kind: 'plan'; key: string; text: string }
  | { kind: 'tables'; key: string; tables: string[] }
  | { kind: 'query'; key: string; step: number; attempts: SqlAttempt[] }
  | { kind: 'chart'; key: string; title: string; option: Record<string, unknown> }

export type QueryItem = Extract<TimelineItem, { kind: 'query' }>

export interface TurnState {
  status: 'running' | 'done' | 'error' | 'aborted'
  conversationId: number | null
  items: TimelineItem[]
  conclusion: string
  finalSql: string | null
  error: string | null
}

export function createTurn(): TurnState {
  return { status: 'running', conversationId: null, items: [], conclusion: '', finalSql: null, error: null }
}

function findQuery(turn: TurnState, step: number): QueryItem | undefined {
  for (let i = turn.items.length - 1; i >= 0; i--) {
    const item = turn.items[i]
    if (item?.kind === 'query' && item.step === step) return item
  }
  return undefined
}

function attemptFor(turn: TurnState, step: number, attempt: number, sql = ''): SqlAttempt {
  let query = findQuery(turn, step)
  if (!query) {
    query = { kind: 'query', key: `query-${step}`, step, attempts: [] }
    turn.items.push(query)
  }
  let found = query.attempts.find((a) => a.attempt === attempt)
  if (!found) {
    found = { attempt, sql, status: 'running' }
    query.attempts.push(found)
  }
  return found
}

/** Applies one event to `turn` in place. Unknown or out-of-order events never throw. */
export function applyEvent(turn: TurnState, event: AnalysisEvent): void {
  const key = `${event.event}-${turn.items.length}`
  switch (event.event) {
    case 'conversation':
      turn.conversationId = event.data.conversationId
      break
    case 'plan':
      turn.items.push({ kind: 'plan', key, text: event.data.text })
      break
    case 'tables':
      turn.items.push({ kind: 'tables', key, tables: event.data.tables })
      break
    case 'sql': {
      const attempt = attemptFor(turn, event.data.step, event.data.attempt, event.data.sql)
      attempt.sql = event.data.sql
      attempt.status = 'running'
      break
    }
    case 'sql_error': {
      const attempt = attemptFor(turn, event.data.step, event.data.attempt)
      attempt.status = 'failed'
      attempt.error = event.data.message
      break
    }
    case 'result': {
      const { step, attempt: n, columns, rows, truncated, elapsedMs } = event.data
      const attempt = attemptFor(turn, step, n)
      attempt.status = 'succeeded'
      // Rows and chart options are never mutated; skip deep reactivity for them.
      attempt.result = markRaw({ columns, rows, truncated, elapsedMs })
      break
    }
    case 'chart':
      turn.items.push({ kind: 'chart', key, title: event.data.title, option: markRaw(event.data.option) })
      break
    case 'token':
      turn.conclusion += event.data.text
      break
    case 'done':
      turn.status = 'done'
      turn.finalSql = event.data.finalSql
      // The full conclusion is authoritative over the concatenated tokens.
      turn.conclusion = event.data.conclusion
      break
    case 'error':
      turn.status = 'error'
      turn.error = event.data.message
      break
  }
}

export function reduceEvents(events: AnalysisEvent[]): TurnState {
  const turn = createTurn()
  for (const event of events) applyEvent(turn, event)
  return turn
}

/** Rebuilds a finished turn from a stored assistant message. */
export function replayMessage(message: StoredMessage): TurnState {
  const turn = reduceEvents(message.trace ?? [])
  // `trace` excludes `token` events, so the conclusion comes from the message content
  // when the trace has no `done` event (e.g. a turn that failed halfway).
  if (!turn.conclusion) turn.conclusion = message.content
  if (turn.status === 'running') turn.status = 'done'
  return turn
}

export interface TurnSummary {
  queries: number
  corrections: number
}

export function summarize(turn: TurnState): TurnSummary {
  let queries = 0
  let corrections = 0
  for (const item of turn.items) {
    if (item.kind !== 'query') continue
    queries++
    corrections += item.attempts.filter((a) => a.status === 'failed').length
  }
  return { queries, corrections }
}
