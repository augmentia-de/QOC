import { useEffect, useState } from 'react';
import axios from 'axios';

export default function HITL() {
  const [pending, setPending] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/runs?limit=50');
      // Filter for runs that might need human decision
      setPending(res.data.runs || []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const decide = async (id: string, approved: boolean) => {
    try {
      await axios.post(`/api/runs/${id}/terminate`, { approved });
      load();
    } catch (e) {
      console.error(e);
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">HITL (Human-in-the-Loop)</h1>
      <p className="text-gray-400 mb-4">Ausstehende Entscheidungen</p>
      {loading ? (
        <p className="text-gray-400">Lade...</p>
      ) : pending.length === 0 ? (
        <p className="text-gray-400">Keine ausstehenden Entscheidungen.</p>
      ) : (
        <div className="space-y-2">
          {pending.map((r: any) => (
            <div key={r.workflowId} className="p-4 bg-gray-800 rounded">
              <div className="flex justify-between items-center">
                <span className="font-mono text-sm text-blue-400">{r.workflowId}</span>
                <span className="text-sm text-gray-400">{r.status}</span>
              </div>
              <div className="flex gap-2 mt-2">
                <button onClick={() => decide(r.workflowId, true)} className="px-3 py-1 bg-green-600 rounded text-xs">
                  Genehmigen
                </button>
                <button onClick={() => decide(r.workflowId, false)} className="px-3 py-1 bg-red-600 rounded text-xs">
                  Ablehnen
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
