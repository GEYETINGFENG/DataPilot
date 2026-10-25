import { describe, expect, it } from 'vitest'
import type { AnalysisEvent } from '@/api/analysis'
import { reduceEvents, replayMessage, summarize, type QueryItem } from '../timeline'

const result = (step: number, attempt: number) =>
  ({
    event: 'result',
    data: { step, attempt, columns: ['c'], rows: [[1]], truncated: false, elapsedMs: 5 },
  }) satisfies AnalysisEvent

const run: AnalysisEvent[] = [
  { event: 'conversation', data: { conversationId: 12 } },
  { event: 'plan', data: { text: '比较各品类复购率' } },
  { event: 'tables', data: { tables: ['orders', 'categories'] } },
  { event: 'sql', data: { step: 1, attempt: 1, sql: 'SELECT created_at' } },
  { event: 'sql_error', data: { step: 1, attempt: 1, message: "Unknown column 'created_at'" } },
  { event: 'sql', data: { step: 1, attempt: 2, sql: 'SELECT order_date' } },
  result(1, 2),
  { event: 'chart', data: { title: '复购率', option: { series: [] } } },
  { event: 'plan', data: { text: '按大区下钻' } },
  { event: 'sql', data: { step: 2, attempt: 1, sql: 'SELECT region' } },
  result(2, 1),
  { event: 'token', data: { text: '母婴' } },
  { event: 'token', data: { text: '下降最多' } },
]

describe('timeline reducer', () => {
  it('builds the step timeline in event order', () => {
    const turn = reduceEvents(run)
    expect(turn.status).toBe('running')
    expect(turn.conversationId).toBe(12)
    expect(turn.items.map((i) => i.kind)).toEqual(['plan', 'tables', 'query', 'chart', 'plan', 'query'])
    expect(new Set(turn.items.map((i) => i.key)).size).toBe(turn.items.length)
    expect(turn.conclusion).toBe('母婴下降最多')
  })

  it('groups attempts of a step and marks the failed one with its error', () => {
    const turn = reduceEvents(run)
    const step1 = turn.items[2] as QueryItem
    expect(step1.step).toBe(1)
    expect(step1.attempts).toMatchObject([
      { attempt: 1, sql: 'SELECT created_at', status: 'failed', error: "Unknown column 'created_at'" },
      { attempt: 2, sql: 'SELECT order_date', status: 'succeeded', result: { columns: ['c'], rows: [[1]] } },
    ])
    expect(summarize(turn)).toEqual({ queries: 2, corrections: 1 })
  })

  it('marks an attempt as running until its result or error arrives', () => {
    const turn = reduceEvents(run.slice(0, 6))
    expect((turn.items[2] as QueryItem).attempts[1]?.status).toBe('running')
  })

  it('takes the full conclusion and final SQL from done', () => {
    const turn = reduceEvents([...run, { event: 'done', data: { finalSql: 'SELECT order_date', conclusion: '# 完整结论' } }])
    expect(turn.status).toBe('done')
    expect(turn.finalSql).toBe('SELECT order_date')
    expect(turn.conclusion).toBe('# 完整结论')
  })

  it('records an error event', () => {
    const turn = reduceEvents([...run.slice(0, 3), { event: 'error', data: { message: '模型超时' } }])
    expect(turn.status).toBe('error')
    expect(turn.error).toBe('模型超时')
  })

  it('tolerates a result or error without a preceding sql event', () => {
    const turn = reduceEvents([result(3, 1), { event: 'sql_error', data: { step: 4, attempt: 1, message: 'x' } }])
    expect(turn.items).toMatchObject([
      { kind: 'query', step: 3, attempts: [{ status: 'succeeded', sql: '' }] },
      { kind: 'query', step: 4, attempts: [{ status: 'failed', error: 'x' }] },
    ])
  })

  it('replays a stored assistant message', () => {
    const trace = run.filter((e) => e.event !== 'token')
    const done = replayMessage({
      role: 'assistant',
      content: '结论',
      trace: [...trace, { event: 'done', data: { finalSql: 'SELECT order_date', conclusion: '结论' } }],
      createdAt: '2026-10-26T10:00:00Z',
    })
    expect(done.status).toBe('done')
    expect(done.items).toHaveLength(6)
    expect(done.conclusion).toBe('结论')

    // A trace without done (e.g. stored mid-run) still renders, using the stored content.
    const partial = replayMessage({ role: 'assistant', content: '部分结论', trace, createdAt: '' })
    expect(partial.status).toBe('done')
    expect(partial.conclusion).toBe('部分结论')
  })
})
