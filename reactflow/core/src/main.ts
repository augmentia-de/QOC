import '../core.css'
import { Catalog, QocApi, Questions, Sessions } from '@shared/api'
import { handleOidcCallback, isOidcEnabled, updateAuthStatus, loginOrLogout, apiFetch } from '@shared/auth'
import { openSse } from '@shared/sse'
import { renderMarkdown } from '@shared/markdown'

interface SessionInfo {
  id: string
  title?: string
  agent?: string
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

interface QuestionInfo {
  id: string
  sessionId: string
  questions?: Array<{ question: string; options?: Array<{ label: string; description?: string }>; multiple?: boolean }>
}

function el<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id)
  if (!element) throw new Error('Missing element: ' + id)
  return element as T
}

const messages = el<HTMLDivElement>('messages')
const input = el<HTMLTextAreaElement>('input')
const sendBtn = el<HTMLButtonElement>('send')
const status = el<HTMLSpanElement>('status')
const sessionsSelect = el<HTMLSelectElement>('sessions')
const agentSelect = el<HTMLSelectElement>('agent')
const tasksContainer = el<HTMLDivElement>('tasks')
const questionsList = el<HTMLDivElement>('questionsList')
const questionsPanel = el<HTMLElement>('panelQuestions')
const questionsBadge = el<HTMLSpanElement>('questionsBadge')
const permModal = el<HTMLDivElement>('permModal')
const permContext = el<HTMLParagraphElement>('permContext')
const questionModal = el<HTMLDivElement>('questionModal')
const questionText = el<HTMLDivElement>('questionText')
const questionOptions = el<HTMLDivElement>('questionOptions')
const questionAnswer = el<HTMLTextAreaElement>('questionAnswer')

let sessionId: string | null = null
let sessions: SessionInfo[] = []
let currentAssistant: HTMLDivElement | null = null
let currentAssistantRaw = ''
let sawStreamed = false
let sending = false
let pendingPermission: PermissionInfo | null = null
let tasks: TaskInfo[] = []
let questions: QuestionInfo[] = []
let sseConnection: { close: () => void } | null = null
let permissionTimer: number | null = null
let taskTimer: number | null = null
let questionTimer: number | null = null
let taskPollActive = false
let currentAgentName = ''

function logEvent(text: string) {
  const logs = document.getElementById('logs')
  if (!logs) return
  const div = document.createElement('div')
  div.textContent = text
  logs.appendChild(div)
  while (logs.childElementCount > 10) logs.removeChild(logs.firstChild as Node)
  logs.scrollTop = logs.scrollHeight
}

function addMessage(role: string, text: string, time?: string) {
  const div = document.createElement('div')
  div.className = 'msg ' + role
  const timestamp = document.createElement('span')
  timestamp.className = 'ts'
  const date = time ? new Date(time) : new Date()
  timestamp.textContent = date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  div.appendChild(timestamp)
  const body = document.createElement('div')
  body.className = 'msg-body'
  body.innerHTML = role === 'assistant' ? renderMarkdown(text) : text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  div.appendChild(body)
  messages.appendChild(div)
  messages.scrollTop = messages.scrollHeight
  return body
}

function updateAssistant(text: string) {
  if (!currentAssistant) return
  currentAssistant.innerHTML = renderMarkdown(text)
  messages.scrollTop = messages.scrollHeight
}

function showPermission(permission: PermissionInfo) {
  pendingPermission = permission
  permContext.textContent = permission.action + ' — ' + (permission.resources || []).join(', ')
  permModal.classList.add('open')
}

function closePermission() {
  permModal.classList.remove('open')
  pendingPermission = null
}

async function replyPermission(reply: 'ONCE' | 'ALWAYS' | 'REJECT') {
  if (!pendingPermission) return
  const target = pendingPermission.sessionId || sessionId
  if (!target) return
  try {
    await Sessions.replyPermission(target, pendingPermission.id, reply)
  } catch (error) {
    logEvent('Permission reply error: ' + (error instanceof Error ? error.message : String(error)))
  }
  closePermission()
}

