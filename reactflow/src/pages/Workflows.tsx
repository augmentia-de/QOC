import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { QocApi } from '../services/qocApi.ts';

export default function Workflows() {
  const [workflows, setWorkflows] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [newName, setNewName] = useState('');

  const load = async () => {
    setLoading(true);
    try {
      const res = await QocApi.workflows.list();
      setWorkflows(Array.isArray(res.workflows) ? res.workflows : []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const create = async () => {
    if (!newName.trim()) return;
    try {
      await QocApi.workflows.create({ name: newName.trim() });
      setNewName('');
      load();
    } catch (e) {
      console.error(e);
      alert('Erstellen fehlgeschlagen');
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm(`Workflow "${id}" wirklich löschen?`)) return;
    try {
      await QocApi.workflows.delete(id);
      load();
    } catch (e) {
      console.error(e);
      alert('Löschen fehlgeschlagen');
    }
  };

  const inputStyle = { padding: '6px 10px', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, fontSize: 13 };

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 16 }}>Workflows</h1>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="Name des neuen Workflows" style={{ ...inputStyle, flex: 1, maxWidth: 300 }} />
        <button onClick={create} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>+ Neuer Workflow</button>
      </div>
      {loading ? (
        <p style={{ color: '#999' }}>Lade...</p>
      ) : workflows.length === 0 ? (
        <p style={{ color: '#999' }}>Keine Workflows vorhanden.</p>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {workflows.map((id) => (
            <div key={id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: 12, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333' }}>
              <Link to={`/workflows/${id}`} style={{ fontFamily: 'monospace', color: '#5bc0de', textDecoration: 'none' }}>{id}</Link>
              <button onClick={() => handleDelete(id)} style={{ padding: '4px 10px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Löschen</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
