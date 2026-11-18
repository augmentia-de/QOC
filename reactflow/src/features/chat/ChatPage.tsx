import { useEffect, useRef, useState } from 'react';
import './ChatPage.css';
import { Catalog, QocApi, Questions, Sessions, Files } from '../../services/qocApi';
import { openSse } from '@shared/sse'
import type { SseConnection } from '@shared/sse'
import { renderMarkdown } from '@shared/markdown'
import { useIsMobile } from '../../hooks/useMediaQuery'
import { usePanelLayout, type PanelId } from './usePanelLayout'
import { Splitter } from './Splitter'
import { CollapsiblePanel } from './CollapsiblePanel'
import { FileTreePanel } from './FileTreePanel'
import { FileViewerPanel } from './FileViewerPanel'
import { LlmTracePanel } from './LlmTracePanel'

interface SessionInfo { id: string; title?: string; agent?: string; directory?: string }
interface ChatMessage { id: string; role: 'user' | 'assistant' | 'tool' | 'error'; text: string; time?: string }
interface AgentOption { value: string; label: string; category?: string }
interface TaskInfo { id: string; agent?: string; state?: string; text?: string; error?: string; childSession?: string; tokens?: number }
interface PermissionInfo { id: string; sessionId: string; action: string; resources: string[]; message?: string }
interface QuestionOption { label: string; description?: string }
interface QuestionEntry { question: string; options?: QuestionOption[]; multiple?: boolean }
interface QuestionInfo { id: string; sessionId: string; questions?: QuestionEntry[] }

function uid(): string { return crypto.randomUUID() }
function roleClass(role: ChatMessage['role']): string {
  if (role === 'user') return '#337ab7'
  if (role === 'assistant') return '#5bc0de'
  if (role === 'error') return '#d9534f'
  return '#999'
}