async function checkPermissions() {
  if (!sessionId || pendingPermission) return
  try {
    const data = await Sessions.permissions(sessionId, true)
    const list = Array.isArray(data?.data) ? data.data as PermissionInfo[] : []
    if (list.length > 0) showPermission(list[0])
  } catch {
    // Ignore transient polling failures.
  }
}

function renderTasks() {
  tasksContainer.innerHTML = ''
  if (!sessionId) {
    tasksContainer.textContent = 'Keine Session.'
    return
  }
  if (tasks.length === 0) {
    tasksContainer.textContent = 'No subagent runs.'
    return
  }
  for (const task of tasks) {
    const div = document.createElement('div')
    div.style.cssText = 'margin-bottom:.5rem;padding:.4rem;border:1px solid var(--border);border-radius:6px'
    const title = document.createElement('div')
    title.style.cssText = 'display:flex;justify-content:space-between;gap:.5rem'
    const name = document.createElement('strong')
    name.textContent = task.agent || 'subagent'
    const state = document.createElement('span')
    const color = task.state === 'running' ? 'var(--warn)' : task.state === 'succeeded' || task.state === 'completed' ? 'var(--ok)' : 'var(--err)'
    state.style.color = color
    state.textContent = task.state || ''
    title.appendChild(name)
    title.appendChild(state)
    div.appendChild(title)
    const meta = document.createElement('div')
    meta.style.cssText = 'color:var(--muted);font-size:.75rem'
    meta.textContent = (task.tokens || 0) + ' tokens'
    div.appendChild(meta)
    if (task.text) {
      const text = document.createElement('div')
      text.style.cssText = 'white-space:pre-wrap;margin-top:.25rem;font-size:.78rem'
      text.textContent = String(task.text).slice(0, 400)
      div.appendChild(text)
    }
    if (task.error) {
      const error = document.createElement('div')
      error.style.cssText = 'color:var(--err);margin-top:.25rem;font-size:.78rem'
      error.textContent = task.error
      div.appendChild(error)
    }
    if (task.childSession) {
      const link = document.createElement('a')
      link.href = '/?session=' + encodeURIComponent(task.childSession)
      link.target = '_blank'
      link.rel = 'noopener noreferrer'
      link.style.cssText = 'font-size:.75rem;display:block;margin-top:.25rem'
      link.textContent = '↗ Neues Tab'
      div.appendChild(link)
    }
    if (task.state === 'running') {
      const button = document.createElement('button')
      button.textContent = 'Cancel'
      button.style.marginTop = '.25rem'
      button.addEventListener('click', () => {
        if (!sessionId) return
        void Sessions.cancelTask(sessionId, task.id).then(() => loadTasks(sessionId as string))
      })
      div.appendChild(button)
    }
    tasksContainer.appendChild(div)
  }
}

async function loadTasks(id: string) {
  if (!id) {
    tasksContainer.textContent = 'Keine Session.'
    return false
  }
  try {
    const data = await Sessions.tasks(id)
    tasks = Array.isArray(data?.data) ? data.data as TaskInfo[] : []
    renderTasks()
    return tasks.some((task) => task.state === 'running')
  } catch (error) {
    tasksContainer.textContent = 'Error: ' + (error instanceof Error ? error.message : String(error))
    return false
  }
}

async function startTaskPoll(id: string) {
  if (taskTimer !== null) {
    window.clearInterval(taskTimer)
    taskTimer = null
  }
  taskPollActive = true
  const running = await loadTasks(id)
  if (!running) {
    taskPollActive = false
    return
  }
  taskTimer = window.setInterval(() => {
    if (sessionId !== id) {
      if (taskTimer !== null) window.clearInterval(taskTimer)
      taskTimer = null
      taskPollActive = false
      return
    }
    void loadTasks(id).then((stillRunning) => {
      if (!stillRunning && taskTimer !== null) {
        window.clearInterval(taskTimer)
        taskTimer = null
        taskPollActive = false
      }
    })
  }, 5000)
}

