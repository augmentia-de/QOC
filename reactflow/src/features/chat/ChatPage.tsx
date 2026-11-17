import { useEffect, useRef, useState } from 'react'
import { Catalog, QocApi, Questions, Sessions } from '../../services/qocApi'
import { openSse } from '@shared/sse'
import type { SseConnection } from '@shared/sse'
import { renderMarkdown } from '@shared/markdown'

interface SessionInfo {
  id: string
  title?: string
  agent?: string
  directory?: string
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'tool' | 'error'
  text: string
  time?: string
}

interface AgentOption {
  value: string
  label: string
}

interface TaskInfo {
  id: string
  agent?: string
  state?: string
  text?: string
  error?: string
  childSession?: string
  tokens?: number
}

interface PermissionInfo {
  id: string
  sessionId: string
  action: string
  resources: string[]
  message?: string
}

interface QuestionOption {
  label: string
  description?: string
}

interface QuestionEntry {
  question: string
  options?: QuestionOption[]
  multiple?: boolean
}

interface QuestionInfo {
  id: string
  sessionId: string
  questions?: QuestionEntry[]
}

function uid(): string {
  return crypto.randomUUID()
}

function roleClass(role: ChatMessage['role']): string {
  if (role === 'user') return '#337ab7'
  if (role === 'assistant') return '#5bc0de'
  if (role === 'error') return '#d9534f'
  return '#999'
}