export default function ChatPage() {
  const isMobile = useIsMobile();
  const layout = usePanelLayout();

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

  // new parity state
  const [currentDir, setCurrentDir] = useState<string>('.')
  const [currentFile, setCurrentFile] = useState<string | null>(null)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [inputExpanded, setInputExpanded] = useState(false)
  const [lastTaskExpanded, setLastTaskExpanded] = useState(false)
  const [lastTask, setLastTask] = useState('')
  const [lastTaskH, setLastTaskH] = useState(70)
  const [inputH, setInputH] = useState(90)
  const sseRef = useRef<SseConnection | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const currentAssistantIdRef = useRef<string | null>(null)
  const assistantRawRef = useRef('')
  const sawStreamedRef = useRef(false)
  const sendingRef = useRef(false)
  const permissionTimerRef = useRef<number | null>(null)
  const taskTimerRef = useRef<number | null>(null)
  const questionTimerRef = useRef<number | null>(null)
  const sideRef = useRef<HTMLElement | null>(null)

  const pushEvent = (text: string) => setEvents((prev) => [text, ...prev].slice(0, 10))
  const addMessage = (role: ChatMessage['role'], text: string, time?: string): string => {
    const id = uid(); setMessages((prev) => [...prev, { id, role, text, time }]); return id
  }
  const updateMessage = (id: string, text: string) => setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, text } : m)))

  const closeSse = () => { sseRef.current?.close(); sseRef.current = null }
  const loadTasks = async (id: string): Promise<boolean> => {
    try {
      const data = await Sessions.tasks(id); const list = Array.isArray(data?.data) ? data.data as TaskInfo[] : []
      setTasks(list); return list.some((t) => t.state === 'running')
    } catch (e) { pushEvent('tasks: ' + (e instanceof Error ? e.message : String(e))); return false }
  }
  const startTaskPoll = (id: string) => {
    if (taskTimerRef.current !== null) { window.clearInterval(taskTimerRef.current); taskTimerRef.current = null }
    void loadTasks(id).then((running) => {
      if (!running) return
      taskTimerRef.current = window.setInterval(() => {
        if (sessionIdRef.current !== id) { if (taskTimerRef.current !== null) { window.clearInterval(taskTimerRef.current); taskTimerRef.current = null } return }
        void loadTasks(id).then((still) => { if (!still && taskTimerRef.current !== null) { window.clearInterval(taskTimerRef.current); taskTimerRef.current = null } })
      }, 5000)
    })
  }
  const loadQuestions = async () => {
    try { const data = await Questions.list(); setQuestions(Array.isArray(data?.data) ? data.data as QuestionInfo[] : []) }
    catch { /* keep */ }
  }

  const handleSseEvent = (event: unknown) => {
    const ev = event as Record<string, unknown>
    if (ev && typeof ev._type === 'string') {
      switch (ev._type) {
        case 'TextStarted': { sawStreamedRef.current = true; const id = addMessage('assistant', '', typeof ev.time === 'string' ? ev.time : undefined); currentAssistantIdRef.current = id; assistantRawRef.current = ''; break }
        case 'TextDelta': { sawStreamedRef.current = true; if (!currentAssistantIdRef.current) { const id = addMessage('assistant', '', typeof ev.time === 'string' ? ev.time : undefined); currentAssistantIdRef.current = id } assistantRawRef.current += typeof ev.text === 'string' ? ev.text : ''; const id = currentAssistantIdRef.current; if (id) updateMessage(id, assistantRawRef.current); break }
        case 'TextEnded': { if (typeof ev.text === 'string' && !assistantRawRef.current) assistantRawRef.current = ev.text; const id = currentAssistantIdRef.current; if (id) updateMessage(id, assistantRawRef.current || (typeof ev.text === 'string' ? ev.text : '')); currentAssistantIdRef.current = null; assistantRawRef.current = ''; break }
        case 'ToolCallRequested': { if (!showDetails) break; sawStreamedRef.current = true; const name = typeof ev.name === 'object' && ev.name !== null && 'value' in ev.name ? String((ev.name as { value?: unknown }).value) : String(ev.name ?? ''); let input = ''; try { input = typeof ev.input === 'string' ? ev.input : JSON.stringify(ev.input) } catch { input = String(ev.input) } if (input.length > 1500) input = input.slice(0, 1500) + '…'; addMessage('tool', '🔧 ' + name + '(' + input + ')'); break }
        case 'ToolResultProvided': { if (!showDetails) break; sawStreamedRef.current = true; const outcome = ev.outcome as { text?: unknown; value?: unknown } | undefined; const result = outcome?.text ?? (outcome?.value ? JSON.stringify(outcome.value) : ''); const trimmed = String(result ?? '').slice(0, 4000); addMessage('tool', '↳ ' + (trimmed || '(no result)')); break }
        case 'ToolFailed': addMessage('error', '⚠ ' + String(ev.message ?? 'Tool failed')); break
        case 'ProviderFailed': addMessage('error', 'Provider error: ' + String(ev.message ?? '')); break
        case 'RetryScheduled': addMessage('tool', '⏳ Retry in ' + String(ev.delay ?? '') + 's (Versuch ' + String(ev.attempt ?? '') + ')'); break
        case 'SubagentStarted': case 'SubagentFinished': case 'SubagentCancelled': pushEvent(String(ev._type) + (typeof ev.agent === 'string' ? ': ' + ev.agent : '')); void loadTasks(sessionIdRef.current ?? ''); break
        case 'LlmRequest': case 'LlmResponse': if (showLlm) pushEvent(String(ev._type)); break
        default: pushEvent(String(ev._type))
      }
      return
    }
    if (typeof ev.type === 'string') {
      if (ev.type === 'tool_result') { const trimmed = String(ev.result ?? '').slice(0, 4000); addMessage('tool', '↳ ' + (trimmed || '(no result)')); return }
      pushEvent(ev.type + (typeof (ev.data as { text?: unknown } | undefined)?.text === 'string' ? ': ' + String((ev.data as { text: string }).text).slice(0, 80) : ''))
    }
  }

  const connectSse = (id: string) => {
    sseRef.current?.close(); sseRef.current = null; if (!id) return
    const connection = openSse(`/api/event?session=${encodeURIComponent(id)}&live=true`, {
      onEvent: handleSseEvent,
      onError: () => { if (sessionIdRef.current !== id) return; setStatus('SSE getrennt – reconnecting...'); window.setTimeout(() => { if (sessionIdRef.current === id) connectSse(id) }, 3000) },
    })
    sseRef.current = connection; setStatus('Session ' + id.slice(0, 12) + '…')
  }

  const loadMessages = async (id: string) => {
    try {
      const data = await Sessions.messages(id); const list = Array.isArray(data?.data) ? data.data : []
      const mapped: ChatMessage[] = []
      for (const item of list) {
        const message = item as { type?: string; text?: string; tools?: Array<{ name?: string; input?: unknown; result?: unknown }>; time?: string }
        const type = (message.type || '').toLowerCase()
        const role: ChatMessage['role'] = type.includes('user') ? 'user' : type.includes('assistant') ? 'assistant' : 'tool'
        mapped.push({ id: uid(), role, text: message.text || '', time: message.time })
        if (Array.isArray(message.tools)) for (const tool of message.tools) {
          let input = ''; try { input = typeof tool.input === 'string' ? tool.input : JSON.stringify(tool.input) } catch { input = String(tool.input) }
          mapped.push({ id: uid(), role: 'tool', text: '🔧 ' + (tool.name || '') + '(' + input + ')', time: message.time })
          mapped.push({ id: uid(), role: 'tool', text: '↳ ' + (tool.result || '(no result)'), time: message.time })
        }
      }
      setMessages(mapped)
    } catch (e) { pushEvent('messages: ' + (e instanceof Error ? e.message : String(e))) }
  }

  const loadSelectors = async () => {
    try {
      const agentsResponse = await Catalog.agents()
      const workflowAgents = await QocApi.agents.list().catch(() => ({ agents: [] }))
      const byName = new Map<string, AgentOption>()
      const engineAgents = Array.isArray(agentsResponse?.data) ? agentsResponse.data : []
      for (const item of engineAgents) {
        const agent = item as { name?: string; id?: string; hidden?: boolean; category?: string }
        if (agent.hidden) continue
        if (agent.name && agent.id) byName.set(agent.name.toLowerCase(), { value: agent.id, label: agent.name, category: agent.category || '' })
      }
      const workflowList = Array.isArray(workflowAgents?.agents) ? workflowAgents.agents : []
      for (const item of workflowList) {
        const agent = item as { name?: string; id?: string; category?: string }
        if (!agent.name || !agent.id) continue
        const engineAgent = engineAgents.find((entry) => (entry as { name?: string }).name?.toLowerCase() === agent.name?.toLowerCase()) as { id?: string } | undefined
        byName.set(agent.name.toLowerCase(), { value: engineAgent?.id || ('wf:' + agent.id), label: agent.name + ' (wf)', category: agent.category || '' })
      }
      const options = Array.from(byName.values())
      setAgents(options); if (options.length > 0 && !agent) setAgent(options[0].value)
    } catch (e) { pushEvent('selectors: ' + (e instanceof Error ? e.message : String(e))) }
  }

  const switchSession = async (id: string) => {
    if (!id || id === sessionId) return
    closeSse(); sessionIdRef.current = id; setSessionId(id); setMessages([])
    currentAssistantIdRef.current = null; assistantRawRef.current = ''; sawStreamedRef.current = false
    localStorage.setItem('qoc.sessionId', id)
    try {
      const session = await Sessions.get(id)
      const sessionAgent = typeof session?.agent === 'string' ? session.agent : ''
      if (sessionAgent && agents.some((o) => o.value === sessionAgent)) setAgent(sessionAgent)
    } catch { /* ignore */ }
    await loadMessages(id); connectSse(id); setStatus('Session ' + id.slice(0, 12) + '…'); await loadTasks(id)
  }

  const loadSessions = async () => {
    try {
      const data = await Sessions.list(); const list = Array.isArray(data?.data) ? data.data as SessionInfo[] : []
      setSessions(list)
      if (!sessionIdRef.current) {
        const stored = localStorage.getItem('qoc.sessionId')
        const next = stored && list.some((i) => i.id === stored) ? stored : list[0]?.id
        if (next) await switchSession(next)
      }
    } catch (e) { pushEvent('sessions: ' + (e instanceof Error ? e.message : String(e))) }
  }

  const newSession = async () => {
    try {
      const data = await Sessions.create(agent ? { agent } : {})
      const id = typeof data?.id === 'string' ? data.id : ''
      if (!id) return
      const createdAgent = data?.data?.agent
      if (typeof createdAgent === 'string' && agents.some((o) => o.value === createdAgent)) setAgent(createdAgent)
      await loadSessions(); await switchSession(id)
    } catch (e) { pushEvent('new session: ' + (e instanceof Error ? e.message : String(e))) }
  }

  const deleteCurrentSession = async () => {
    if (!sessionId) return
    if (!window.confirm('Really delete session?\n\n' + sessionId)) return
    try {
      await Sessions.delete(sessionId)
      closeSse(); sessionIdRef.current = null; setSessionId(null); setMessages([])
      await loadSessions()
      const next = sessions.find((i) => i.id !== sessionId)?.id
      if (next) await switchSession(next)
      else setStatus('Keine Session')
    } catch (e) { pushEvent('delete: ' + (e instanceof Error ? e.message : String(e))) }
  }

  const checkPermissions = async () => {
    if (!sessionIdRef.current || pendingPermission) return
    try {
      const data = await Sessions.permissions(sessionIdRef.current, true)
      const list = Array.isArray(data?.data) ? data.data as PermissionInfo[] : []
      if (list.length > 0) setPendingPermission(list[0])
    } catch { /* ignore */ }
  }
  const replyPermission = async (reply: 'ONCE' | 'ALWAYS' | 'REJECT') => {
    if (!pendingPermission) return
    const target = pendingPermission.sessionId || sessionIdRef.current
    if (!target) return
    try { await Sessions.replyPermission(target, pendingPermission.id, reply) } catch (e) { pushEvent('permission: ' + (e instanceof Error ? e.message : String(e))) }
    setPendingPermission(null)
  }

  const handleCommand = async (command: string) => {
    if (!sessionId) return
    const parts = command.trim().split(/\s+/); const name = parts[0].slice(1)
    addMessage('user', command)
    switch (name) {
      case 'new': await newSession(); break
      case 'interrupt': await Sessions.interrupt(sessionId); pushEvent('Interrupt gesendet'); break
      case 'title': { try { const data = await Sessions.title(sessionId); pushEvent('Titel: ' + (data?.title || '')) } catch (e) { pushEvent('Error: ' + (e instanceof Error ? e.message : String(e))) } break }
      case 'compact': await Sessions.compact(sessionId); pushEvent('Compact done'); await loadMessages(sessionId); break
      case 'revert': await Sessions.revert(sessionId); pushEvent('Revert done'); await loadMessages(sessionId); break
      case 'fork': { try { const tree = await Sessions.tree(sessionId); if (!tree?.leaf) throw new Error('Session has no message to fork'); const data = await Sessions.fork(sessionId, tree.leaf); if (data?.id) { pushEvent('Fork erstellt: ' + String(data.id).slice(0, 12)); await loadSessions(); await switchSession(data.id) } } catch (e) { window.alert('Fork fehlgeschlagen: ' + (e instanceof Error ? e.message : String(e))) } break }
      default: setInput(command); await send()
    }
  }

  const send = async () => {
    const text = input.trim(); if (!text || !sessionId) return
    if (text.startsWith('/')) { await handleCommand(text); setInput(''); return }
    addMessage('user', text); setInput('')
    sendingRef.current = true; setSending(true); setStatus('Denke…')
    sawStreamedRef.current = false; currentAssistantIdRef.current = null; assistantRawRef.current = ''
    if (permissionTimerRef.current !== null) window.clearInterval(permissionTimerRef.current)
    permissionTimerRef.current = window.setInterval(() => { void checkPermissions() }, 700)
    try {
      const data = await Sessions.prompt(sessionId, text)
      if (data?.text && !sawStreamedRef.current) addMessage('assistant', data.text)
    } catch (e) { addMessage('error', 'Error: ' + (e instanceof Error ? e.message : String(e))) }
    finally {
      if (permissionTimerRef.current !== null) { window.clearInterval(permissionTimerRef.current); permissionTimerRef.current = null }
      sendingRef.current = false; setSending(false); setStatus('ready')
      await loadSessions(); await loadTasks(sessionId); startTaskPoll(sessionId)
      setLastTask(text)
    }
  }

  const interrupt = async () => {
    if (!sessionId) return
    await Sessions.interrupt(sessionId)
    await loadTasks(sessionId)
  }

  const replyQuestion = async (q: QuestionInfo) => {
    const selected = questionSelected[q.id] || []; const text = questionText[q.id] || ''
    try { await Questions.reply(q.sessionId, q.id, { selected, text }); await loadQuestions() }
    catch (e) { pushEvent('question: ' + (e instanceof Error ? e.message : String(e))) }
  }

  useEffect(() => { sessionIdRef.current = sessionId }, [sessionId])
  useEffect(() => {
    void (async () => {
      await (async () => { const { handleOidcCallback } = await import('@shared/auth'); await handleOidcCallback() })()
      await loadSelectors(); await loadSessions(); await loadQuestions()
    })()
    questionTimerRef.current = window.setInterval(() => { void loadQuestions() }, 5000)
    return () => {
      closeSse()
      if (permissionTimerRef.current !== null) window.clearInterval(permissionTimerRef.current)
      if (taskTimerRef.current !== null) window.clearInterval(taskTimerRef.current)
      if (questionTimerRef.current !== null) window.clearInterval(questionTimerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // mobile edge swipe
  useEffect(() => {
    if (!isMobile) return
    let startX = 0
    const ts = (e: TouchEvent) => { startX = e.touches[0].clientX }
    const tm = (e: TouchEvent) => {
      const x = e.touches[0].clientX
      if (startX >= window.innerWidth - 24 && startX - x > 40) { setMobileOpen(true); startX = 0 }
    }
    document.addEventListener('touchstart', ts, { passive: true })
    document.addEventListener('touchmove', tm, { passive: true })
    return () => { document.removeEventListener('touchstart', ts); document.removeEventListener('touchmove', tm) }
  }, [isMobile])

  const statusColor = sending ? '#f0ad4e' : '#5cb85c'

  const panelIds: PanelId[] = showLlm
    ? ['files', 'viewer', 'tasks', 'questions', 'events', 'llm']
    : ['files', 'viewer', 'tasks', 'questions', 'events']

  const panelCtx = {
    tasks, questions, events,
    currentDir, setCurrentDir, currentFile, setCurrentFile,
    showLlm, lastTask, setLastTask,
    collapsed: layout.collapsed,
    togglePanel: (id: string) => layout.togglePanel(id as PanelId),
    heights: layout.heights,
    isMobile,
    questionText, setQuestionText, questionSelected, setQuestionSelected,
    replyQuestion,
  }

  return (
    <div className="chat-root">
      <header style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '8px 12px', borderBottom: '1px solid #333', flexWrap: 'wrap' }}>
        <strong style={{ color: '#5bc0de' }}>Chat</strong>
        <select value={sessionId ?? ''} onChange={(e) => void switchSession(e.target.value)} style={selStyle}>
          {sessions.map((s) => (<option key={s.id} value={s.id}>{(s.title || 'Session') + ' — ' + s.id.slice(0, 12)}</option>))}
        </select>
        <select value="" onChange={(e) => { const v = e.target.value; e.target.value = ''; if (v === 'new') void newSession(); else if (v === 'delete') void deleteCurrentSession() }} style={selStyle}>
          <option value="" disabled>Session-Aktionen…</option>
          <option value="new">+ Neue Session</option>
          <option value="delete" disabled={!sessionId}>Session löschen</option>
        </select>
        <select value={agent} onChange={(e) => setAgent(e.target.value)} style={{ ...selStyle, maxWidth: 260 }}>
          {(() => {
            const groups = new Map<string, AgentOption[]>()
            for (const o of agents) { const cat = (o.category || '').trim() || 'Ohne Kategorie'; if (!groups.has(cat)) groups.set(cat, []); groups.get(cat)!.push(o) }
            return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([cat, opts]) => (
              <optgroup key={cat} label={cat}>{opts.map((o) => (<option key={o.value} value={o.value}>{o.label}</option>))}</optgroup>
            ))
          })()}
        </select>
        <label style={{ fontSize: 12, color: '#999' }}><input type="checkbox" checked={showDetails} onChange={(e) => setShowDetails(e.target.checked)} /> Details</label>
        <label style={{ fontSize: 12, color: '#999' }}><input type="checkbox" checked={showLlm} onChange={(e) => { setShowLlm(e.target.checked); if (e.target.checked) layout.togglePanel('llm') }} /> LLM</label>
        <span style={{ fontSize: 12, color: '#999' }}>Status: <span style={{ color: statusColor }}>{status}</span></span>
        <span style={{ fontSize: 12, color: '#999' }}>Fragen: {questions.length}</span>
      </header>

      <div className="chat-main">
        {/* chat panel */}
        <div className="chat-panel">
          <div className={'chat-lasttask' + (lastTaskExpanded ? ' expanded' : '')} style={lastTaskExpanded ? undefined : { height: lastTaskH, flex: '0 0 auto' }}>
            <textarea readOnly value={lastTask} placeholder="Letzte Aufgabe…" rows={2} />
            <button className="chat-ta-toggle" onClick={() => setLastTaskExpanded((v) => !v)} title="Expand">⤢</button>
          </div>
          <Splitter orientation="horizontal" onDrag={(d) => setLastTaskH((h) => Math.max(40, h + d))} />
          <div className="chat-messages">
            {messages.length === 0 ? <p style={{ color: '#999' }}>Keine Nachrichten.</p> : messages.map((m) => (
              <div key={m.id} className={'chat-msg ' + m.role} style={{ alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start' }}>
                <div className="meta">{m.role} {m.time ? '· ' + m.time : ''}</div>
                <div style={{ color: roleClass(m.role), whiteSpace: 'pre-wrap', wordBreak: 'break-word' }} dangerouslySetInnerHTML={{ __html: renderMarkdown(m.text) }} />
              </div>
            ))}
          </div>
          <Splitter orientation="horizontal" onDrag={(d) => setInputH((h) => Math.max(40, h - d))} />
          <div className={'chat-inputbar' + (inputExpanded ? ' expanded' : '')} style={inputExpanded ? undefined : { height: inputH, flex: '0 0 auto' }}>
            <button className="chat-ta-toggle" onClick={() => setInputExpanded((v) => !v)} title="Expand">⤢</button>
            <textarea
              value={input} onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); void send() } }}
              placeholder="Prompt eingeben…" rows={2}
            />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <button onClick={() => void send()} disabled={!sessionId || !input.trim()} style={btnPrimary(sending ? 'Interrupt' : 'Send', !sessionId || !input.trim())}>{sending ? 'Interrupt' : 'Send'}</button>
              <button onClick={() => void interrupt()} disabled={!sessionId} style={btnDanger('Cancel', !sessionId)}>Cancel</button>
            </div>
          </div>
        </div>

        {/* splitter */}
        {!isMobile ? <Splitter orientation="vertical" onDrag={layout.resizeSide} disabled={false} /> : null}

        {/* side */}
        <aside ref={(el) => { sideRef.current = el }} className={'chat-side' + (isMobile && mobileOpen ? ' open' : '')} style={{ width: isMobile ? undefined : layout.sideWidth, flex: isMobile ? undefined : '0 0 auto' }}>
          {isMobile ? <button className="chat-close-side" onClick={() => setMobileOpen(false)} title="Schließen">×</button> : null}
          {panelIds.map((pid, idx) => {
            const isLast = idx === panelIds.length - 1
            return (
              <div key={pid} style={{ display: 'contents' }}>
                {renderPanel(pid, panelCtx)}
                {!isLast && !isMobile ? (
                  <Splitter orientation="horizontal" onDrag={(d) => layout.resizePanels(idx, panelIds, d)} disabled={layout.collapsed[panelIds[idx]] || layout.collapsed[panelIds[idx + 1]]} />
                ) : null}
              </div>
            )
          })}
        </aside>
      </div>

      {isMobile ? (
        <>
          <button className="chat-toggle-side" onClick={() => setMobileOpen((v) => !v)} title="Dateien anzeigen" aria-label="Dateien anzeigen">‹</button>
          <div className={'chat-backdrop' + (mobileOpen ? ' open' : '')} onClick={() => setMobileOpen(false)} />
        </>
      ) : null}

      {pendingPermission ? (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}>
          <div style={{ background: '#1e1e1e', border: '1px solid #333', borderRadius: 8, padding: 16, width: 420, maxWidth: '90vw' }}>
            <h3 style={{ color: '#5bc0de', marginTop: 0 }}>Permission required</h3>
            <p style={{ color: '#d4d4d4', fontSize: 13 }}>{pendingPermission.action}</p>
            <div style={{ fontSize: 11, color: '#999', whiteSpace: 'pre-wrap', wordBreak: 'break-all', marginBottom: 8 }}>{(pendingPermission.resources || []).join('\n')}</div>
            {pendingPermission.message ? <p style={{ fontSize: 11, color: '#555' }}>{pendingPermission.message}</p> : null}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => void replyPermission('REJECT')} style={btnDanger('Ablehnen', false)}>Ablehnen</button>
              <button onClick={() => void replyPermission('ONCE')} style={btnStyle}>Einmal</button>
              <button onClick={() => void replyPermission('ALWAYS')} style={btnPrimary('Immer', false)}>Immer</button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

