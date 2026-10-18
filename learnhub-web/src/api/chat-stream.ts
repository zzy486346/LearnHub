export interface ServerSentEvent {
  event: string
  data: string
  id?: string
}

export interface SseParserOptions {
  maxEventBytes?: number
}

const DEFAULT_MAX_EVENT_BYTES = 256 * 1024

export function createSseParser(
  onEvent: (event: ServerSentEvent) => void,
  options: SseParserOptions = {},
) {
  const decoder = new TextDecoder()
  const maxEventBytes = options.maxEventBytes ?? DEFAULT_MAX_EVENT_BYTES
  let buffer = ''
  let eventBytes = 0
  let eventName = 'message'
  let eventId: string | undefined
  let dataLines: string[] = []

  function dispatch() {
    if (dataLines.length) {
      onEvent({ event: eventName || 'message', data: dataLines.join('\n'), ...(eventId === undefined ? {} : { id: eventId }) })
    }
    eventBytes = 0
    eventName = 'message'
    eventId = undefined
    dataLines = []
  }

  function consumeLine(line: string) {
    if (line === '') {
      dispatch()
      return
    }
    eventBytes += new TextEncoder().encode(`${line}\n`).byteLength
    if (eventBytes > maxEventBytes) throw new Error('SSE_EVENT_TOO_LARGE')
    if (line.startsWith(':')) return
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') eventName = value
    else if (field === 'data') dataLines.push(value)
    else if (field === 'id' && !value.includes('\0')) eventId = value
  }

  function consumeText(text: string) {
    buffer += text
    let newline = buffer.indexOf('\n')
    while (newline >= 0) {
      let line = buffer.slice(0, newline)
      if (line.endsWith('\r')) line = line.slice(0, -1)
      buffer = buffer.slice(newline + 1)
      consumeLine(line)
      newline = buffer.indexOf('\n')
    }
    if (new TextEncoder().encode(buffer).byteLength + eventBytes > maxEventBytes) {
      throw new Error('SSE_EVENT_TOO_LARGE')
    }
  }

  return {
    push(chunk: Uint8Array) {
      consumeText(decoder.decode(chunk, { stream: true }))
    },
    finish() {
      consumeText(decoder.decode())
      if (buffer || eventBytes > 0 || dataLines.length > 0 || eventName !== 'message' || eventId !== undefined) {
        throw new Error('SSE_TRUNCATED_EVENT')
      }
    },
  }
}

export async function consumeSseStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: ServerSentEvent) => void,
  options: SseParserOptions = {},
) {
  const parser = createSseParser(onEvent, options)
  const reader = stream.getReader()
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) parser.push(value)
    }
    parser.finish()
  } catch (error) {
    try { await reader.cancel(error) } catch { /* 保留原始解析错误。 */ }
    throw error
  } finally {
    reader.releaseLock()
  }
}
