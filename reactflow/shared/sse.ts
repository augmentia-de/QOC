import { apiFetch } from './auth'

export interface SseHandlers {
  onOpen?: () => void
  onEvent: (event: unknown) => void
  onError?: (error: unknown) => void
}

export interface SseConnection {
  close: () => void
}

function parseSseChunk(chunk: string): any[] {
  const events: any[] = []
  for (const block of chunk.split(/\n\n+/)) {
    const data = block
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).replace(/^ /, ''))
      .join('\n')
    if (!data) continue
    try {
      events.push(JSON.parse(data))
    } catch {
      // Ignore malformed SSE frames, mirroring the existing UI behaviour.
    }
  }
  return events
}

export function openSse(
  path: string,
  handlers: SseHandlers,
  signal?: AbortSignal,
): SseConnection {
  const controller = new AbortController()
  const externalSignal = signal

  const combinedSignal = externalSignal
    ? (AbortSignal as any).any
      ? (AbortSignal as any).any([externalSignal, controller.signal])
      : controller.signal
    : controller.signal

  const run = async () => {
    let buffer = ''
    try {
      const response = await apiFetch(path, {
        headers: { Accept: 'text/event-stream' },
        signal: combinedSignal,
      })
      if (!response.ok || !response.body) {
        throw new Error('SSE HTTP ' + response.status)
      }
      handlers.onOpen?.()
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let index = buffer.indexOf('\n\n')
        while (index >= 0) {
          const chunk = buffer.slice(0, index)
          buffer = buffer.slice(index + 2)
          for (const event of parseSseChunk(chunk)) handlers.onEvent(event)
          index = buffer.indexOf('\n\n')
        }
      }
      if (!combinedSignal.aborted) handlers.onError?.(new Error('SSE disconnected'))
    } catch (error) {
      if (!combinedSignal.aborted) handlers.onError?.(error)
    }
  }

  void run()

  return {
    close: () => controller.abort(),
  }
}

export function scheduleReconnect(
  retryCount: { value: number },
  maxRetries: number,
  baseDelay: number,
  reconnect: () => void,
): boolean {
  if (retryCount.value >= maxRetries) return false
  retryCount.value += 1
  const delay = Math.min(baseDelay * 2 ** (retryCount.value - 1), 30000)
  window.setTimeout(reconnect, delay)
  return true
}