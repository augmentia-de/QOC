import { useEffect, useState } from 'react';
import { QocApi } from '../services/qocApi.ts';
import { SchemaSource } from '../components/SchemaSource.tsx';

const emptyAgent: any = {
  id: '', name: '', version: '1.0.0', category: '', model: 'deepseek-v4.1-flash',
  temperature: 0.3, maxTokens: 4096, topP: 1.0, tools: [], guardrailsInput: [], guardrailsOutput: [],
  hooks: [], jsonOutput: false, active: true, systemPrompt: '', responseSchema: null, ownerId: '',
  rolesAllowed: ['*'], mode: 'all', skills: [],
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

  const handleSave = async () => {
    if (!editing) return;
    try {
      if (editing.id && agents.find((a) => a.id === editing.id)) {
        await QocApi.agents.save(editing.id, editing);
      } else {
        const created = await QocApi.agents.create(editing);
        setEditing(created);
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
  const handleEdit = (a: any) => { setEditing({ ...a }); setShowForm(true); };

  if (loading) return <div style={{ padding: 24, color: '#999' }}>Loading agents...</div>;

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ fontSize: 22, color: '#5bc0de' }}>Agents</h1>
        <button onClick={handleNew} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>+ New agent</button>
      </div>

      {showForm && editing && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 50, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,0.6)', padding: 16 }}>
          <div style={{ width: '100%', maxWidth: 720, maxHeight: '90vh', overflow: 'auto', padding: 20, background: '#1e1e1e', borderRadius: 8, border: '1px solid #333' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ fontSize: 16, color: '#5bc0de' }}>{editing.id ? 'Edit agent' : 'New agent'}</h2>
              <button onClick={() => { setShowForm(false); setEditing(null); }} style={{ background: 'none', border: 'none', color: '#999', fontSize: 20, cursor: 'pointer' }}>×</button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
              <div><label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>ID</label><input value={editing.id} onChange={(e) => setEditing({ ...editing, id: e.target.value })} style={inp} /></div>
              <div><label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Name</label><input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} style={inp} /></div>
              <div><label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Model</label><input value={editing.model} onChange={(e) => setEditing({ ...editing, model: e.target.value })} style={inp} /></div>
              <div><label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Temperature</label><input type="number" step="0.1" value={editing.temperature} onChange={(e) => setEditing({ ...editing, temperature: parseFloat(e.target.value) || 0.3 })} style={inp} /></div>
              <div><label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Max Tokens</label><input type="number" value={editing.maxTokens} onChange={(e) => setEditing({ ...editing, maxTokens: parseInt(e.target.value) || 4096 })} style={inp} /></div>
              <div><label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Top P</label><input type="number" step="0.05" value={editing.topP} onChange={(e) => setEditing({ ...editing, topP: parseFloat(e.target.value) || 1.0 })} style={inp} /></div>
            </div>
            <div style={{ marginTop: 10 }}>
              <label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>System Prompt</label>
              <textarea value={editing.systemPrompt} onChange={(e) => setEditing({ ...editing, systemPrompt: e.target.value })} style={{ ...inp, height: 80, resize: 'vertical' }} />
            </div>
            <div style={{ marginTop: 10 }}>
              <label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Tools (comma-separated)</label>
              <input value={(editing.tools || []).join(', ')} onChange={(e) => setEditing({ ...editing, tools: e.target.value.split(',').map((s: string) => s.trim()).filter(Boolean) })} style={inp} />
            </div>
            <div style={{ marginTop: 10 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                <input type="checkbox" checked={editing.jsonOutput} onChange={(e) => setEditing({ ...editing, jsonOutput: e.target.checked })} />
                JSON Output
              </label>
            </div>
            <div style={{ marginTop: 10 }}>
              <SchemaSource value={editing.responseSchema ?? null} onChange={(schema: any) => setEditing({ ...editing, responseSchema: schema })} label="Response Schema" />
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <button onClick={handleSave} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Save</button>
              <button onClick={() => { setShowForm(false); setEditing(null); }} style={{ padding: '6px 14px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Cancel</button>
            </div>
          </div>
        </div>
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
