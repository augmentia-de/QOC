import { useEffect, useMemo, useState } from 'react'
import { SchemaSource } from './SchemaSource.tsx'
import { AGENT_PRESETS } from '../pages/agentPresets.ts'
import { Catalog } from '../services/qocApi.ts'

export type AgentForm = {
  name: string;
  version: string;
  category: string;
  model: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  permissions: string;
  prompt: string;
  jsonOutput: boolean;
  schema: string;
  tools: string[];
  permissionRules: { action: string; resource: string; effect: string }[];
  permissionPreset: string;
  guardrailsInput: string;
  guardrailsOutput: string;
  hooks: string;
  mode: string;
  skills: string;
  ownerId: string;
  maxTurns: number;
  maxCost: number;
  active: boolean;
  requiresHITL: boolean;
};

interface ToolItem { name: string; namespace: string }

const CORE_TOOLS = ['read','write','edit','glob','grep','patch','shell','process','webfetch','secure_rest','firecrawl','todo','skill','worktree_create','worktree_list','worktree_remove','agent_create','agent_list','subagent','task','session_rename','session_move','session_models']

export function AgentConfigOverlay({ open, agent, onChange, onClose, onSave, onSaveRef, onSaveToFile, onResetToRef, showWorkflowActions = false, showRef = true }: {
  open: boolean;
  agent: AgentForm;
  onChange: (patch: Partial<AgentForm>) => void;
  onClose: () => void;
  onSave?: () => void;
  onSaveRef?: () => void;
  onSaveToFile?: () => void;
  onResetToRef?: () => void;
  showWorkflowActions?: boolean;
  showRef?: boolean;
}) {
  const [availableTools, setAvailableTools] = useState<ToolItem[]>([])
  const [loadingTools, setLoadingTools] = useState(false)
  const [search, setSearch] = useState('')
  const [dragged, setDragged] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setLoadingTools(true)
    void (async () => {
      try {
        const res: any = await Catalog.tools()
        const data = Array.isArray(res?.data) ? res.data : []
        const items: ToolItem[] = data
          .map((d: any) => ({ name: String(d?.name || '').trim(), namespace: String(d?.namespace || 'core').trim() || 'core' }))
          .filter((t: ToolItem) => t.name)
        setAvailableTools(items.length ? items : CORE_TOOLS.map((n) => ({ name: n, namespace: 'core' })))
      } catch {
        setAvailableTools(CORE_TOOLS.map((n) => ({ name: n, namespace: 'core' })))
      } finally {
        setLoadingTools(false)
      }
    })()
  }, [open])

  const selectedSet = useMemo(() => new Set(agent.tools), [agent.tools])

  const filteredAvailable = useMemo(() => {
    const q = search.trim().toLowerCase()
    return availableTools.filter((t) => !selectedSet.has(t.name) && (!q || t.name.toLowerCase().includes(q) || t.namespace.toLowerCase().includes(q)))
  }, [availableTools, selectedSet, search])

  const groupedAvailable = useMemo(() => {
    const g: Record<string, ToolItem[]> = {}
    for (const t of filteredAvailable) {
      if (!g[t.namespace]) g[t.namespace] = []
      g[t.namespace].push(t)
    }
    return Object.keys(g).sort().map((ns) => ({ ns, items: g[ns].sort((a, b) => a.name.localeCompare(b.name)) }))
  }, [filteredAvailable])

  const selectedItems = useMemo(() => {
    const map = new Map(availableTools.map((t) => [t.name, t]))
    return agent.tools.map((n) => map.get(n) || { name: n, namespace: 'stale' })
  }, [agent.tools, availableTools])

  const staleItems = selectedItems.filter((t) => t.namespace === 'stale')

  const addTool = (name: string) => onChange({ tools: [...agent.tools, name] })
  const removeTool = (name: string) => onChange({ tools: agent.tools.filter((t) => t !== name) })
  const addAllNamespace = (ns: string) => {
    const names = availableTools.filter((t) => t.namespace === ns && !selectedSet.has(t.name)).map((t) => t.name)
    onChange({ tools: [...agent.tools, ...names] })
  }
  const removeAllNamespace = (ns: string) => {
    const toRemove = new Set(availableTools.filter((t) => t.namespace === ns).map((t) => t.name))
    onChange({ tools: agent.tools.filter((t) => !toRemove.has(t)) })
  }

  const onDropToSelected = (e: React.DragEvent) => {
    e.preventDefault()
    if (dragged && !selectedSet.has(dragged)) addTool(dragged)
    setDragged(null)
  }
  const onDropToAvailable = (e: React.DragEvent) => {
    e.preventDefault()
    if (dragged && selectedSet.has(dragged)) removeTool(dragged)
    setDragged(null)
  }

  if (!open) return null

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: '#2d2d2d', color: '#d4d4d4', padding: 20, borderRadius: 8, width: '90vw', maxWidth: 900, maxHeight: '90vh', overflow: 'auto', border: '1px solid #555' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <h3 style={{ margin: 0 }}>Agent Config</h3>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer', fontSize: 16 }}>x</button>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <label>Name<br /><input value={agent.name} onChange={(e) => onChange({ name: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Version<br /><input value={agent.version} onChange={(e) => onChange({ version: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Category<br /><input value={agent.category} onChange={(e) => onChange({ category: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Model (disabled — agent uses default model)<br />
            <select value="" disabled style={{ width: '100%', background: '#1e1e1e', color: '#888', border: '1px solid #555' }}>
              <option value="">(default)</option>
            </select>
          </label>
          <label>Permission Preset<br />
            <select value={agent.permissionPreset || ''} onChange={(e) => {
              const p = AGENT_PRESETS.find((x) => x.id === e.target.value)
              if (p) onChange({ permissionPreset: p.id, tools: p.tools, permissionRules: p.permissions })
              else onChange({ permissionPreset: '' })
            }} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }}>
              <option value="">(custom / unverändert)</option>
              {AGENT_PRESETS.map((p) => (<option key={p.id} value={p.id}>{p.label}</option>))}
            </select>
          </label>
          <label>Roles allowed (comma, * = all)<br /><input value={agent.permissions} onChange={(e) => onChange({ permissions: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        </div>

        <div style={{ marginTop: 12 }}>
          <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>Tools — {agent.tools.length} of {availableTools.length} selected {loadingTools ? '(loading…)' : ''}</div>
          <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search tools…" style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', padding: 6, marginBottom: 6, borderRadius: 4 }} />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <div onDragOver={(e) => e.preventDefault()} onDrop={onDropToAvailable} style={{ border: '1px solid #555', borderRadius: 4, padding: 6, minHeight: 200, maxHeight: 320, overflow: 'auto', background: '#1a1a1a' }}>
              <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>Available ({filteredAvailable.length})</div>
              {groupedAvailable.map(({ ns, items }) => (
                <div key={ns} style={{ marginBottom: 6 }}>
                  <div style={{ fontSize: 11, color: '#5bc0de', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span>▼ {ns} ({items.length})</span>
                    <button onClick={() => addAllNamespace(ns)} style={{ fontSize: 10, background: '#337ab7', color: '#fff', border: 'none', borderRadius: 3, cursor: 'pointer', padding: '1px 6px' }}>add all</button>
                  </div>
                  {items.map((t) => (
                    <div key={t.name} draggable onDragStart={() => setDragged(t.name)} style={{ fontSize: 12, padding: '2px 4px', cursor: 'grab', borderBottom: '1px solid #333' }}>
                      {t.name}
                      <button onClick={() => addTool(t.name)} style={{ float: 'right', fontSize: 10, background: '#5cb85c', color: '#fff', border: 'none', borderRadius: 3, cursor: 'pointer', padding: '0 4px' }}>+</button>
                    </div>
                  ))}
                </div>
              ))}
              {filteredAvailable.length === 0 && <div style={{ fontSize: 11, color: '#666' }}>No matching tools</div>}
            </div>
            <div onDragOver={(e) => e.preventDefault()} onDrop={onDropToSelected} style={{ border: '1px solid #555', borderRadius: 4, padding: 6, minHeight: 200, maxHeight: 320, overflow: 'auto', background: '#1a1a1a' }}>
              <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>Selected ({agent.tools.length})</div>
              {selectedItems.filter((t) => t.namespace !== 'stale').map((t) => (
                <div key={t.name} draggable onDragStart={() => setDragged(t.name)} style={{ fontSize: 12, padding: '2px 4px', cursor: 'grab', borderBottom: '1px solid #333', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span>{t.name}</span>
                  <button onClick={() => removeTool(t.name)} style={{ fontSize: 10, background: '#d9534f', color: '#fff', border: 'none', borderRadius: 3, cursor: 'pointer', padding: '0 4px' }}>−</button>
                </div>
              ))}
              {staleItems.length > 0 && (
                <div style={{ marginTop: 8 }}>
                  <div style={{ fontSize: 11, color: '#f0ad4e' }}>Stale / unavailable ({staleItems.length})</div>
                  {staleItems.map((t) => (
                    <div key={t.name} style={{ fontSize: 12, padding: '2px 4px', color: '#f0ad4e', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span>{t.name}</span>
                      <button onClick={() => removeTool(t.name)} style={{ fontSize: 10, background: '#d9534f', color: '#fff', border: 'none', borderRadius: 3, cursor: 'pointer', padding: '0 4px' }}>remove</button>
                    </div>
                  ))}
                </div>
              )}
              {agent.tools.length === 0 && <div style={{ fontSize: 11, color: '#666' }}>No tools selected</div>}
            </div>
          </div>
        </div>

        <label style={{ display: 'block', marginTop: 8 }}>Guardrails Input (comma)<br />
          <input value={agent.guardrailsInput} onChange={(e) => onChange({ guardrailsInput: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>Guardrails Output (comma)<br />
          <input value={agent.guardrailsOutput} onChange={(e) => onChange({ guardrailsOutput: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>Hooks (comma)<br />
          <input value={agent.hooks} onChange={(e) => onChange({ hooks: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>Mode<br />
          <select value={agent.mode} onChange={(e) => onChange({ mode: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }}>
            <option value="all">all</option>
            <option value="primary">primary</option>
            <option value="subagent">subagent</option>
          </select>
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>System Prompt<br />
          <textarea value={agent.prompt} onChange={(e) => onChange({ prompt: e.target.value })} rows={4} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>Response Schema (JSON)<br />
          <textarea value={agent.schema} onChange={(e) => onChange({ schema: e.target.value })} rows={3} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>
          <input type="checkbox" checked={agent.jsonOutput} onChange={(e) => onChange({ jsonOutput: e.target.checked })} /> JSON Output
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Max Turns<br />
          <input type="number" value={agent.maxTurns} onChange={(e) => onChange({ maxTurns: Number(e.target.value) })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>Max Cost<br />
          <input type="number" step="0.1" value={agent.maxCost} onChange={(e) => onChange({ maxCost: Number(e.target.value) })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        <label style={{ display: 'block', marginTop: 8 }}>
          <input type="checkbox" checked={agent.active} onChange={(e) => onChange({ active: e.target.checked })} /> Active
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>
          <input type="checkbox" checked={agent.requiresHITL} onChange={(e) => onChange({ requiresHITL: e.target.checked })} /> Requires HITL
        </label>
        <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap', position: 'sticky', bottom: 0, background: '#2d2d2d', padding: '8px 0', zIndex: 2, touchAction: 'manipulation' }}>
          {onSave && <button onClick={onSave} style={{ padding: '6px 14px', background: '#5cb85c', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Save</button>}
          {showWorkflowActions && onSaveRef && <button onClick={onSaveRef} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Save Ref</button>}
          {showWorkflowActions && onSaveToFile && <button onClick={onSaveToFile} style={{ padding: '6px 14px', background: '#17a2b8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Save ref to file</button>}
          {showWorkflowActions && onResetToRef && <button onClick={onResetToRef} style={{ padding: '6px 14px', background: '#f0ad4e', color: '#000', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Reset to ref</button>}
          <button onClick={onClose} style={{ padding: '6px 14px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>Cancel</button>
        </div>
      </div>
    </div>
  )
}