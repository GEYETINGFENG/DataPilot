import axios from 'axios'
import type { QueryResult } from './warehouse'

// Types and client for the analysis API. The contract is docs/analysis-api.md.

export interface AnalysisRequest {
  question: string
  conversationId: number | null
  database?: string | null
  hint?: string | null
}

export type AnalysisEvent =
  | { event: 'conversation'; data: { conversationId: number } }
  | { event: 'plan'; data: { text: string } }
  | { event: 'tables'; data: { tables: string[] } }
  | { event: 'sql'; data: { step: number; attempt: number; sql: string } }
  | { event: 'sql_error'; data: { step: number; attempt: number; message: string } }
  | { event: 'result'; data: { step: number; attempt: number } & QueryResult }
  | { event: 'chart'; data: { title: string; option: Record<string, unknown> } }
  | { event: 'token'; data: { text: string } }
  | { event: 'done'; data: { finalSql: string; conclusion: string } }
  | { event: 'error'; data: { message: string } }

export type AnalysisEventType = AnalysisEvent['event']

const EVENT_TYPES: ReadonlySet<string> = new Set<AnalysisEventType>([
  'conversation', 'plan', 'tables', 'sql', 'sql_error', 'result', 'chart', 'token', 'done', 'error',
])

export interface StoredMessage {
  role: 'user' | 'assistant'
  content: string
  /** SSE events of the turn, excluding `token`. Only on assistant messages. */
  trace?: AnalysisEvent[]
  createdAt: string
}

/** One dispatched Server-Sent Event, before its data is interpreted. */
export interface SseMessage {
  event: string
  data: string
  id?: string
}

/**
 * Incremental parser for the `text/event-stream` format
 * (https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation).
 * Feed it decoded text in arbitrary chunks; it returns the events completed by each chunk.
 */
export class SseParser {
  private buffer = ''
  private eventType = ''
  private dataLines: string[] = []
  private id: string | undefined

  push(chunk: string): SseMessage[] {
    this.buffer += chunk
    const messages: SseMessage[] = []
    let start = 0
    for (let i = 0; i < this.buffer.length; i++) {
      const char = this.buffer[i]
      if (char !== '\n' && char !== '\r') continue
      // A CR at the very end may be the first half of a CRLF split across chunks.
      if (char === '\r' && i === this.buffer.length - 1) break
      this.processLine(this.buffer.slice(start, i), messages)
      if (char === '\r' && this.buffer[i + 1] === '\n') i++
      start = i + 1
    }
    this.buffer = this.buffer.slice(start)
    return messages
  }

  /**
   * Call at end of stream. Returns an event completed by a final lone CR that `push` held back;
   * per the spec, an event still missing its terminating blank line is discarded.
   */
  end(): SseMessage[] {
    const messages: SseMessage[] = []
    if (this.buffer.endsWith('\r')) this.processLine(this.buffer.slice(0, -1), messages)
    this.buffer = ''
    this.eventType = ''
    this.dataLines = []
    return messages
  }

  private processLine(line: string, messages: SseMessage[]) {
    if (line === '') {
      if (this.dataLines.length > 0) {
        messages.push({ event: this.eventType || 'message', data: this.dataLines.join('\n'), id: this.id })
      }
      this.eventType = ''
      this.dataLines = []
      return
    }
    if (line.startsWith(':')) return
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    switch (field) {
      case 'event':
        this.eventType = value
        break
      case 'data':
        this.dataLines.push(value)
        break
      case 'id':
        if (!value.includes('\0')) this.id = value
        break
    }
  }
}

/** Non-2xx response to the stream request, carrying the RFC 9457 problem detail. */
export class AnalysisHttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'AnalysisHttpError'
  }
}

async function problemFromResponse(response: Response): Promise<AnalysisHttpError> {
  let message = `请求失败（HTTP ${response.status}）`
  try {
    const body = await response.json()
    if (body && typeof body === 'object') message = body.detail || body.title || message
  } catch {
    // Not a problem-details body; keep the generic message.
  }
  return new AnalysisHttpError(response.status, message)
}

export function toAnalysisEvent(message: SseMessage): AnalysisEvent | null {
  if (!EVENT_TYPES.has(message.event)) return null
  return { event: message.event, data: JSON.parse(message.data) } as AnalysisEvent
}

export interface StreamOptions {
  onEvent: (event: AnalysisEvent) => void
  signal?: AbortSignal
  fetch?: typeof fetch
}

/**
 * Runs one analysis turn, calling `onEvent` for each event in order.
 * Resolves once the stream ends. Rejects with an AnalysisHttpError for a non-2xx response,
 * with an AbortError when `signal` aborts, and with an Error if the stream ends before
 * a `done` or `error` event. An `error` event is delivered to `onEvent`, not thrown.
 */
export async function streamAnalysis(request: AnalysisRequest, options: StreamOptions): Promise<void> {
  const doFetch = options.fetch ?? fetch
  const response = await doFetch('/api/analysis/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ database: null, hint: null, ...request }),
    signal: options.signal,
  })
  if (!response.ok) throw await problemFromResponse(response)
  if (!response.body) throw new Error('响应没有内容')

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  const parser = new SseParser()
  let finished = false
  const dispatch = (messages: SseMessage[]) => {
    for (const message of messages) {
      const event = toAnalysisEvent(message)
      if (!event) continue
      options.onEvent(event)
      if (event.event === 'done' || event.event === 'error') finished = true
    }
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      dispatch(parser.push(value))
    }
    dispatch(parser.end())
  } finally {
    reader.releaseLock()
  }
  if (!finished) throw new Error('连接意外中断，分析未完成')
}

export async function getConversationMessages(conversationId: number): Promise<StoredMessage[]> {
  const { data } = await axios.get<StoredMessage[]>(`/api/conversations/${conversationId}/messages`)
  return data
}
