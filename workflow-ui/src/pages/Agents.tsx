import { useEffect, useState } from 'react';
import { AgentApi } from '../services/api';
import type { Agent } from '../types/workflow';

export default function Agents() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Agent | null>(null);
  const [showForm, setShowForm] = useState(false);

  const emptyAgent: Agent = {
    id: '',
    name: '',
    version: '1.0.0',
    category: '',
    model: 'deepseek-v4.1-flash',
    temperature: 0.3,
    maxTokens: 4096,
    topP: 1.0,
    tools: [],
    guardrailsInput: [],
    guardrailsOutput: [],
    hooks: [],
    jsonOutput: false,
    active: true,
    systemPrompt: '',
    responseSchema: null,
  };

  const load = async () => {
    setLoading(true);
    try {
      const res = await AgentApi.list();
      setAgents(res.data.agents || []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleSave = async () => {
    if (!editing) return;
    try {
      if (editing.id && agents.find((a) => a.id === editing.id)) {
        await AgentApi.save(editing.id, editing);
      } else {
        const created = await AgentApi.create(editing);
        setEditing(created.data);
      }
      setShowForm(false);
      setEditing(null);
      load();
    } catch (e) {
      console.error(e);
      alert('Speichern fehlgeschlagen');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm(`Agent "${id}" löschen?`)) return;
    try {
      await AgentApi.delete(id);
      load();
    } catch (e) {
      console.error(e);
      alert('Löschen fehlgeschlagen');
    }
  };

  const handleNew = () => {
    setEditing({ ...emptyAgent });
    setShowForm(true);
  };

  const handleEdit = (agent: Agent) => {
    setEditing({ ...agent });
    setShowForm(true);
  };

  if (loading) {
    return <div className="p-8 text-gray-400">Lade Agents...</div>;
  }

  return (
    <div className="p-8">
      <div className="flex justify-between items-center mb-4">
        <h1 className="text-2xl font-bold">Agents (Templates)</h1>
        <button onClick={handleNew} className="px-4 py-2 bg-blue-600 rounded text-sm font-bold">
          + Neuer Agent
        </button>
      </div>

      {showForm && editing && (
        <div className="mb-6 p-4 bg-gray-800 rounded border border-gray-700">
          <h2 className="font-bold mb-3">{editing.id ? 'Agent bearbeiten' : 'Neuer Agent'}</h2>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs text-gray-400 mb-1">ID</label>
              <input value={editing.id} onChange={(e) => setEditing({ ...editing, id: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Name</label>
              <input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Model</label>
              <input value={editing.model} onChange={(e) => setEditing({ ...editing, model: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Temperature</label>
              <input type="number" step="0.1" value={editing.temperature} onChange={(e) => setEditing({ ...editing, temperature: parseFloat(e.target.value) || 0.3 })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Max Tokens</label>
              <input type="number" value={editing.maxTokens} onChange={(e) => setEditing({ ...editing, maxTokens: parseInt(e.target.value) || 4096 })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Top P</label>
              <input type="number" step="0.05" value={editing.topP} onChange={(e) => setEditing({ ...editing, topP: parseFloat(e.target.value) || 1.0 })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
          </div>
          <div className="mt-3">
            <label className="block text-xs text-gray-400 mb-1">System Prompt</label>
            <textarea value={editing.systemPrompt} onChange={(e) => setEditing({ ...editing, systemPrompt: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-24" />
          </div>
          <div className="mt-3">
            <label className="block text-xs text-gray-400 mb-1">Tools (comma-separated)</label>
            <input value={editing.tools.join(', ')} onChange={(e) => setEditing({ ...editing, tools: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
          </div>
          <div className="mt-3">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={editing.jsonOutput} onChange={(e) => setEditing({ ...editing, jsonOutput: e.target.checked })} />
              JSON Output
            </label>
          </div>
          <div className="mt-3">
            <label className="block text-xs text-gray-400 mb-1">Response Schema (JSON Schema, optional)</label>
            <textarea value={editing.responseSchema ? JSON.stringify(editing.responseSchema, null, 2) : ''} onChange={(e) => {
              const v = e.target.value.trim();
              try { setEditing({ ...editing, responseSchema: v ? JSON.parse(v) : null }); } catch { /* keep raw? */ }
            }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-32 font-mono" placeholder='{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}' />
            <p className="text-xs text-gray-500 mt-1">Leer = keine strukturierte Ausgabe. Import via /api/schema/* möglich.</p>
          </div>
          <div className="flex gap-2 mt-4">
            <button onClick={handleSave} className="px-4 py-2 bg-blue-600 rounded text-sm font-bold">Speichern</button>
            <button onClick={() => { setShowForm(false); setEditing(null); }} className="px-4 py-2 bg-gray-600 rounded text-sm">Abbrechen</button>
          </div>
        </div>
      )}

      {agents.length === 0 ? (
        <p className="text-gray-400">Keine Agents im Store. Erstelle einen mit "+ Neuer Agent".</p>
      ) : (
        <div className="grid gap-2">
          {agents.map((a) => (
            <div key={a.id} className="p-4 bg-gray-800 rounded flex justify-between items-center">
              <div>
                <span className="font-bold">{a.name || a.id}</span>
                <span className="text-xs text-gray-400 font-mono ml-2">{a.model}</span>
                <div className="text-xs text-gray-400 mt-1">
                  Temp: {a.temperature} · MaxTokens: {a.maxTokens} · Tools: {a.tools.join(', ') || 'keine'}
                </div>
              </div>
              <div className="flex gap-2">
                <button onClick={() => handleEdit(a)} className="px-3 py-1 bg-gray-600 rounded text-xs">Bearbeiten</button>
                <button onClick={() => handleDelete(a.id)} className="px-3 py-1 bg-red-600 rounded text-xs">Löschen</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}