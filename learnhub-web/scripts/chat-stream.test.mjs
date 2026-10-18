import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

async function loadParser() {
  const source = await readFile(new URL('../src/api/chat-stream.ts', import.meta.url), 'utf8')
  const code = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)
}

async function loadChatApi(harness) {
  const parser = await readFile(new URL('../src/api/chat-stream.ts', import.meta.url), 'utf8')
  const parserCode = ts.transpileModule(parser, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
  const parserUrl = `data:text/javascript;base64,${Buffer.from(parserCode).toString('base64')}`
  const source = (await readFile(new URL('../src/api/chat.ts', import.meta.url), 'utf8'))
    .replace(/import \{ getSessionGeneration, http, isCurrentSession \} from ['"]\.\/http['"]/,
      'const { http, getSessionGeneration, isCurrentSession } = globalThis.chatApiHarness')
    .replace(/import \{ SessionExpiredError \} from ['"]\.\/session['"]/,
      "class SessionExpiredError extends Error { constructor() { super('AUTH_SESSION_EXPIRED'); this.code = 'AUTH_SESSION_EXPIRED' } }")
    .replace(/import \{ consumeSseStream,[^}]*\} from ['"]\.\/chat-stream['"]/, `import { consumeSseStream } from ${JSON.stringify(parserUrl)}`)
  globalThis.chatApiHarness = {
    getSessionGeneration: () => 0, isCurrentSession: generation => generation === 0, ...harness,
  }
  const code = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(`${code}\n// ${Math.random()}`).toString('base64')}`)
}

function splitBytes(bytes, points) {
  const chunks = []
  let start = 0
  for (const point of points) {
    chunks.push(bytes.slice(start, point))
    start = point
  }
  chunks.push(bytes.slice(start))
  return chunks.filter(chunk => chunk.byteLength)
}

test('UTF-8中文跨字节分包、CRLF和多事件均完整解析', async () => {
  const { createSseParser } = await loadParser()
  const events = []
  const parser = createSseParser(event => events.push(event))
  const bytes = new TextEncoder().encode(
    'event: accepted\r\nid: e-1\r\ndata: {"runId":"r1","text":"开始"}\r\n\r\n'
    + 'event: delta\ndata: {"runId":"r1",\ndata: "text":"缓存击穿"}\n\n',
  )
  const chineseOffset = bytes.findIndex((value, index) => value >= 0x80 && index > 0)
  for (const chunk of splitBytes(bytes, [1, 7, chineseOffset + 1, chineseOffset + 2, bytes.length - 3])) {
    parser.push(chunk)
  }
  parser.finish()
  assert.deepEqual(events, [
    { event: 'accepted', id: 'e-1', data: '{"runId":"r1","text":"开始"}' },
    { event: 'delta', data: '{"runId":"r1",\n"text":"缓存击穿"}' },
  ])
})

test('半包保留到后续chunk，EOF丢弃未用空行终止的事件', async () => {
  const { createSseParser } = await loadParser()
  const events = []
  const parser = createSseParser(event => events.push(event))
  parser.push(new TextEncoder().encode('event: delta\nda'))
  assert.equal(events.length, 0)
  parser.push(new TextEncoder().encode('ta: {"runId":"r2","text":"A"}\n\nevent: final\ndata: {"runId":"r2"}'))
  assert.equal(events.length, 1)
  assert.throws(() => parser.finish(), /SSE_TRUNCATED_EVENT/)
  assert.equal(events.length, 1)
})

test('注释心跳和未知字段不产生事件', async () => {
  const { createSseParser } = await loadParser()
  const events = []
  const parser = createSseParser(event => events.push(event))
  parser.push(new TextEncoder().encode(': heartbeat\nretry: 1000\n\nevent: status\ndata: ok\n\n'))
  parser.finish()
  assert.deepEqual(events, [{ event: 'status', data: 'ok' }])
})

test('单事件超过边界时立即拒绝', async () => {
  const { createSseParser } = await loadParser()
  const parser = createSseParser(() => {}, { maxEventBytes: 32 })
  assert.throws(() => parser.push(new TextEncoder().encode(`event: delta\ndata: ${'x'.repeat(40)}`)), /SSE_EVENT_TOO_LARGE/)
})

test('流解析失败会cancel底层reader并保留原始错误', async () => {
  const { consumeSseStream } = await loadParser()
  let cancelled = false
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(`event: delta\ndata: ${'x'.repeat(40)}`))
    },
    cancel() { cancelled = true },
  })
  await assert.rejects(() => consumeSseStream(stream, () => {}, { maxEventBytes: 32 }), /SSE_EVENT_TOO_LARGE/)
  assert.equal(cancelled, true)
})

test('聊天流只发送一次带Authorization的POST并转发命名事件', async () => {
  const requests = []
  globalThis.localStorage = { getItem: key => key === 'learnhub_access_token' ? 'token-1' : null }
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          'event: accepted\ndata: {"runId":"r1","eventId":"e1","seq":1,"modelMode":"FAKE"}\n\n'
          + 'event: final\ndata: {"runId":"r1","eventId":"e2","seq":2,"status":"COMPLETED"}\n\n'
          + 'event: delta\ndata: {"runId":"r1","eventId":"e3","seq":3,"text":"迟到内容"}\n\n',
        ))
        controller.close()
      },
    })
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  }
  const http = { defaults: { baseURL: '/api' }, get() {}, post() {}, patch() {}, delete() {} }
  const { chatApi } = await loadChatApi({ http })
  const events = []
  await chatApi.streamRun('c/1', { message: '继续', clientRequestId: 'client-1' }, event => events.push(event))
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/assistant/conversations/c%2F1/runs')
  assert.equal(requests[0].options.method, 'POST')
  assert.equal(requests[0].options.headers.Authorization, 'Bearer token-1')
  assert.deepEqual(events, [
    { type: 'accepted', payload: { runId: 'r1', eventId: 'e1', seq: 1, modelMode: 'FAKE' } },
    { type: 'final', payload: { runId: 'r1', eventId: 'e2', seq: 2, status: 'COMPLETED' } },
  ])
})

test('停止生成等待取消请求结束后才abort，取消失败也会断流', async () => {
  let finishCancel
  const http = {
    defaults: { baseURL: '/api' }, get() {}, patch() {}, delete() {},
    post: () => new Promise(resolve => { finishCancel = resolve }),
  }
  const { cancelRunThenAbort } = await loadChatApi({ http })
  const controller = new AbortController()
  const pending = cancelRunThenAbort('run-1', controller)
  assert.equal(controller.signal.aborted, false)
  finishCancel({ data: {} })
  await pending
  assert.equal(controller.signal.aborted, true)

  const failingHttp = { ...http, post: async () => { throw new Error('cancel failed') } }
  const failingModule = await loadChatApi({ http: failingHttp })
  const failingController = new AbortController()
  await assert.rejects(() => failingModule.cancelRunThenAbort('run-2', failingController), /cancel failed/)
  assert.equal(failingController.signal.aborted, true)
})

test('原生聊天流在会话代次变化后取消读取并拒绝迟到事件', async () => {
  let generation = 3
  let cancelled = false
  globalThis.localStorage = { getItem: key => key === 'learnhub_access_token' ? 'token-1' : null }
  globalThis.fetch = async () => new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(
        'event: accepted\ndata: {"runId":"r1","eventId":"e1","seq":1}\n\n'
        + 'event: delta\ndata: {"runId":"r1","eventId":"e2","seq":2,"text":"旧账户内容"}\n\n',
      ))
    },
    cancel() { cancelled = true },
  }), { status: 200 })
  const http = { defaults: { baseURL: '/api' }, get() {}, post() {}, patch() {}, delete() {} }
  const { chatApi } = await loadChatApi({
    http, getSessionGeneration: () => 3, isCurrentSession: expected => expected === generation,
  })
  const events = []
  await assert.rejects(() => chatApi.streamRun('c1', { message: '问题', clientRequestId: 'id-1' }, event => {
    events.push(event)
    generation = 4
  }), /AUTH_SESSION_EXPIRED/)
  assert.deepEqual(events.map(event => event.type), ['accepted'])
  assert.equal(cancelled, true)
})
