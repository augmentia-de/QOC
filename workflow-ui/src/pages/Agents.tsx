import { useEffect, useState } from 'react';
import { WorkflowApi } from '../services/api';

export default function Agents() {
  const [agents, setAgents] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      // Load agents from all workflows
      const res = await WorkflowApi.list();
      const allAgents: any[] = [];
      for (const id of res.data.workflows) {
        try {
          const wf = await WorkflowApi.get(id);
          if (wf.data.agents) {
            wf.data.agents.forEach((a: any) => {
              allAgents.push({ ...a, workflowId: id, workflowName: wf.data.name });
            });
          }
        } catch {}
      }
      setAgents(allAgents);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Agents</h1>
      <button onClick={load} className="px-4 py-2 bg-gray-700 rounded text-sm mb-4">Aktualisieren</button>
      {loading ? (
        <p className="text-gray-400">Lade...</p>
      ) : agents.length === 0 ? (
        <p className="text-gray-400">Keine Agents gefunden. Agents werden in Workflows definiert.</p>
      ) : (
        <div className="grid gap-2">
          {agents.map((a, i) => (
            <div key={i} className="p-4 bg-gray-800 rounded">
              <div className="flex justify-between items-center">
                <span className="font-bold">{a.name || a.id}</span>
                <span className="text-xs text-gray-400 font-mono">{a.model}</span>
              </div>
              <div className="text-xs text-gray-400 mt-1">
                Workflow: {a.workflowName} · Temp: {a.temperature} · MaxTokens: {a.maxTokens}
              </div>
              {a.systemPrompt && (
                <div className="text-xs text-gray-500 mt-2 truncate">{a.systemPrompt.substring(0, 100)}...</div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
