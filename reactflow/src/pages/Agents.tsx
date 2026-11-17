import { useEffect, useState } from 'react';
import { QocApi } from '../services/qocApi.ts';
import { SchemaSource } from '../components/SchemaSource.tsx';
import { AgentConfigOverlay, AgentForm } from '../components/AgentConfigOverlay.tsx';

const emptyAgent: AgentForm = {
  name: '', version: '1.0.0', category: '', model: 'deepseek-v4.1-flash',
  temperature: 0.3, maxTokens: 4096, topP: 1.0, tools: [], guardrailsInput: '', guardrailsOutput: '',
  hooks: '', jsonOutput: false, active: true, ownerId: '',
  mode: 'all', skills: '', permissions: '', prompt: '', schema: '', requiresHITL: false,
  maxTurns: 10, maxCost: 1.0,
};

const inp = { width: '100%', padding: '6px 8px', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, fontSize: 12 };

export default function Agents() {
  const [agents, setAgents] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<any | null>(null);
  const [showForm, setShowForm] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await QocApi.agents.list();
      setAgents(Array.isArray(res.agents) ? res.agents : []);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const name = params.get('agent');
    if (!name) return;
    const found = agents.find((a) => a.name === name || a.id === name);
    if (found) { setEditing({ ...found }); setShowForm(true); }
  }, [agents]);

  const toAgent = (f: AgentForm) => ({
    id: f.name,
    name: f.name,
    version: f.version,
    category: f.category,
    model: f.model,
    temperature: f.temperature,
    maxTokens: f.maxTokens,
    topP: f.topP,
    tools: f.tools,
    guardrailsInput: f.guardrailsInput.split(',').map((s) => s.trim()).filter(Boolean),
    guardrailsOutput: f.guardrailsOutput.split(',').map((s) => s.trim()).filter(Boolean),
    hooks: f.hooks.split(',').map((s) => s.trim()).filter(Boolean),
    jsonOutput: f.jsonOutput,
    systemPrompt: f.prompt,
    responseSchema: f.schema.trim() ? (() => { try { return JSON.parse(f.schema); } catch { return null; } })() : null,
    ownerId: f.ownerId,
    rolesAllowed: ['*'],
    mode: f.mode,
    skills: f.skills.split(',').map((s) => s.trim()).filter(Boolean),
  });

  const handleSave = async () => {
    if (!editing) return;
    const agent = toAgent(editing);
    try {
      if (editing.name && agents.find((a: any) => a.id === editing.name || a.name === editing.name)) {
        await QocApi.agents.save(editing.name, agent);
      } else {
        const created = await QocApi.agents.create(agent);
        setEditing(created ? { ...editing, name: (created as any).name || editing.name } : editing);
      }
      setShowForm(false); setEditing(null); load();
    } catch (e) { console.error(e); alert('Save failed'); }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm(`Delete agent "${id}"?`)) return;
    try { await QocApi.agents.delete(id); load(); }
    catch (e) { console.error(e); alert('Delete failed'); }
  };

  const handleNew = () => { setEditing({ ...emptyAgent }); setShowForm(true); };
  const handleEdit = (a: any) => {
    setEditing({
      name: a.name || a.id || '',
      version: a.version || '1.0.0',
      category: a.category || '',
      model: a.model || 'deepseek-v4.1-flash',
      temperature: a.temperature ?? 0.3,
      maxTokens: a.maxTokens ?? 4096,
      topP: a.topP ?? 1.0,
      permissions: '',
      prompt: a.systemPrompt || '',
      jsonOutput: !!a.jsonOutput,
      schema: a.responseSchema ? JSON.stringify(a.responseSchema, null, 2) : '',
      tools: Array.isArray(a.tools) ? a.tools : [],
      guardrailsInput: Array.isArray(a.guardrailsInput) ? a.guardrailsInput.join(', ') : '',
      guardrailsOutput: Array.isArray(a.guardrailsOutput) ? a.guardrailsOutput.join(', ') : '',
      hooks: Array.isArray(a.hooks) ? a.hooks.join(', ') : '',
      mode: a.mode || 'all',
      skills: Array.isArray(a.skills) ? a.skills.join(', ') : '',
      ownerId: a.ownerId || '',
      maxTurns: 10,
      maxCost: 1.0,
      active: a.active !== false,
      requiresHITL: false,
    });
    setShowForm(true);
  };

  if (loading) return <div style={{ padding: 24, color: '#999' }}>Loading agents...</div>;

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ fontSize: 22, color: '#5bc0de' }}>Agents</h1>
        <button onClick={handleNew} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>+ New agent</button>
      </div>

      {showForm && editing && (
        <AgentConfigOverlay
          open={true}
          agent={editing}
          onChange={(patch) => setEditing((prev) => (prev ? { ...prev, ...patch } : prev))}
          onClose={() => { setShowForm(false); setEditing(null); }}
          onSave={handleSave}
          showWorkflowActions={false}
        />
      )}

      {agents.length === 0 ? (
        <p style={{ color: '#999' }}>No agents in store. Create one with "+ New agent".</p>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {agents.map((a: any) => (
            <div key={a.id} style={{ padding: 12, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <span style={{ fontWeight: 'bold' }}>{a.name || a.id}</span>
                <span style={{ fontSize: 11, color: '#999', fontFamily: 'monospace', marginLeft: 8 }}>{a.model}</span>
                <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>Temp: {a.temperature} · MaxTokens: {a.maxTokens} · Tools: {(a.tools || []).join(', ') || 'none'}</div>
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <button onClick={() => handleEdit(a)} style={{ padding: '4px 10px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Edit</button>
                <button onClick={() => handleDelete(a.id)} style={{ padding: '4px 10px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Delete</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
