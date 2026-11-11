import { useEffect, useState } from 'react';
import axios from 'axios';

export default function Observe() {
  const [runs, setRuns] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/runs?limit=100');
      setRuns(res.data.runs || []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, []);

  const statusColor = (s: string) => {
    if (s === 'COMPLETED') return 'bg-green-500';
    if (s === 'RUNNING') return 'bg-blue-500';
    if (s === 'FAILED') return 'bg-red-500';
    return 'bg-gray-500';
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Observe</h1>
      <p className="text-gray-400 mb-4">Live-Überwachung aller Runs (Auto-Refresh 5s)</p>
      {loading ? (
        <p className="text-gray-400">Lade...</p>
      ) : (
        <div className="grid gap-2">
          {runs.map((r: any) => (
            <div key={r.workflowId} className="flex items-center gap-3 p-3 bg-gray-800 rounded">
              <div className={`w-3 h-3 rounded-full ${statusColor(r.status)}`} />
              <span className="font-mono text-sm text-blue-400 flex-1">{r.workflowId}</span>
              <span className="text-sm text-gray-400">{r.status}</span>
              <span className="text-xs text-gray-500">{r.startTime}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