// helper to render a panel by id
function renderPanel(id: PanelId, ctx: any) {
  const { tasks, questions, events, currentDir, setCurrentDir, currentFile, setCurrentFile, showLlm } = ctx
  switch (id) {
    case 'files':
      return (
        <CollapsiblePanel id={id} title={'Arbeitsverzeichnis: ' + (currentDir === '.' ? '.' : currentDir)} collapsed={ctx.collapsed?.[id] ?? false} onToggle={ctx.togglePanel} height={ctx.isMobile ? undefined : (ctx.heights?.[id] ?? 180)}>
          <FileTreePanel path={currentDir} onPathChange={setCurrentDir} onOpenFile={setCurrentFile} selected={currentFile ?? ''} />
        </CollapsiblePanel>
      )
    case 'viewer':
      return (
        <CollapsiblePanel id={id} title="Datei" collapsed={ctx.collapsed?.[id] ?? false} onToggle={ctx.togglePanel} actions={null} height={ctx.isMobile ? undefined : (ctx.heights?.[id] ?? 160)}>
          <FileViewerPanel filePath={currentFile} />
        </CollapsiblePanel>
      )
    case 'tasks':
      return (
        <CollapsiblePanel id={id} title="Subagents" collapsed={ctx.collapsed?.[id] ?? false} onToggle={ctx.togglePanel} height={ctx.isMobile ? undefined : (ctx.heights?.[id] ?? 140)}>
          <div style={{ fontSize: 12 }}>
            {tasks.length === 0 ? <p style={{ color: '#999' }}>No subagent runs.</p> : tasks.map((t: TaskInfo) => (
              <div key={t.id} style={{ marginBottom: 6, padding: 6, border: '1px solid #333', borderRadius: 4 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <strong>{t.agent || 'subagent'}</strong>
                  <span style={{ color: t.state === 'running' ? '#d29922' : t.state === 'completed' || t.state === 'succeeded' ? '#3fb950' : '#f85149' }}>{t.state}</span>
                </div>
                {t.text ? <div style={{ fontSize: 11, color: '#999', marginTop: 4, whiteSpace: 'pre-wrap' }}>{t.text.slice(0, 300)}</div> : null}
                {t.error ? <div style={{ fontSize: 11, color: '#f85149', marginTop: 4 }}>{t.error}</div> : null}
              </div>
            ))}
          </div>
        </CollapsiblePanel>
      )
    case 'questions':
      return (
        <CollapsiblePanel id={id} title={'Fragen (' + (ctx.questions?.length ?? 0) + ')'} collapsed={ctx.collapsed?.[id] ?? false} onToggle={ctx.togglePanel} height={ctx.isMobile ? undefined : (ctx.heights?.[id] ?? 140)}>
          <div style={{ fontSize: 12 }}>
            {(ctx.questions ?? []).length === 0 ? <p style={{ color: '#999' }}>Keine offenen Fragen.</p> : (ctx.questions ?? []).map((q: QuestionInfo) => (
              <div key={q.id} style={{ marginBottom: 8, padding: 6, border: '1px solid #333', borderRadius: 4 }}>
                <div style={{ color: '#5bc0de', marginBottom: 4, fontSize: 11 }}>Session {q.sessionId.slice(0, 12)}…</div>
                {(q.questions || []).map((entry, index) => (
                  <div key={index} style={{ marginBottom: 6 }}>
                    <div style={{ marginBottom: 4 }}>{entry.question}</div>
                    {entry.options?.map((opt) => (
                      <label key={opt.label} style={{ display: 'block', fontSize: 11, margin: '2px 0' }}>
                        <input type={entry.multiple ? 'checkbox' : 'radio'} name={'q_' + q.id + '_' + index} value={opt.label}
                          checked={(ctx.questionSelected?.[q.id] || []).includes(opt.label)}
                          onChange={(e) => {
                            const cur = ctx.questionSelected?.[q.id] || []
                            if (entry.multiple) ctx.setQuestionSelected((prev: any) => ({ ...prev, [q.id]: e.target.checked ? [...cur, opt.label] : cur.filter((v: string) => v !== opt.label) }))
                            else ctx.setQuestionSelected((prev: any) => ({ ...prev, [q.id]: [opt.label] }))
                          }} style={{ marginRight: 4 }} />
                        {opt.label}{opt.description ? ' – ' + opt.description : ''}
                      </label>
                    ))}
                    <textarea value={ctx.questionText?.[q.id] || ''} onChange={(e) => ctx.setQuestionText((prev: any) => ({ ...prev, [q.id]: e.target.value }))} placeholder="Antwort (optional)…" rows={2}
                      style={{ width: '100%', marginTop: 4, background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 4, padding: 4, fontSize: 11 }} />
                  </div>
                ))}
                <button onClick={() => void ctx.replyQuestion(q)} style={btnStyle}>Antworten</button>
              </div>
            ))}
          </div>
        </CollapsiblePanel>
      )
    case 'events':
      return (
        <CollapsiblePanel id={id} title="Ereignisse" collapsed={ctx.collapsed?.[id] ?? false} onToggle={ctx.togglePanel} height={ctx.isMobile ? undefined : (ctx.heights?.[id] ?? 140)}>
          <div className="chat-logs">
            {events.length === 0 ? <p style={{ color: '#999' }}>Keine Ereignisse.</p> : events.map((ev: string, i: number) => (
              <div key={i} style={{ marginBottom: 2 }}>{ev}</div>
            ))}
          </div>
        </CollapsiblePanel>
      )
    case 'llm':
      return showLlm ? (
        <CollapsiblePanel id={id} title="LLM-Trace" collapsed={ctx.collapsed?.[id] ?? false} onToggle={ctx.togglePanel} height={ctx.isMobile ? undefined : (ctx.heights?.[id] ?? 140)}>
          <LlmTracePanel active={!ctx.collapsed?.[id]} />
        </CollapsiblePanel>
      ) : null
    default:
      return null
  }
}

// Context passed to renderPanel — keep in sync
// We'll instead call renderPanel with proper context by wrapping. To avoid
// prop drilling complexity, the panels above reference ctx fields; ensure
// ChatPage passes them. The actual call site builds ctx:
// (see inline in JSX below)

// --- small style helpers ---
const selStyle: React.CSSProperties = { background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, padding: '4px 8px', fontSize: 12 }
const btnStyle: React.CSSProperties = { background: '#0b0d12', border: '1px solid #333', color: '#d4d4d4', cursor: 'pointer', borderRadius: 4, padding: '4px 8px', fontSize: 12 }

function btnPrimary(label: string, disabled: boolean): React.CSSProperties { return { padding: '6px 12px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', opacity: disabled ? 0.5 : 1 } }
function btnDanger(label: string, disabled: boolean): React.CSSProperties { return { padding: '6px 12px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', opacity: disabled ? 0.5 : 1 } }

// (vertical resize handled via Splitter + lastTaskH/inputH state above)