import { useEffect, useState } from 'react'
import { Catalog, EngineAgents } from '../services/qocApi'

interface EngineAgent {
  id?: string
  name?: string
  hidden?: boolean
}

interface AvailableTools {
  tools: string
  mcp: string
  a2a: string
}

const emptyForm = {
  name: '',
  mode: 'subagent',
  model: '',
  maxSteps: 0,
  hidden: false,
  system: '',
  toolAllow: '*',
  toolDeny: '',
  mcp: '*',
  roles: '',
  permissions: '[{"action":"*","resource":"*","effect":"allow"}]',
  verifyOnStop: '[]',
  skills: '{"enabled":true,"allow":[],"deny":[],"max":0,"prompt":"top-k"}',
}

function splitList(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function parseJson(value: string, fallback: unknown): unknown {
  const trimmed = value.trim()
  if (!trimmed) return fallback
  return JSON.parse(trimmed)
}

function buildContent(form: typeof emptyForm): string {
  const toolAllow = splitList(form.toolAllow)
  const toolDeny = splitList(form.toolDeny)
  const mcp = splitList(form.mcp)
  const roles = splitList(form.roles)
  const permissions = parseJson(form.permissions, [{ action: '*', resource: '*', effect: 'allow' }])
  const verifyOnStop = parseJson(form.verifyOnStop, [])
  const skills = form.skills.trim() ? parseJson(form.skills, null) : null

  const lines = [
    '---',
    'name: ' + JSON.stringify(form.name.trim()),
    'mode: ' + form.mode,
  ]
  if (form.model.trim()) lines.push('model: ' + JSON.stringify(form.model.trim()))
  lines.push('hidden: ' + form.hidden)
  lines.push('steps: ' + form.maxSteps)
  lines.push('toolAllow: ' + JSON.stringify(toolAllow.length ? toolAllow : ['*']))
  lines.push('toolDeny: ' + JSON.stringify(toolDeny))
  lines.push('mcpServers: ' + JSON.stringify(mcp.length ? mcp : ['*']))
  if (roles.length) lines.push('rolesAllowed: ' + JSON.stringify(roles))
  lines.push('permissions: ' + JSON.stringify(permissions))
  if (Array.isArray(verifyOnStop) && verifyOnStop.length) lines.push('verifyOnStop: ' + JSON.stringify(verifyOnStop))
  if (skills) lines.push('skills: ' + JSON.stringify(skills))
  lines.push('---')
  lines.push('')
  if (form.system.trim()) lines.push(form.system.trim())
  return lines.join('\n')
}

export default function EngineAgentsPage() {
  const [agents, setAgents] = useState<EngineAgent[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [editingName, setEditingName] = useState<string | null>(null)
  const [form, setForm] = useState(emptyForm)
  const [status, setStatus] = useState('')
  const [available, setAvailable] = useState<AvailableTools>({ tools: '', mcp: '', a2a: '' })

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const data = await EngineAgents.list()
      setAgents(Array.isArray(data?.data) ? data.data as EngineAgent[] : [])
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setAgents([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const loadAvailable = async () => {
    try {
      const [tools, mcp, a2a] = await Promise.all([
        Catalog.tools().catch(() => ({ data: [] })),
        Catalog.mcp().catch(() => ({ data: [] })),
        Catalog.a2a().catch(() => ({ data: [] })),
      ])
      setAvailable({
        tools: Array.isArray(tools?.data) ? tools.data.map((item: { name?: string }) => item.name || '').filter(Boolean).join(', ') : '',
        mcp: Array.isArray(mcp?.data) ? mcp.data.map((item: unknown) => String(item)).join(', ') : '',
        a2a: Array.isArray(a2a?.data) ? a2a.data.map((item: unknown) => String(item)).join(', ') : '',
      })
    } catch {
      setAvailable({ tools: '', mcp: '', a2a: '' })
    }
  }

  const openNew = () => {
    setEditingName(null)
    setForm(emptyForm)
    setStatus('')
    setShowForm(true)
    void loadAvailable()
  }

  const openEdit = async (name: string) => {
    try {
      const data = await EngineAgents.get(name)
      const content = typeof data?.content === 'string' ? data.content : ''
      setEditingName(name)
      setForm({ ...emptyForm, name, system: content })
      setStatus('')
      setShowForm(true)
      void loadAvailable()
    } catch (err) {
      setStatus(err instanceof Error ? err.message : String(err))
    }
  }

  const save = async () => {
    const name = form.name.trim()
    if (!name) {
      setStatus('Name required')
      return
    }
    setStatus('Saving…')
    try {
      const content = buildContent(form)
      if (editingName && editingName !== name) {
        await EngineAgents.delete(editingName)
      }
      if (editingName && editingName === name) {
        await EngineAgents.update(name, content)
      } else {
        await EngineAgents.create(name, content)
      }
      setStatus('Gespeichert: ' + name)
      setShowForm(false)
      await load()
    } catch (err) {
      setStatus('Error: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const remove = async (name: string) => {
    if (!window.confirm('Delete agent "' + name + '"?')) return
    try {
      await EngineAgents.delete(name)
      await load()
    } catch (err) {
      window.alert('Delete failed: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const field = (label: string, value: string, onChange: (value: string) => void, multiline = false) => (
    <label style={{ display: 'block', marginBottom: 8 }}>
      <span style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>{label}</span>
      {multiline ? (
        <textarea value={value} onChange={(event) => onChange(event.target.value)} rows={4} style={{ width: '100%', background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 4, padding: 6, fontSize: 12, resize: 'vertical' }} />
      ) : (
        <input value={value} onChange={(event) => onChange(event.target.value)} style={{ width: '100%', background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 4, padding: 6, fontSize: 12 }} />
      )}
    </label>
  )

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ fontSize: 22, color: '#5bc0de', margin: 0 }}>Engine Agents</h1>
        <button onClick={openNew} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>+ New agent</button>
      </div>

      {loading ? <p style={{ color: '#999' }}>Loading agents...</p> : error ? <p style={{ color: '#f85149' }}>Error: {error}</p> : agents.length === 0 ? (
        <p style={{ color: '#999' }}>No engine agents configured.</p>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {agents.map((agent) => (
            <div key={agent.id || agent.name} style={{ padding: 12, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
              <div>
                <span style={{ fontWeight: 'bold' }}>{agent.name || agent.id}</span>
                {agent.hidden ? <span style={{ fontSize: 11, color: '#999', marginLeft: 8 }}>(hidden)</span> : null}
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <button onClick={() => void openEdit(agent.name || agent.id || '')} style={{ padding: '4px 10px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Edit</button>
                <button onClick={() => void remove(agent.name || agent.id || '')} style={{ padding: '4px 10px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Delete</button>
              </div>
            </div>
          ))}
        </div>
      )}

      {showForm ? (
        <div style={{ position: 'fixed', inset: 0, zIndex: 50, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,0.6)', padding: 16, overflow: 'auto' }}>
          <div style={{ width: '100%', maxWidth: 760, maxHeight: '90vh', overflow: 'auto', padding: 20, background: '#1e1e1e', borderRadius: 8, border: '1px solid #333' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ fontSize: 16, color: '#5bc0de', margin: 0 }}>{editingName ? 'Edit agent' : 'New agent'}</h2>
              <button onClick={() => setShowForm(false)} style={{ background: 'none', border: 'none', color: '#999', fontSize: 20, cursor: 'pointer' }}>×</button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
              <div>{field('Name', form.name, (value) => setForm((prev) => ({ ...prev, name: value })))}</div>
              <div>{field('Mode', form.mode, (value) => setForm((prev) => ({ ...prev, mode: value })))}</div>
              <div>{field('Model', form.model, (value) => setForm((prev) => ({ ...prev, model: value })), false)}</div>
              <div>{field('Max Steps', String(form.maxSteps), (value) => setForm((prev) => ({ ...prev, maxSteps: Number(value) || 0 })))}</div>
            </div>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, margin: '8px 0' }}>
              <input type="checkbox" checked={form.hidden} onChange={(event) => setForm((prev) => ({ ...prev, hidden: event.target.checked }))} />
              Hidden
            </label>
            {field('System prompt', form.system, (value) => setForm((prev) => ({ ...prev, system: value })), true)}
            {field('Tool allow (comma-separated or *)', form.toolAllow, (value) => setForm((prev) => ({ ...prev, toolAllow: value })))}
            {field('Tool deny (comma-separated)', form.toolDeny, (value) => setForm((prev) => ({ ...prev, toolDeny: value })))}
            {field('MCP servers (comma-separated or *)', form.mcp, (value) => setForm((prev) => ({ ...prev, mcp: value })))}
            {field('Roles (comma-separated)', form.roles, (value) => setForm((prev) => ({ ...prev, roles: value })))}
            {field('Permissions (JSON array)', form.permissions, (value) => setForm((prev) => ({ ...prev, permissions: value })), true)}
            {field('Verify-on-Stop (JSON array)', form.verifyOnStop, (value) => setForm((prev) => ({ ...prev, verifyOnStop: value })), true)}
            {field('Skills (JSON)', form.skills, (value) => setForm((prev) => ({ ...prev, skills: value })), true)}
            <label style={{ display: 'block', marginBottom: 8 }}>
              <span style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Available tools / MCP / A2A</span>
              <textarea value={available.tools + '\n' + available.mcp + '\n' + available.a2a} readOnly rows={4} style={{ width: '100%', background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 4, padding: 6, fontSize: 12, resize: 'vertical' }} />
            </label>
            {status ? <p style={{ fontSize: 12, color: '#999' }}>{status}</p> : null}
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button onClick={() => void save()} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Save</button>
              <button onClick={() => setShowForm(false)} style={{ padding: '6px 14px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Cancel</button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}