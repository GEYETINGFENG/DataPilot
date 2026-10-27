import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin } from 'vite'
import { demoRun, failingRun, type ScriptedEvent } from './demoRun.ts'

// Dev-server stand-in for the analysis API (docs/analysis-api.md), enabled with VITE_MOCK=1.
// Every question replays the scripted demo run; a question containing「失败」replays a failure.

interface StoredMessage {
  role: 'user' | 'assistant'
  content: string
  trace?: { event: string; data: unknown }[]
  createdAt: string
}

const conversations = new Map<number, StoredMessage[]>()
let nextConversationId = 101

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = ''
    req.setEncoding('utf8')
    req.on('data', (chunk: string) => (body += chunk))
    req.on('end', () => resolve(body))
    req.on('error', reject)
  })
}

function problem(res: ServerResponse, status: number, detail: string) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/problem+json')
  res.end(JSON.stringify({ type: 'about:blank', title: 'Bad Request', status, detail }))
}

async function stream(req: IncomingMessage, res: ServerResponse) {
  let request: { question?: unknown; conversationId?: unknown }
  try {
    request = JSON.parse(await readBody(req))
  } catch {
    return problem(res, 400, '请求体不是合法的 JSON')
  }
  const question = typeof request.question === 'string' ? request.question.trim() : ''
  if (!question) return problem(res, 400, 'question 不能为空')

  let conversationId = typeof request.conversationId === 'number' ? request.conversationId : null
  if (conversationId === null || !conversations.has(conversationId)) {
    conversationId = nextConversationId++
    conversations.set(conversationId, [])
  }
  const messages = conversations.get(conversationId)!
  messages.push({ role: 'user', content: question, createdAt: new Date().toISOString() })

  res.writeHead(200, {
    'Content-Type': 'text/event-stream;charset=UTF-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  })

  const script: ScriptedEvent[] = question.includes('失败') ? failingRun(conversationId) : demoRun(conversationId)
  const trace: { event: string; data: unknown }[] = []
  let closed = false
  let timer: NodeJS.Timeout | undefined
  res.on('close', () => {
    closed = true
    clearTimeout(timer)
  })

  for (const { delay, event, data } of script) {
    await new Promise<void>((resolve) => (timer = setTimeout(resolve, delay)))
    if (closed) return
    res.write(`event:${event}\ndata:${JSON.stringify(data)}\n\n`)
    if (event !== 'token') trace.push({ event, data })
  }
  const last = script[script.length - 1]
  const conclusion = last?.event === 'done' ? (last.data as { conclusion: string }).conclusion : ''
  messages.push({ role: 'assistant', content: conclusion, trace, createdAt: new Date().toISOString() })
  res.end()
}

function history(res: ServerResponse, id: number) {
  const messages = conversations.get(id)
  if (!messages) return problem(res, 404, `会话 ${id} 不存在（mock 数据在重启 dev server 后清空）`)
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify(messages))
}

export function analysisMock(): Plugin {
  return {
    name: 'datapilot-analysis-mock',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url?.split('?')[0] ?? ''
        if (req.method === 'POST' && url === '/api/analysis/stream') {
          stream(req, res).catch(next)
          return
        }
        const match = req.method === 'GET' ? /^\/api\/conversations\/(\d+)\/messages$/.exec(url) : null
        if (match) return history(res, Number(match[1]))
        next()
      })
    },
  }
}
