import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { WorkflowApi } from '../services/api';
import type { WorkflowDefinition } from '../types/workflow';

export default function Workflows() {
  const [workflows, setWorkflows] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [newName, setNewName] = useState('');

  const load = async () => {
    setLoading(true);
    try {
      const res = await WorkflowApi.list();
      setWorkflows(res.data.workflows);
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
      await WorkflowApi.create(newName.trim());
      setNewName('');
      load();
    } catch (e) {
      console.error(e);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm(`Workflow "${id}" wirklich löschen?`)) return;
    try {
      await WorkflowApi.delete(id);
      load();
    } catch (e) {
      console.error(e);
      alert('Löschen fehlgeschlagen');
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Workflows</h1>
      <div className="flex gap-2 mb-6">
        <input
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          placeholder="Name des neuen Workflows"
          className="px-3 py-2 bg-gray-700 rounded text-sm flex-1 max-w-xs"
        />
        <button onClick={create} className="px-4 py-2 bg-blue-600 rounded text-sm">
          + Neuer Workflow
        </button>
      </div>
      {loading ? (
        <p className="text-gray-400">Lade...</p>
      ) : workflows.length === 0 ? (
        <p className="text-gray-400">Keine Workflows vorhanden.</p>
      ) : (
        <div className="grid gap-2">
          {workflows.map((id) => (
            <div
              key={id}
              className="flex items-center justify-between p-4 bg-gray-800 rounded hover:bg-gray-700"
            >
              <Link to={`/workflows/${id}`} className="font-mono text-blue-400">
                {id}
              </Link>
              <button
                onClick={() => handleDelete(id)}
                className="px-3 py-1 bg-red-700 hover:bg-red-600 rounded text-sm"
              >
                Löschen
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}