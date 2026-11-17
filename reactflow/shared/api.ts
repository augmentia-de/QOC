import { apiFetch } from './auth'

const BASE = '/api'

export interface RequestOptions extends RequestInit {
  // Convenience for callers that build query strings themselves.
}

async function request(path: string, options: RequestOptions = {}): Promise<any> {
  const response = await apiFetch(BASE + path, options)
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(text || response.statusText || ('HTTP ' + response.status))
  }
  if (response.status === 204) return null
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) return response.json()
  return response.text()
}

function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined) continue
    search.set(key, String(value))
  }
  const text = search.toString()
  return text ? '?' + text : ''
}

export const Api = {
  workflows: {
    list: () => request('/workflows'),
    get: (id: string) => request('/workflows/' + encodeURIComponent(id)),
    save: (id: string, def: unknown) => request('/workflows/' + encodeURIComponent(id), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(def),
    }),
    create: (def: unknown) => request('/workflows', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(def),
    }),
    delete: (id: string) => request('/workflows/' + encodeURIComponent(id), { method: 'DELETE' }),
  },
  agents: {
    list: () => request('/agents'),
    get: (id: string) => request('/agents/' + encodeURIComponent(id)),
    save: (id: string, agent: unknown) => request('/agents/' + encodeURIComponent(id), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(agent),
    }),
    create: (agent: unknown) => request('/agents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(agent),
    }),
    delete: (id: string) => request('/agents/' + encodeURIComponent(id), { method: 'DELETE' }),
  },
}

export const QocApi = {
  ...Api,
  workflows: {
    ...Api.workflows,
    validate: (def: unknown) => request('/workflows/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(def),
    }),
    run: (def: unknown, input: string) => request('/workflows/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ workflow: def, input }),
    }),
    generate: (prompt: string) => request('/workflows/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    }),
    versions: (id: string) => request('/workflows/' + encodeURIComponent(id) + '/versions'),
    version: (id: string, version: string) => request(
      '/workflows/' + encodeURIComponent(id) + '/versions/' + encodeURIComponent(version),
    ),
  },
  agents: {
    ...Api.agents,
  },
  mcpClient: {
    listServers: () => request('/mcp-client/servers'),
    getServerTools: (name: string) => request('/mcp-client/servers/' + encodeURIComponent(name) + '/tools'),
    reloadServer: (name: string) => request('/mcp-client/servers/' + encodeURIComponent(name) + '/reload', { method: 'POST' }),
    addServer: (config: unknown) => request('/mcp-client/servers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    }),
    removeServer: (name: string) => request('/mcp-client/servers/' + encodeURIComponent(name), { method: 'DELETE' }),
    listTools: () => request('/mcp-client/tools'),
  },
  health: {
    check: () => request('/health'),
  },
  schema: {
    fromJava: (source: string) => request('/schema/from-java', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ source }),
    }),
    fromOpenApi: (spec: string, schemaName?: string) => request('/schema/from-openapi', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ spec, schemaName }),
    }),
    fromYaml: (yaml: string, schemaName?: string) => request('/schema/from-yaml', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ yaml, schemaName }),
    }),
    validate: (schema: unknown) => request('/schema/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ schema }),
    }),
  },
}