export default function ChatPage() {
  const [sessions, setSessions] = useState<SessionInfo[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [status, setStatus] = useState('ready')
  const [agents, setAgents] = useState<AgentOption[]>([])
  const [agent, setAgent] = useState('')
  const [showDetails, setShowDetails] = useState(false)
  const [showLlm, setShowLlm] = useState(false)
  const [events, setEvents] = useState<string[]>([])
  const [tasks, setTasks] = useState<TaskInfo[]>([])
  const [questions, setQuestions] = useState<QuestionInfo[]>([])
  const [pendingPermission, setPendingPermission] = useState<PermissionInfo | null>(null)
  const [questionText, setQuestionText] = useState<Record<string, string>>({})
  const [questionSelected, setQuestionSelected] = useState<Record<string, string[]>>({})

  const sseRef = useRef<SseConnection | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const currentAssistantIdRef = useRef<string | null>(null)
  const assistantRawRef = useRef('')
  const sawStreamedRef = useRef(false)
  const sendingRef = useRef(false)
  const permissionTimerRef = useRef<number | null>(null)
  const taskTimerRef = useRef<number | null>(null)
  const questionTimerRef = useRef<number | null>(null)

  const pushEvent = (text: string) => {
    setEvents((prev) => [text, ...prev].slice(0, 10))
  }

  const addMessage = (role: ChatMessage['role'], text: string, time?: string): string => {
    const id = uid()
    setMessages((prev) => [...prev, { id, role, text, time }])
    return id
  }

  const updateMessage = (id: string, text: string) => {
    setMessages((prev) => prev.map((message) => (message.id === id ? { ...message, text } : message)))
  }

  const closeSse = () => {
    sseRef.current?.close()
    sseRef.current = null
  }

  const loadTasks = async (id: string): Promise<boolean> => {
    try {
      const data = await Sessions.tasks(id)
      const list = Array.isArray(data?.data) ? data.data as TaskInfo[] : []
      setTasks(list)
      return list.some((task) => task.state === 'running')
    } catch (error) {
      pushEvent('tasks: ' + (error instanceof Error ? error.message : String(error)))
      return false
    }
  }

  const startTaskPoll = (id: string) => {
    if (taskTimerRef.current !== null) {
      window.clearInterval(taskTimerRef.current)
      taskTimerRef.current = null
    }
    void loadTasks(id).then((running) => {
      if (!running) return
      taskTimerRef.current = window.setInterval(() => {
        if (sessionIdRef.current !== id) {
          if (taskTimerRef.current !== null) window.clearInterval(taskTimerRef.current)
          taskTimerRef.current = null
          return
        }
        void loadTasks(id).then((stillRunning) => {
          if (!stillRunning && taskTimerRef.current !== null) {
            window.clearInterval(taskTimerRef.current)
            taskTimerRef.current = null
          }
        })
      }, 5000)
    })
  }

  const loadQuestions = async () => {
    try {
      const data = await Questions.list()
      setQuestions(Array.isArray(data?.data) ? data.data as QuestionInfo[] : [])
    } catch {
      // Keep the last known question list.
    }
  }

  const handleSseEvent = (event: unknown) => {
    const ev = event as Record<string, unknown>
    if (ev && typeof ev._type === 'string') {
      switch (ev._type) {
        case 'TextStarted': {
          sawStreamedRef.current = true
          const id = addMessage('assistant', '', typeof ev.time === 'string' ? ev.time : undefined)
          currentAssistantIdRef.current = id
          assistantRawRef.current = ''
          break
        }
        case 'TextDelta': {
          sawStreamedRef.current = true
          if (!currentAssistantIdRef.current) {
            const id = addMessage('assistant', '', typeof ev.time === 'string' ? ev.time : undefined)
            currentAssistantIdRef.current = id
          }
          assistantRawRef.current += typeof ev.text === 'string' ? ev.text : ''
          const id = currentAssistantIdRef.current
          if (id) updateMessage(id, assistantRawRef.current)
          break
        }
        case 'TextEnded': {
          if (typeof ev.text === 'string' && !assistantRawRef.current) assistantRawRef.current = ev.text
          const id = currentAssistantIdRef.current
          if (id) updateMessage(id, assistantRawRef.current || (typeof ev.text === 'string' ? ev.text : ''))
          currentAssistantIdRef.current = null
          assistantRawRef.current = ''
          break
        }
        case 'ToolCallRequested': {
          if (!showDetails) break
          sawStreamedRef.current = true
          const name = typeof ev.name === 'object' && ev.name !== null && 'value' in ev.name
            ? String((ev.name as { value?: unknown }).value)
            : String(ev.name ?? '')
          let input = ''
          try {
            input = typeof ev.input === 'string' ? ev.input : JSON.stringify(ev.input)
          } catch {
            input = String(ev.input)
          }
          if (input.length > 1500) input = input.slice(0, 1500) + '…'
          addMessage('tool', '🔧 ' + name + '(' + input + ')')
          break
        }
        case 'ToolResultProvided': {
          if (!showDetails) break
          sawStreamedRef.current = true
          const outcome = ev.outcome as { text?: unknown; value?: unknown } | undefined
          const result = outcome?.text ?? (outcome?.value ? JSON.stringify(outcome.value) : '')
          const trimmed = String(result ?? '').slice(0, 4000)
          addMessage('tool', '↳ ' + (trimmed || '(no result)'))
          break
        }
        case 'ToolFailed':
          addMessage('error', '⚠ ' + String(ev.message ?? 'Tool failed'))
          break
        case 'ProviderFailed':
          addMessage('error', 'Provider error: ' + String(ev.message ?? ''))
          break
        case 'RetryScheduled':
          addMessage('tool', '⏳ Retry in ' + String(ev.delay ?? '') + 's (Versuch ' + String(ev.attempt ?? '') + ')')
          break
        case 'SubagentStarted':
        case 'SubagentFinished':
        case 'SubagentCancelled':
          pushEvent(String(ev._type) + (typeof ev.agent === 'string' ? ': ' + ev.agent : ''))
          void loadTasks(sessionIdRef.current ?? '')
          break
        case 'LlmRequest':
        case 'LlmResponse':
          if (showLlm) pushEvent(String(ev._type))
          break
        default:
          pushEvent(String(ev._type))
      }
      return
    }

    if (typeof ev.type === 'string') {
      if (ev.type === 'tool_result') {
        const trimmed = String(ev.result ?? '').slice(0, 4000)
        addMessage('tool', '↳ ' + (trimmed || '(no result)'))
        return
      }
      pushEvent(ev.type + (typeof (ev.data as { text?: unknown } | undefined)?.text === 'string'
        ? ': ' + String((ev.data as { text: string }).text).slice(0, 80)
        : ''))
    }
  }

  const connectSse = (id: string) => {
    sseRef.current?.close()
    sseRef.current = null
    if (!id) return
    const connection = openSse(`/api/event?session=${encodeURIComponent(id)}&live=true`, {
      onEvent: handleSseEvent,
      onError: () => {
        if (sessionIdRef.current !== id) return
        setStatus('SSE getrennt – reconnecting...')
        window.setTimeout(() => {
          if (sessionIdRef.current === id) connectSse(id)
        }, 3000)
      },
    })
    sseRef.current = connection
    setStatus('Session ' + id.slice(0, 12) + '…')
  }

  const loadMessages = async (id: string) => {
    try {
      const data = await Sessions.messages(id)
      const list = Array.isArray(data?.data) ? data.data : []
      const mapped: ChatMessage[] = []
      for (const item of list) {
        const message = item as { type?: string; text?: string; tools?: Array<{ name?: string; input?: unknown; result?: unknown }>; time?: string }
        const type = (message.type || '').toLowerCase()
        const role: ChatMessage['role'] = type.includes('user')
          ? 'user'
          : type.includes('assistant')
            ? 'assistant'
            : 'tool'
        mapped.push({ id: uid(), role, text: message.text || '', time: message.time })
        if (Array.isArray(message.tools)) {
          for (const tool of message.tools) {
            let input = ''
            try {
              input = typeof tool.input === 'string' ? tool.input : JSON.stringify(tool.input)
            } catch {
              input = String(tool.input)
            }
            mapped.push({ id: uid(), role: 'tool', text: '🔧 ' + (tool.name || '') + '(' + input + ')', time: message.time })
            mapped.push({ id: uid(), role: 'tool', text: '↳ ' + (tool.result || '(no result)'), time: message.time })
          }
        }
      }
      setMessages(mapped)
    } catch (error) {
      pushEvent('messages: ' + (error instanceof Error ? error.message : String(error)))
    }
  }

  const loadSelectors = async () => {
    try {
      const agentsResponse = await Catalog.agents()
      const workflowAgents = await QocApi.agents.list().catch(() => ({ agents: [] }))
      const byName = new Map<string, AgentOption>()
      const engineAgents = Array.isArray(agentsResponse?.data) ? agentsResponse.data : []
      for (const item of engineAgents) {
        const agent = item as { name?: string; id?: string; hidden?: boolean }
        if (agent.hidden) continue
        if (agent.name && agent.id) byName.set(agent.name.toLowerCase(), { value: agent.id, label: agent.name })
      }
      const workflowList = Array.isArray(workflowAgents?.agents) ? workflowAgents.agents : []
      for (const item of workflowList) {
        const agent = item as { name?: string; id?: string }
        if (!agent.name || !agent.id) continue
        const engineAgent = engineAgents.find((entry) => {
          const candidate = entry as { name?: string }
          return candidate.name?.toLowerCase() === agent.name?.toLowerCase()
        }) as { id?: string } | undefined
        byName.set(agent.name.toLowerCase(), {
          value: engineAgent?.id || ('wf:' + agent.id),
          label: agent.name + ' (wf)',
        })
      }
      const options = Array.from(byName.values())
      setAgents(options)
      if (options.length > 0 && !agent) setAgent(options[0].value)
    } catch (error) {
      pushEvent('selectors: ' + (error instanceof Error ? error.message : String(error)))
    }
  }

  const switchSession = async (id: string) => {
    if (!id || id === sessionId) return
    closeSse()
    sessionIdRef.current = id
    setSessionId(id)
    setMessages([])
    currentAssistantIdRef.current = null
    assistantRawRef.current = ''
    sawStreamedRef.current = false
    localStorage.setItem('qoc.sessionId', id)
    try {
      const session = await Sessions.get(id)
      const sessionAgent = typeof session?.agent === 'string' ? session.agent : ''
      if (sessionAgent && agents.some((option) => option.value === sessionAgent)) {
        setAgent(sessionAgent)
      }
    } catch {
      // The session may still be usable even if metadata loading fails.
    }
    await loadMessages(id)
    connectSse(id)
    setStatus('Session ' + id.slice(0, 12) + '…')
    await loadTasks(id)
  }

  const loadSessions = async () => {
    try {
      const data = await Sessions.list()
      const list = Array.isArray(data?.data) ? data.data as SessionInfo[] : []
      setSessions(list)
      if (!sessionIdRef.current) {
        const stored = localStorage.getItem('qoc.sessionId')
        const next = stored && list.some((item) => item.id === stored) ? stored : list[0]?.id
        if (next) await switchSession(next)
      }
    } catch (error) {
      pushEvent('sessions: ' + (error instanceof Error ? error.message : String(error)))
    }
  }

  const newSession = async () => {
    try {
      const data = await Sessions.create(agent ? { agent } : {})
      const id = typeof data?.id === 'string' ? data.id : ''
      if (!id) return
      const createdAgent = data?.data?.agent
      if (typeof createdAgent === 'string' && agents.some((option) => option.value === createdAgent)) {
        setAgent(createdAgent)
      }
      await loadSessions()
      await switchSession(id)
    } catch (error) {
      pushEvent('new session: ' + (error instanceof Error ? error.message : String(error)))
    }
  }

  const deleteCurrentSession = async () => {
    if (!sessionId) return
    if (!window.confirm('Really delete session?\n\n' + sessionId)) return
    try {
      let response = await Sessions.delete(sessionId).catch((error) => {
        throw error
      })
      void response
      closeSse()
      sessionIdRef.current = null
      setSessionId(null)
      setMessages([])
      await loadSessions()
      const next = sessions.find((item) => item.id !== sessionId)?.id
      if (next) await switchSession(next)
      else setStatus('Keine Session')
    } catch (error) {
      pushEvent('delete: ' + (error instanceof Error ? error.message : String(error)))
    }
  }

  const checkPermissions = async () => {
    if (!sessionIdRef.current || pendingPermission) return
    try {
      const data = await Sessions.permissions(sessionIdRef.current, true)
      const list = Array.isArray(data?.data) ? data.data as PermissionInfo[] : []
      if (list.length > 0) setPendingPermission(list[0])
    } catch {
      // Ignore transient permission polling failures.
    }
  }

  const replyPermission = async (reply: 'ONCE' | 'ALWAYS' | 'REJECT') => {
    if (!pendingPermission) return
    const target = pendingPermission.sessionId || sessionIdRef.current
    if (!target) return
    try {
      await Sessions.replyPermission(target, pendingPermission.id, reply)
    } catch (error) {
      pushEvent('permission: ' + (error instanceof Error ? error.message : String(error)))
    }
    setPendingPermission(null)
  }

  const handleCommand = async (command: string) => {
    if (!sessionId) return
    const parts = command.trim().split(/\s+/)
    const name = parts[0].slice(1)
    addMessage('user', command)
    switch (name) {
      case 'new':
        await newSession()
        break
      case 'interrupt':
        await Sessions.interrupt(sessionId)
        pushEvent('Interrupt gesendet')
        break
      case 'title': {
        try {
          const data = await Sessions.title(sessionId)
          pushEvent('Titel: ' + (data?.title || ''))
        } catch (error) {
          pushEvent('Error: ' + (error instanceof Error ? error.message : String(error)))
        }
        break
      }
      case 'compact':
        await Sessions.compact(sessionId)
        pushEvent('Compact done')
        await loadMessages(sessionId)
        break
      case 'revert':
        await Sessions.revert(sessionId)
        pushEvent('Revert done')
        await loadMessages(sessionId)
        break
      case 'fork': {
        try {
          const tree = await Sessions.tree(sessionId)
          if (!tree?.leaf) throw new Error('Session has no message to fork')
          const data = await Sessions.fork(sessionId, tree.leaf)
          if (data?.id) {
            pushEvent('Fork erstellt: ' + String(data.id).slice(0, 12))
            await loadSessions()
            await switchSession(data.id)
          }
        } catch (error) {
          window.alert('Fork fehlgeschlagen: ' + (error instanceof Error ? error.message : String(error)))
        }
        break
      }
      default:
        setInput(command)
        await send()
    }
  }

  const send = async () => {
    const text = input.trim()
    if (!text || !sessionId) return
    if (text.startsWith('/')) {
      await handleCommand(text)
      setInput('')
      return
    }
    addMessage('user', text)
    setInput('')
    sendingRef.current = true
    setSending(true)
    setStatus('Denke…')
    sawStreamedRef.current = false
    currentAssistantIdRef.current = null
    assistantRawRef.current = ''
    if (permissionTimerRef.current !== null) window.clearInterval(permissionTimerRef.current)
    permissionTimerRef.current = window.setInterval(() => {
      void checkPermissions()
    }, 700)

    try {
      const data = await Sessions.prompt(sessionId, text)
      if (data?.text && !sawStreamedRef.current) addMessage('assistant', data.text)
    } catch (error) {
      addMessage('error', 'Error: ' + (error instanceof Error ? error.message : String(error)))
    } finally {
      if (permissionTimerRef.current !== null) {
        window.clearInterval(permissionTimerRef.current)
        permissionTimerRef.current = null
      }
      sendingRef.current = false
      setSending(false)
      setStatus('ready')
      await loadSessions()
      await loadTasks(sessionId)
      startTaskPoll(sessionId)
    }
  }

  const interrupt = async () => {
    if (!sessionId) return
    if (sending) {
      await Sessions.interrupt(sessionId)
      return
    }
    // Hard cancel is useful even when the UI is not in the sending state.
    await Sessions.interrupt(sessionId)
    await loadTasks(sessionId)
  }

  const replyQuestion = async (question: QuestionInfo) => {
    const selected = questionSelected[question.id] || []
    const text = questionText[question.id] || ''
    try {
      await Questions.reply(question.sessionId, question.id, { selected, text })
      await loadQuestions()
    } catch (error) {
      pushEvent('question: ' + (error instanceof Error ? error.message : String(error)))
    }
  }

  useEffect(() => {
    sessionIdRef.current = sessionId
  }, [sessionId])

  useEffect(() => {
    void (async () => {
      await handleOidcCallbackFromShared()
      await loadSelectors()
      await loadSessions()
      await loadQuestions()
    })()

    questionTimerRef.current = window.setInterval(() => {
      void loadQuestions()
    }, 5000)

    return () => {
      closeSse()
      if (permissionTimerRef.current !== null) window.clearInterval(permissionTimerRef.current)
      if (taskTimerRef.current !== null) window.clearInterval(taskTimerRef.current)
      if (questionTimerRef.current !== null) window.clearInterval(questionTimerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // placeholder; actual callback handling is done in bootstrap below
  async function handleOidcCallbackFromShared() {
    const { handleOidcCallback } = await import('@shared/auth')
    await handleOidcCallback()
  }

  const statusColor = sending ? '#f0ad4e' : '#5cb85c'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#161616', color: '#d4d4d4' }}>
      <header style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '8px 12px', borderBottom: '1px solid #333', flexWrap: 'wrap' }}>
        <strong style={{ color: '#5bc0de' }}>Chat</strong>
        <select value={sessionId ?? ''} onChange={(event) => void switchSession(event.target.value)} style={{ background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, padding: '4px 8px', fontSize: 12 }}>
          {sessions.map((session) => (
            <option key={session.id} value={session.id}>
              {(session.title || 'Session') + ' — ' + session.id.slice(0, 12)}
            </option>
          ))}
        </select>
        <button onClick={() => void newSession()} style={{ padding: '4px 10px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Neue Session</button>
        <button onClick={() => void deleteCurrentSession()} disabled={!sessionId} style={{ padding: '4px 10px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12, opacity: sessionId ? 1 : 0.5 }}>Delete</button>
        <select value={agent} onChange={(event) => setAgent(event.target.value)} style={{ background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, padding: '4px 8px', fontSize: 12, maxWidth: 260 }}>
          {agents.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <label style={{ fontSize: 12, color: '#999' }}>
          <input type="checkbox" checked={showDetails} onChange={(event) => setShowDetails(event.target.checked)} /> Details
        </label>
        <span style={{ fontSize: 12, color: '#999' }}>Status: <span style={{ color: statusColor }}>{status}</span></span>
        <span style={{ fontSize: 12, color: '#999' }}>Fragen: {questions.length}</span>
      </header>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 8, padding: 8, flex: 1, minHeight: 0 }}>
        <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, border: '1px solid #333', borderRadius: 6, background: '#111' }}>
          <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: 12 }}>
            {messages.length === 0 ? <p style={{ color: '#999' }}>Keine Nachrichten.</p> : messages.map((message) => (
              <div key={message.id} className="msg" style={{ marginBottom: 10 }}>
                <div style={{ fontSize: 11, color: '#555', marginBottom: 2 }}>{message.role} {message.time ? '· ' + message.time : ''}</div>
                <div style={{ color: roleClass(message.role), whiteSpace: 'pre-wrap', wordBreak: 'break-word' }} dangerouslySetInnerHTML={{ __html: renderMarkdown(message.text) }} />
              </div>
            ))}
          </div>
          <div style={{ borderTop: '1px solid #333', padding: 8, display: 'flex', gap: 8 }}>
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                  event.preventDefault()
                  void send()
                }
              }}
              placeholder="Prompt eingeben…"
              rows={3}
              style={{ flex: 1, background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 6, padding: 8, resize: 'vertical' }}
            />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <button onClick={() => void send()} disabled={!sessionId || !input.trim()} style={{ padding: '6px 12px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', opacity: !sessionId || !input.trim() ? 0.5 : 1 }}>
                {sending ? 'Interrupt' : 'Send'}
              </button>
              <button onClick={() => void interrupt()} disabled={!sessionId} style={{ padding: '6px 12px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', opacity: sessionId ? 1 : 0.5 }}>
                Cancel
              </button>
            </div>
          </div>
        </div>

        <div style={{ display: 'grid', gap: 8, minHeight: 0, overflow: 'auto' }}>
          <section style={{ border: '1px solid #333', borderRadius: 6, padding: 8, background: '#1e1e1e' }}>
            <h2 style={{ fontSize: 14, color: '#5bc0de', margin: '0 0 6px' }}>Tasks</h2>
            {tasks.length === 0 ? <p style={{ color: '#999', fontSize: 12 }}>No subagent runs.</p> : tasks.map((task) => (
              <div key={task.id} style={{ marginBottom: 6, padding: 6, border: '1px solid #333', borderRadius: 4 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                  <strong>{task.agent || 'subagent'}</strong>
                  <span style={{ color: task.state === 'running' ? '#d29922' : task.state === 'completed' || task.state === 'succeeded' ? '#3fb950' : '#f85149' }}>{task.state}</span>
                </div>
                {task.text ? <div style={{ fontSize: 11, color: '#999', marginTop: 4, whiteSpace: 'pre-wrap' }}>{task.text.slice(0, 300)}</div> : null}
                {task.error ? <div style={{ fontSize: 11, color: '#f85149', marginTop: 4 }}>{task.error}</div> : null}
              </div>
            ))}
          </section>

          <section style={{ border: '1px solid #333', borderRadius: 6, padding: 8, background: '#1e1e1e' }}>
            <h2 style={{ fontSize: 14, color: '#5bc0de', margin: '0 0 6px' }}>Fragen ({questions.length})</h2>
            {questions.length === 0 ? <p style={{ color: '#999', fontSize: 12 }}>Keine offenen Fragen.</p> : questions.map((question) => (
              <div key={question.id} style={{ marginBottom: 8, padding: 6, border: '1px solid #333', borderRadius: 4 }}>
                <div style={{ fontSize: 12, color: '#5bc0de', marginBottom: 4 }}>Session {question.sessionId.slice(0, 12)}…</div>
                {(question.questions || []).map((entry, index) => (
                  <div key={index} style={{ marginBottom: 6 }}>
                    <div style={{ fontSize: 12, marginBottom: 4 }}>{entry.question}</div>
                    {entry.options?.map((option) => (
                      <label key={option.label} style={{ display: 'block', fontSize: 11, margin: '2px 0' }}>
                        <input
                          type={entry.multiple ? 'checkbox' : 'radio'}
                          name={'q_' + question.id + '_' + index}
                          value={option.label}
                          checked={(questionSelected[question.id] || []).includes(option.label)}
                          onChange={(event) => {
                            const current = questionSelected[question.id] || []
                            if (entry.multiple) {
                              setQuestionSelected((prev) => ({
                                ...prev,
                                [question.id]: event.target.checked
                                  ? [...current, option.label]
                                  : current.filter((value) => value !== option.label),
                              }))
                            } else {
                              setQuestionSelected((prev) => ({ ...prev, [question.id]: [option.label] }))
                            }
                          }}
                          style={{ marginRight: 4 }}
                        />
                        {option.label}{option.description ? ' – ' + option.description : ''}
                      </label>
                    ))}
                    <textarea
                      value={questionText[question.id] || ''}
                      onChange={(event) => setQuestionText((prev) => ({ ...prev, [question.id]: event.target.value }))}
                      placeholder="Antwort (optional)…"
                      rows={2}
                      style={{ width: '100%', marginTop: 4, background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 4, padding: 4, fontSize: 11 }}
                    />
                  </div>
                ))}
                <button onClick={() => void replyQuestion(question)} style={{ padding: '4px 10px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Antworten</button>
              </div>
            ))}
          </section>

          <section style={{ border: '1px solid #333', borderRadius: 6, padding: 8, background: '#1e1e1e' }}>
            <h2 style={{ fontSize: 14, color: '#5bc0de', margin: '0 0 6px' }}>Ereignisse</h2>
            {events.length === 0 ? <p style={{ color: '#999', fontSize: 12 }}>Keine Ereignisse.</p> : events.map((event, index) => (
              <div key={index} style={{ fontSize: 11, color: '#999', marginBottom: 2 }}>{event}</div>
            ))}
          </section>
        </div>
      </div>

      {pendingPermission ? (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}>
          <div style={{ background: '#1e1e1e', border: '1px solid #333', borderRadius: 8, padding: 16, width: 420, maxWidth: '90vw' }}>
            <h3 style={{ color: '#5bc0de', marginTop: 0 }}>Permission required</h3>
            <p style={{ color: '#d4d4d4', fontSize: 13 }}>{pendingPermission.action}</p>
            <div style={{ fontSize: 11, color: '#999', whiteSpace: 'pre-wrap', wordBreak: 'break-all', marginBottom: 8 }}>
              {(pendingPermission.resources || []).join('\n')}
            </div>
            {pendingPermission.message ? <p style={{ fontSize: 11, color: '#555' }}>{pendingPermission.message}</p> : null}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => void replyPermission('REJECT')} style={{ padding: '6px 12px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Ablehnen</button>
              <button onClick={() => void replyPermission('ONCE')} style={{ padding: '6px 12px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Einmal</button>
              <button onClick={() => void replyPermission('ALWAYS')} style={{ padding: '6px 12px', background: '#27ae60', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Immer</button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}