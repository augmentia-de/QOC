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
            <Link
              key={id}
              to={`/workflows/${id}`}
              className="block p-4 bg-gray-800 rounded hover:bg-gray-700"
            >
              <span className="font-mono text-blue-400">{id}</span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}