import { useEffect, useState } from 'react';
import axios from 'axios';

interface Run {
  runId: string;
  sessionId: string;
  workflowId: string;
  status: string;
  startedAt: string;
}

interface Permission {
  id: string;
  sessionId: string;
  action: string;
  resources: string[];
  message?: string;
}

export default function HITL() {
  const [runs, setRuns] = useState<Run[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<Run | null>(null);
  const [pending, setPending] = useState<Permission[]>([]);
  const [loadingPending, setLoadingPending] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/workflows/runs?limit=50');
      setRuns(res.data.runs || []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const loadPending = async (run: Run) => {
    setSelected(run);
    setPending([]);
    if (!run.sessionId) return;
    setLoadingPending(true);
    try {
      const res = await axios.get(
        `/api/session/${encodeURIComponent(run.sessionId)}/permission?includeChildren=true`);
      setPending(res.data.data || []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoadingPending(false);
    }
  };

  const decide = async (perm: Permission, approved: boolean) => {
    try {
      await axios.post(
        `/api/session/${encodeURIComponent(perm.sessionId)}/permission/${encodeURIComponent(perm.id)}/reply`,
        { reply: approved ? 'ONCE' : 'REJECT' });
      if (selected) await loadPending(selected);
      load();
    } catch (e) {
      console.error(e);
      alert('Entscheidung fehlgeschlagen');
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">HITL (Human-in-the-Loop)</h1>
      <p className="text-gray-400 mb-4">Run auswählen, um ausstehende Berechtigungen zu bearbeiten.</p>
      {loading ? (
        <p className="text-gray-400">Lade...</p>
      ) : runs.length === 0 ? (
        <p className="text-gray-400">Keine Runs vorhanden.</p>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="space-y-2">
            {runs.map((r) => (
              <div
                key={r.runId}
                onClick={() => loadPending(r)}
                className={`p-3 bg-gray-800 rounded cursor-pointer hover:bg-gray-700 ${selected?.runId === r.runId ? 'ring-2 ring-blue-500' : ''}`}
              >
                <div className="flex justify-between gap-2">
                  <span className="font-mono text-sm text-blue-400 truncate">{r.runId}</span>
                  <span className="text-sm text-gray-400">{r.status}</span>
                </div>
                <div className="text-xs text-gray-500 mt-1">{r.workflowId} · {r.startedAt}</div>
              </div>
            ))}
          </div>
          <div>
            {!selected ? (
              <p className="text-gray-400">Run auswählen.</p>
            ) : loadingPending ? (
              <p className="text-gray-400">Lade Berechtigungen...</p>
            ) : pending.length === 0 ? (
              <p className="text-gray-400">Keine ausstehenden Entscheidungen.</p>
            ) : (
              <div className="space-y-2">
                {pending.map((p) => (
                  <div key={p.id} className="p-4 bg-gray-800 rounded">
                    <div className="text-sm font-bold text-yellow-400">{p.action}</div>
                    <div className="text-xs text-gray-300 mt-1 break-all">
                      {(p.resources || []).join('\n')}
                    </div>
                    {p.message && <div className="text-xs text-gray-500 mt-1">{p.message}</div>}
                    <div className="flex gap-2 mt-2">
                      <button onClick={() => decide(p, true)} className="px-3 py-1 bg-green-600 rounded text-xs">
                        Genehmigen
                      </button>
                      <button onClick={() => decide(p, false)} className="px-3 py-1 bg-red-600 rounded text-xs">
                        Ablehnen
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