export const Sessions = {
  list: () => request('/session'),
  get: (id: string) => request('/session/' + encodeURIComponent(id)),
  create: (body: unknown = {}) => request('/session', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }),
  delete: (id: string) => request('/session/' + encodeURIComponent(id), { method: 'DELETE' }),
  switchAgent: (id: string, agent: string) => request('/session/' + encodeURIComponent(id) + '/agent', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agent }),
  }),
  prompt: (id: string, text: string) => request('/session/' + encodeURIComponent(id) + '/prompt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  }),
  interrupt: (id: string) => request('/session/' + encodeURIComponent(id) + '/interrupt', { method: 'POST' }),
  running: (id: string) => request('/session/' + encodeURIComponent(id) + '/running'),
  messages: (id: string) => request('/session/' + encodeURIComponent(id) + '/messages'),
  permissions: (id: string, includeChildren = false) => request(
    '/session/' + encodeURIComponent(id) + '/permission' + query({ includeChildren }),
  ),
  replyPermission: (sessionId: string, permissionId: string, reply: string) => request(
    '/session/' + encodeURIComponent(sessionId) + '/permission/' + encodeURIComponent(permissionId) + '/reply',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reply }),
    },
  ),
  tasks: (id: string) => request('/session/' + encodeURIComponent(id) + '/tasks'),
  cancelTask: (sessionId: string, taskId: string) => request(
    '/session/' + encodeURIComponent(sessionId) + '/tasks/' + encodeURIComponent(taskId) + '/cancel',
    { method: 'POST' },
  ),
  compact: (id: string) => request('/session/' + encodeURIComponent(id) + '/compact', { method: 'POST' }),
  revert: (id: string) => request('/session/' + encodeURIComponent(id) + '/revert', { method: 'POST' }),
  fork: (id: string, boundary?: string) => request('/session/' + encodeURIComponent(id) + '/fork', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(boundary ? { boundary } : {}),
  }),
  tree: (id: string) => request('/session/' + encodeURIComponent(id) + '/tree'),
  title: (id: string, title?: string) => request('/session/' + encodeURIComponent(id) + '/title', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(title ? { title } : {}),
  }),
  command: (id: string, command: string) => request('/session/' + encodeURIComponent(id) + '/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ command }),
  }),
}

export const Runs = {
  list: (limit = 50) => request('/workflows/runs' + query({ limit })),
  get: (runId: string) => request('/workflows/runs/' + encodeURIComponent(runId)),
}

export const Questions = {
  list: () => request('/questions'),
  reply: (sessionId: string, questionId: string, payload: string | { selected?: string[]; text?: string }) => request(
    '/session/' + encodeURIComponent(sessionId) + '/question/' + encodeURIComponent(questionId) + '/reply',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(typeof payload === 'string' ? { text: payload } : payload),
    },
  ),
}

export const Files = {
  list: (path = '.') => request('/files' + query({ path })),
  read: (path: string) => request('/file' + query({ path })),
  create: (path: string, content = '') => request('/file', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, content }),
  }),
  upload: (path: string, body: Blob | ArrayBuffer | Uint8Array) => apiFetch(BASE + '/file/upload' + query({ path }), {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: body as BodyInit,
  }).then(async (response) => {
    if (!response.ok) {
      const text = await response.text().catch(() => '')
      throw new Error(text || response.statusText || ('HTTP ' + response.status))
    }
    return response.json().catch(() => ({ ok: true }))
  }),
  delete: (path: string) => request('/file' + query({ path }), { method: 'DELETE' }),
  downloadFile: (path: string) => apiFetch(BASE + '/file/download' + query({ path })),
  downloadDirectory: (path: string) => apiFetch(BASE + '/dir/download' + query({ path })),
}

export const Workspace = {
  get: () => request('/workspace'),
  set: (path: string, create = false) => request('/workspace', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, create }),
  }),
}

export const Catalog = {
  agents: () => request('/agent'),
  models: () => request('/model'),
  providers: () => request('/provider'),
  tools: () => request('/tool'),
  mcp: () => request('/mcp'),
  a2a: () => request('/a2a'),
}

export const EngineAgents = {
  list: () => request('/agent'),
  get: (name: string) => request('/agent/' + encodeURIComponent(name)),
  create: (name: string, content: string) => request('/agent', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, content }),
  }),
  update: (name: string, content: string) => request('/agent/' + encodeURIComponent(name), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  }),
  delete: (name: string) => request('/agent/' + encodeURIComponent(name), { method: 'DELETE' }),
}

export const A2A = {
  listAgents: (refresh = false) => request('/a2a/agents' + (refresh ? '?refresh=true' : '')),
  call: (body: unknown) => request('/a2a', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }),
}

export const LlmTrace = {
  get: (tail = 1600, limit = 1600) => request('/llm/trace' + query({ tail, limit })),
}

export async function backendAvailable(): Promise<boolean> {
  try {
    const response = await apiFetch(BASE + '/workflows')
    return response.ok
  } catch {
    return false
  }
}

export default QocApi