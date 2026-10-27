import { describe, expect, it, vi } from 'vitest'
import { AnalysisHttpError, SseParser, streamAnalysis, type AnalysisEvent } from '../analysis'

function parseAll(chunks: string[]) {
  const parser = new SseParser()
  return [...chunks.flatMap((chunk) => parser.push(chunk)), ...parser.end()]
}

describe('SseParser', () => {
  it('parses events with and without a space after the colon', () => {
    expect(parseAll(['event:plan\ndata:{"text":"a"}\n\nevent: tables\ndata: {"tables":[]}\n\n'])).toEqual([
      { event: 'plan', data: '{"text":"a"}', id: undefined },
      { event: 'tables', data: '{"tables":[]}', id: undefined },
    ])
  })

  it('reassembles events split across chunks at every position', () => {
    const stream = 'event:sql\ndata:{"step":1,"attempt":1,"sql":"SELECT 1"}\n\nevent:token\ndata:{"text":"母婴"}\n\n'
    const expected = parseAll([stream])
    expect(expected).toHaveLength(2)
    for (let i = 1; i < stream.length; i++) {
      expect(parseAll([stream.slice(0, i), stream.slice(i)])).toEqual(expected)
    }
    expect(parseAll(stream.split(''))).toEqual(expected)
  })

  it('handles CRLF and CR line endings, including a CRLF split between chunks', () => {
    const expected = [{ event: 'plan', data: 'x', id: undefined }]
    expect(parseAll(['event:plan\r\ndata:x\r\n\r\n'])).toEqual(expected)
    expect(parseAll(['event:plan\rdata:x\r\r'])).toEqual(expected)
    expect(parseAll(['event:plan\r', '\ndata:x\r', '\n\r', '\n'])).toEqual(expected)
  })

  it('joins multi-line data with newlines', () => {
    const [message] = parseAll(['event:done\ndata:{"finalSql":"SELECT 1",\ndata: "conclusion":"ok"}\n\n'])
    expect(message?.data).toBe('{"finalSql":"SELECT 1",\n"conclusion":"ok"}')
    expect(JSON.parse(message!.data)).toEqual({ finalSql: 'SELECT 1', conclusion: 'ok' })
  })

  it('ignores comments, unknown fields and blocks without data', () => {
    expect(parseAll([':keep-alive\n\nretry:1000\nevent:plan\n\nfoo:bar\ndata:x\n\n'])).toEqual([
      { event: 'message', data: 'x', id: undefined },
    ])
  })

  it('does not dispatch an event that is missing its blank line', () => {
    expect(parseAll(['event:plan\ndata:x\n'])).toEqual([])
  })
})

function sseResponse(chunks: string[], signal?: AbortSignal): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      if (signal) {
        // Stay open like a live run until aborted.
        signal.addEventListener('abort', () => controller.error(signal.reason))
      } else {
        controller.close()
      }
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('streamAnalysis', () => {
  const request = { question: 'q', conversationId: null }

  it('delivers typed events in order and splits multi-byte characters correctly', async () => {
    const bytes = new TextEncoder().encode('event:conversation\ndata:{"conversationId":7}\n\nevent:token\ndata:{"text":"复购率"}\n\nevent:done\ndata:{"finalSql":"SELECT 1","conclusion":"复购率"}\n\n')
    // Split inside a three-byte UTF-8 character.
    const cut = bytes.indexOf(0xe5) + 1
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.slice(0, cut))
        controller.enqueue(bytes.slice(cut))
        controller.close()
      },
    })
    const events: AnalysisEvent[] = []
    const fetchMock = vi.fn(async () => new Response(body))
    await streamAnalysis(request, { onEvent: (e) => events.push(e), fetch: fetchMock })

    expect(events).toEqual([
      { event: 'conversation', data: { conversationId: 7 } },
      { event: 'token', data: { text: '复购率' } },
      { event: 'done', data: { finalSql: 'SELECT 1', conclusion: '复购率' } },
    ])
    expect(fetchMock).toHaveBeenCalledWith('/api/analysis/stream', expect.objectContaining({ method: 'POST' }))
    const init = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(JSON.parse(init[1].body as string)).toEqual({ question: 'q', conversationId: null, database: null, hint: null })
  })

  it('skips unknown event types', async () => {
    const events: AnalysisEvent[] = []
    const fetchMock = async () => sseResponse(['event:heartbeat\ndata:{}\n\nevent:error\ndata:{"message":"boom"}\n\n'])
    await streamAnalysis(request, { onEvent: (e) => events.push(e), fetch: fetchMock })
    expect(events).toEqual([{ event: 'error', data: { message: 'boom' } }])
  })

  it('rejects when the stream ends without done or error', async () => {
    const fetchMock = async () => sseResponse(['event:plan\ndata:{"text":"a"}\n\n'])
    await expect(streamAnalysis(request, { onEvent: () => {}, fetch: fetchMock })).rejects.toThrow('连接意外中断')
  })

  it('surfaces problem details of a non-2xx response', async () => {
    const fetchMock = async () =>
      new Response(JSON.stringify({ title: 'Bad Request', status: 400, detail: 'database 不在允许范围内' }), {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      })
    const promise = streamAnalysis(request, { onEvent: () => {}, fetch: fetchMock })
    await expect(promise).rejects.toBeInstanceOf(AnalysisHttpError)
    await expect(promise).rejects.toMatchObject({ status: 400, message: 'database 不在允许范围内' })
  })

  it('stops delivering events and rejects with AbortError when aborted mid-stream', async () => {
    const controller = new AbortController()
    const events: AnalysisEvent[] = []
    const fetchMock = async (_url: string | URL | Request, init?: RequestInit) =>
      sseResponse(['event:plan\ndata:{"text":"a"}\n\n'], init?.signal ?? undefined)
    const promise = streamAnalysis(request, {
      signal: controller.signal,
      fetch: fetchMock,
      onEvent(event) {
        events.push(event)
        controller.abort()
      },
    })
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(events).toHaveLength(1)
  })
})