function handleSseEvent(event: unknown) {
  const ev = event as Record<string, unknown>
  if (ev && typeof ev._type === 'string') {
    switch (ev._type) {
      case 'TextStarted':
        sawStreamed = true
        currentAssistant = addMessage('assistant', '')
        currentAssistantRaw = ''
        break
      case 'TextDelta':
        if (!currentAssistant) {
          currentAssistant = addMessage('assistant', '')
          currentAssistantRaw = ''
        }
        sawStreamed = true
        currentAssistantRaw += typeof ev.text === 'string' ? ev.text : ''
        updateAssistant(currentAssistantRaw)
        break
      case 'TextEnded':
        if (typeof ev.text === 'string' && !currentAssistantRaw) currentAssistantRaw = ev.text
        if (currentAssistant) updateAssistant(currentAssistantRaw || (typeof ev.text === 'string' ? ev.text : ''))
        currentAssistant = null
        currentAssistantRaw = ''
        break
      case 'ToolCallRequested': {
        if (!el<HTMLInputElement>('showDetails').checked) break
        sawStreamed = true
        const name = typeof ev.name === 'object' && ev.name !== null && 'value' in ev.name
          ? String((ev.name as { value?: unknown }).value)
          : String(ev.name ?? '')
        let inputText = ''
        try {
          inputText = typeof ev.input === 'string' ? ev.input : JSON.stringify(ev.input)
        } catch {
          inputText = String(ev.input)
        }
        if (inputText.length > 1500) inputText = inputText.slice(0, 1500) + '…'
        addMessage('tool', '🔧 ' + name + '(' + inputText + ')')
        break
      }
      case 'ToolResultProvided': {
        if (!el<HTMLInputElement>('showDetails').checked) break
        sawStreamed = true
        const outcome = ev.outcome as { text?: unknown; value?: unknown } | undefined
        const result = outcome?.text ?? (outcome?.value ? JSON.stringify(outcome.value) : '')
        addMessage('tool', '↳ ' + (String(result || '').slice(0, 4000) || '(no result)'))
        break
      }
      case 'ToolFailed':
        addMessage('error', '⚠ ' + String(ev.message ?? 'Tool failed'))
        break
      case 'SubagentStarted':
      case 'SubagentFinished':
      case 'SubagentCancelled':
        void loadTasks(sessionId as string)
        break
      case 'ProviderFailed':
        addMessage('error', 'Provider error: ' + String(ev.message ?? ''))
        break
      case 'RetryScheduled':
        addMessage('tool', '⏳ Retry in ' + String(ev.delay ?? '') + 's (Versuch ' + String(ev.attempt ?? '') + ')')
        break
      default:
        logEvent(String(ev._type))
    }
    return
  }

  if (typeof ev.type === 'string') {
    if (ev.type === 'tool_result') {
      const trimmed = String(ev.result ?? '').slice(0, 4000)
      addMessage('tool', '↳ ' + (trimmed || '(no result)'))
      return
    }
    logEvent(ev.type + (typeof (ev.data as { text?: unknown } | undefined)?.text === 'string' ? ': ' + String((ev.data as { text: string }).text).slice(0, 80) : ''))
  }
}

function connectSse(id: string) {
  sseConnection?.close()
  sseConnection = null
  if (!id) return
  sseConnection = openSse(`/api/event?session=${encodeURIComponent(id)}&live=true`, {
    onEvent: handleSseEvent,
    onError: () => {
      if (sessionId !== id) return
      status.textContent = 'SSE getrennt – reconnecting...'
      window.setTimeout(() => {
        if (sessionId === id) connectSse(id)
      }, 3000)
    },
  })
  status.textContent = 'Session ' + id.slice(0, 12) + '…'
}

async function loadMessages(id: string) {
  messages.innerHTML = ''
  currentAssistant = null
  currentAssistantRaw = ''
  sawStreamed = false
  try {
    const data = await Sessions.messages(id)
    const list = Array.isArray(data?.data) ? data.data : []
    for (const item of list) {
      const message = item as { type?: string; text?: string; tools?: Array<{ name?: string; input?: unknown; result?: unknown }>; time?: string }
      const type = (message.type || '').toLowerCase()
      const role = type.includes('user') ? 'user' : type.includes('assistant') ? 'assistant' : 'tool'
      addMessage(role, message.text || '', message.time)
      if (Array.isArray(message.tools)) {
        for (const tool of message.tools) {
          let inputText = ''
          try {
            inputText = typeof tool.input === 'string' ? tool.input : JSON.stringify(tool.input)
          } catch {
            inputText = String(tool.input)
          }
          addMessage('tool', '🔧 ' + (tool.name || '') + '(' + inputText + ')', message.time)
          addMessage('tool', '↳ ' + (tool.result || '(no result)'), message.time)
        }
      }
    }
  } catch (error) {
    logEvent('Error loading: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function loadSelectors() {
  try {
    const agentsResponse = await Catalog.agents()
    const workflowAgents = await QocApi.agents.list().catch(() => ({ agents: [] }))
    const byName = new Map<string, { value: string; label: string }>()
    const dbNames = new Set<string>()
    const engineAgents = Array.isArray(agentsResponse?.data) ? agentsResponse.data : []
    for (const item of engineAgents) {
      const agent = item as { name?: string; id?: string; hidden?: boolean }
      if (agent.hidden || !agent.name || !agent.id) continue
      byName.set(agent.name.toLowerCase(), { value: agent.id, label: agent.name })
    }
    const workflowList = Array.isArray(workflowAgents?.agents) ? workflowAgents.agents : []
    for (const item of workflowList) {
      const agent = item as { name?: string; id?: string }
      if (!agent.name || !agent.id) continue
      dbNames.add(agent.name.toLowerCase())
      const existing = byName.get(agent.name.toLowerCase())
      byName.set(agent.name.toLowerCase(), {
        value: existing?.value || ('wf:' + agent.id),
        label: agent.name,
      })
    }
    ;(window as any).__qocDbAgentNames = dbNames
    agentSelect.innerHTML = ''
    for (const option of byName.values()) {
      const element = document.createElement('option')
      element.value = option.value
      element.textContent = option.label
      agentSelect.appendChild(element)
    }
    if (agentSelect.options.length === 0) {
      const option = document.createElement('option')
      option.textContent = '(none)'
      agentSelect.appendChild(option)
    }
  } catch (error) {
    logEvent('selectors: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function openAgentEditor() {
  const selectedOption = agentSelect.options[agentSelect.selectedIndex]
  currentAgentName = (selectedOption?.textContent || '').trim() || agentSelect.value
  window.open('/agents?agent=' + encodeURIComponent(currentAgentName), '_blank')
}

async function saveAgentEditor() {
  const content = document.getElementById('agentOverlayContent') as HTMLTextAreaElement | null
  const status = document.getElementById('agentOverlayStatus') as HTMLParagraphElement | null
  const overlay = document.getElementById('agentOverlay') as HTMLDivElement | null
  if (!content || !status || !overlay || !currentAgentName) return
  try {
    await QocApi.agents.save(currentAgentName, { name: currentAgentName, content: content.value })
    status.textContent = 'Gespeichert'
    await loadSelectors()
    for (let i = 0; i < agentSelect.options.length; i++) {
      if (agentSelect.options[i].textContent?.trim() === currentAgentName) {
        agentSelect.selectedIndex = i
        break
      }
    }
    window.setTimeout(() => { overlay.style.display = 'none' }, 400)
  } catch (error) {
    status.textContent = 'Speichern fehlgeschlagen: ' + (error instanceof Error ? error.message : String(error))
  }
}

async function deleteAgentEditor() {
  const status = document.getElementById('agentOverlayStatus') as HTMLParagraphElement | null
  const overlay = document.getElementById('agentOverlay') as HTMLDivElement | null
  if (!currentAgentName || !overlay) return
  if (!window.confirm('Agent "' + currentAgentName + '" wirklich löschen?')) return
  try {
    await QocApi.agents.delete(currentAgentName)
    if (status) status.textContent = 'Gelöscht'
    await loadSelectors()
    overlay.style.display = 'none'
  } catch (error) {
    if (status) status.textContent = 'Löschen fehlgeschlagen: ' + (error instanceof Error ? error.message : String(error))
  }
}

async function switchSession(id: string) {
  if (!id || id === sessionId) return
  sseConnection?.close()
  sseConnection = null
  sessionId = id
  localStorage.setItem('qoc.sessionId', id)
  sessionsSelect.value = id
  await loadMessages(id)
  connectSse(id)
  status.textContent = 'Session ' + id.slice(0, 12) + '…'
  await loadTasks(id)
}

async function loadSessions() {
  try {
    const data = await Sessions.list()
    const list = Array.isArray(data?.data) ? data.data as SessionInfo[] : []
    sessions = list
    sessionsSelect.innerHTML = ''
    for (const session of list) {
      const option = document.createElement('option')
      option.value = session.id
      option.textContent = (session.title || 'Session') + ' — ' + session.id.slice(0, 12)
      sessionsSelect.appendChild(option)
    }
    if (sessionId) sessionsSelect.value = sessionId
    if (!sessionId) {
      const stored = localStorage.getItem('qoc.sessionId')
      const next = stored && list.some((item) => item.id === stored) ? stored : list[0]?.id
      if (next) await switchSession(next)
    }
  } catch (error) {
    logEvent('sessions: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function newSession() {
  try {
    const data = await Sessions.create(agentSelect.value ? { agent: agentSelect.value } : {})
    const id = typeof data?.id === 'string' ? data.id : ''
    if (!id) return
    await loadSessions()
    await switchSession(id)
  } catch (error) {
    logEvent('new session: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function deleteCurrentSession() {
  if (!sessionId) return
  const selected = sessionsSelect.options[sessionsSelect.selectedIndex]
  const label = selected ? selected.textContent : sessionId
  if (!window.confirm('Really delete session?\n\n' + (label || sessionId))) return
  try {
    await Sessions.delete(sessionId)
    sseConnection?.close()
    sseConnection = null
    sessionId = null
    currentAssistant = null
    currentAssistantRaw = ''
    sawStreamed = false
    messages.innerHTML = ''
    await loadSessions()
    const next = sessionsSelect.value
    if (next) await switchSession(next)
    else status.textContent = 'Keine Session'
  } catch (error) {
    logEvent('delete: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function interrupt() {
  if (!sessionId) return
  try {
    await Sessions.interrupt(sessionId)
    await loadTasks(sessionId)
  } catch (error) {
    logEvent('interrupt: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function compactNow() {
  if (!sessionId) return
  try {
    await Sessions.compact(sessionId)
    logEvent('Compact done')
    await loadMessages(sessionId)
  } catch (error) {
    logEvent('Error: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function revertTurn() {
  if (!sessionId) return
  try {
    await Sessions.revert(sessionId)
    logEvent('Revert done')
    await loadMessages(sessionId)
  } catch (error) {
    logEvent('Error: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function forkSession() {
  if (!sessionId) return
  try {
    const tree = await Sessions.tree(sessionId)
    if (!tree?.leaf) throw new Error('Session has no message to fork')
    const data = await Sessions.fork(sessionId, tree.leaf)
    if (typeof data?.id !== 'string') throw new Error('Fork failed')
    logEvent('Fork erstellt: ' + data.id.slice(0, 12))
    await loadSessions()
    await switchSession(data.id)
  } catch (error) {
    window.alert('Fork fehlgeschlagen: ' + (error instanceof Error ? error.message : String(error)))
  }
}

async function handleCommand(command: string) {
  if (!sessionId) return
  const parts = command.trim().split(/\s+/)
  const name = parts[0].slice(1)
  const args = parts.slice(1).join(' ')
  addMessage('user', command)
  switch (name) {
    case 'new':
      await newSession()
      break
    case 'interrupt':
      await Sessions.interrupt(sessionId)
      logEvent('Interrupt gesendet')
      break
    case 'title':
      try {
        const data = await Sessions.title(sessionId)
        logEvent('Titel: ' + (data?.title || ''))
      } catch (error) {
        logEvent('Error: ' + (error instanceof Error ? error.message : String(error)))
      }
      break
    case 'compact':
      await compactNow()
      break
    case 'revert':
      await revertTurn()
      break
    case 'fork':
      await forkSession()
      break
    default:
      input.value = command
      await send()
  }
  void args
}

async function send() {
  const text = input.value.trim()
  if (!text || !sessionId) return
  if (text.startsWith('/')) {
    await handleCommand(text)
    input.value = ''
    return
  }
  addMessage('user', text)
  input.value = ''
  sending = true
  sendBtn.textContent = 'Interrupt'
  status.textContent = 'Denke…'
  if (permissionTimer !== null) {
    window.clearInterval(permissionTimer)
    permissionTimer = null
  }
  permissionTimer = window.setInterval(() => {
    void checkPermissions()
  }, 700)
  sawStreamed = false
  currentAssistant = null
  currentAssistantRaw = ''
  try {
    const data = await Sessions.prompt(sessionId, text)
    if (data?.text && !sawStreamed) addMessage('assistant', data.text)
  } catch (error) {
    addMessage('error', 'Error: ' + (error instanceof Error ? error.message : String(error)))
  } finally {
    if (permissionTimer !== null) {
      window.clearInterval(permissionTimer)
      permissionTimer = null
    }
    sending = false
    sendBtn.textContent = 'Send'
    status.textContent = 'ready'
    await loadSessions()
    if (sessionId) {
      await loadTasks(sessionId)
      startTaskPoll(sessionId)
    }
  }
}

async function loadQuestions() {
  try {
    const data = await Questions.list()
    questions = Array.isArray(data?.data) ? data.data as QuestionInfo[] : []
    questionsBadge.textContent = String(questions.length)
    questionsBadge.style.display = questions.length > 0 ? '' : 'none'
    questionsPanel.style.display = questions.length > 0 ? '' : 'none'
    questionsList.innerHTML = ''
    for (const question of questions) {
      const container = document.createElement('div')
      container.style.cssText = 'border:1px solid var(--border);border-radius:6px;padding:8px;margin-bottom:8px;background:var(--surface)'
      const title = document.createElement('div')
      title.style.cssText = 'font-weight:600;font-size:0.85rem;margin-bottom:4px;color:var(--accent)'
      title.textContent = 'Session: ' + (question.sessionId || '').slice(0, 12) + '…'
      container.appendChild(title)
      const selected = new Set<string>()
      for (const entry of question.questions || []) {
        const text = document.createElement('div')
        text.style.cssText = 'font-size:0.85rem;margin-bottom:6px'
        text.textContent = entry.question || ''
        container.appendChild(text)
        if (entry.options && entry.options.length > 0) {
          for (const option of entry.options) {
            const label = document.createElement('label')
            label.style.cssText = 'display:block;font-size:0.8rem;margin:2px 0;cursor:pointer'
            const input = document.createElement('input')
            input.type = entry.multiple ? 'checkbox' : 'radio'
            input.name = 'q_' + question.id
            input.value = option.label
            input.style.marginRight = '4px'
            input.addEventListener('change', () => {
              if (entry.multiple) {
                if (input.checked) selected.add(option.label)
                else selected.delete(option.label)
              } else {
                selected.clear()
                if (input.checked) selected.add(option.label)
              }
            })
            label.appendChild(input)
            label.appendChild(document.createTextNode(option.label + (option.description ? ' – ' + option.description : '')))
            container.appendChild(label)
          }
        }
        const textarea = document.createElement('textarea')
        textarea.placeholder = 'Antwort (optional)...'
        textarea.style.cssText = 'width:100%;margin-top:4px;font-size:0.8rem;min-height:40px;background:var(--bg);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px'
        container.appendChild(textarea)
        const button = document.createElement('button')
        button.textContent = 'Antworten'
        button.style.cssText = 'margin-top:6px;padding:4px 12px;font-size:0.8rem;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer'
        button.addEventListener('click', () => {
          const answer = textarea.value
          void Questions.reply(question.sessionId, question.id, { selected: Array.from(selected), text: answer }).then(() => {
            logEvent('Frage beantwortet: ' + question.id)
            void loadQuestions()
          }).catch((error) => {
            logEvent('Error answering: ' + (error instanceof Error ? error.message : String(error)))
          })
        })
        container.appendChild(button)
      }
      questionsList.appendChild(container)
    }
  } catch {
    // Ignore transient question polling failures.
  }
}

async function replyPermissionModal(reply: 'ONCE' | 'ALWAYS' | 'REJECT') {
  await replyPermission(reply)
}

async function bootstrap() {
  await handleOidcCallback()
  updateAuthStatus()
  const loginButton = document.getElementById('loginBtn')
  if (loginButton) loginButton.addEventListener('click', () => {
    void loginOrLogoutImported()
  })
  await loadSelectors()
  const editAgentBtn = document.getElementById('editAgentBtn')
  if (editAgentBtn) editAgentBtn.addEventListener('click', () => { void openAgentEditor() })
  const closeBtn = document.getElementById('agentOverlayClose')
  if (closeBtn) closeBtn.addEventListener('click', () => {
    const overlay = document.getElementById('agentOverlay') as HTMLDivElement | null
    if (overlay) overlay.style.display = 'none'
  })
  const cancelBtn = document.getElementById('agentOverlayCancel')
  if (cancelBtn) cancelBtn.addEventListener('click', () => {
    const overlay = document.getElementById('agentOverlay') as HTMLDivElement | null
    if (overlay) overlay.style.display = 'none'
  })
  const saveBtn = document.getElementById('agentOverlaySave')
  if (saveBtn) saveBtn.addEventListener('click', () => { void saveAgentEditor() })
  const deleteBtn = document.getElementById('agentOverlayDelete')
  if (deleteBtn) deleteBtn.addEventListener('click', () => { void deleteAgentEditor() })
  await loadSessions()
  await loadQuestions()
  questionTimer = window.setInterval(() => {
    void loadQuestions()
  }, 5000)
  if (sessionId) {
    await loadTasks(sessionId)
    startTaskPoll(sessionId)
  }
}

// Dynamic import keeps the shared auth module tree-shakeable for the core build.
async function loginOrLogoutImported() {
  const auth = await import('@shared/auth')
  await auth.loginOrLogout()
}

function bindEvents() {
  sendBtn.addEventListener('click', () => {
    if (sending) {
      if (sessionId) void Sessions.interrupt(sessionId)
      return
    }
    void send()
  })
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault()
      void send()
    }
  })
  sessionsSelect.addEventListener('change', () => {
    const value = sessionsSelect.value
    if (value) void switchSession(value)
  })
  agentSelect.addEventListener('change', () => {
    if (!sessionId) return
    void Sessions.switchAgent(sessionId, agentSelect.value).catch((error) => {
      logEvent('agent switch: ' + (error instanceof Error ? error.message : String(error)))
    })
  })
  const sessionActions = document.getElementById('sessionActions')
  if (sessionActions) sessionActions.addEventListener('change', () => {
    const value = (sessionActions as HTMLSelectElement).value
    ;(sessionActions as HTMLSelectElement).value = ''
    if (value === 'new') void newSession()
    else if (value === 'delete') void deleteCurrentSession()
  })
  el<HTMLButtonElement>('hardCancelBtn').addEventListener('click', () => {
    void interrupt()
  })
  el<HTMLButtonElement>('permReject').addEventListener('click', () => {
    void replyPermissionModal('REJECT')
  })
  el<HTMLButtonElement>('permOnce').addEventListener('click', () => {
    void replyPermissionModal('ONCE')
  })
  el<HTMLButtonElement>('permAlways').addEventListener('click', () => {
    void replyPermissionModal('ALWAYS')
  })
  const refreshQuestions = document.getElementById('refreshQuestions')
  if (refreshQuestions) refreshQuestions.addEventListener('click', () => {
    void loadQuestions()
  })
  const questionSend = document.getElementById('questionSend')
  if (questionSend) questionSend.addEventListener('click', () => {
    // The modal is a simplified placeholder; the inline question list handles replies.
    questionModal.classList.remove('open')
  })
}

bindEvents()
void bootstrap()