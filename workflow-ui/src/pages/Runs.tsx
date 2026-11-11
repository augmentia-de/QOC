import { useEffect, useState } from 'react';
import axios from 'axios';

interface Run {
  workflowId: string;
  runId: string;
  workflowType: string;
  status: string;
  startTime: string;
  closeTime: string | null;
}

export default function Runs() {
  const [runs, setRuns] = useState<Run[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<any>(null);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [streamEvents, setStreamEvents] = useState<any[]>([]);
  const [streaming, setStreaming] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/runs?limit=50');
      setRuns(res.data.runs || []);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);
  useEffect(() => {
    if (!autoRefresh) return;
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [autoRefresh]);

  const showDetail = async (id: string) => {
    setSelected(id);
    try {
      const res = await axios.get(`/api/runs/${id}`);
      setDetail(res.data);
    } catch (e) {
      console.error(e);
    }
  };

  const startStream = (id: string) => {
    setStreamEvents([]);
    setStreaming(true);
    const es = new EventSource(`/api/runs/${id}/stream`);
    es.onmessage = (e) => {
      try {
        const data = JSON.parse(e.data);
        setStreamEvents((prev) => [...prev, data]);
      } catch {}
    };
    es.addEventListener('complete', (e: any) => {
      try {
        const data = JSON.parse(e.data);
        setDetail(data);
      } catch {}
      es.close();
      setStreaming(false);
    });
    es.onerror = () => {
      es.close();
      setStreaming(false);
    };
  };

  const terminate = async (id: string) => {
    if (!window.confirm(`Run ${id} wirklich beenden?`)) return;
    try {
      await axios.post(`/api/runs/${id}/terminate`);
      load();
    } catch (e: any) {
      console.error(e);
      alert(e?.response?.data?.error || 'Beenden fehlgeschlagen');
    }
  };

  const statusColor = (s: string) => {
    if (s === 'COMPLETED') return 'text-green-400';
    if (s === 'RUNNING') return 'text-blue-400';
    if (s === 'FAILED') return 'text-red-400';
    return 'text-gray-400';
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Runs</h1>
      <button onClick={load} className="px-4 py-2 bg-gray-700 rounded text-sm mb-4">Aktualisieren</button>
      <button onClick={() => setAutoRefresh(!autoRefresh)} className={`px-4 py-2 rounded text-sm mb-4 ml-2 ${autoRefresh ? 'bg-green-600' : 'bg-gray-700'}`}>
        Auto-Refresh: {autoRefresh ? 'AN' : 'AUS'}
      </button>
      {loading ? (
        <p className="text-gray-400">Lade...</p>
      ) : runs.length === 0 ? (
        <p className="text-gray-400">Keine Runs vorhanden.</p>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="space-y-2">
            {runs.map((r) => (
              <div
                key={r.workflowId}
                onClick={() => showDetail(r.workflowId)}
                className={`p-3 bg-gray-800 rounded cursor-pointer hover:bg-gray-700 ${selected === r.workflowId ? 'ring-2 ring-blue-500' : ''}`}
              >
                <div className="flex justify-between">
                  <span className="font-mono text-sm text-blue-400">{r.workflowId}</span>
                  <span className={`text-sm font-bold ${statusColor(r.status)}`}>{r.status}</span>
                </div>
                <div className="text-xs text-gray-400 mt-1">
                  {r.workflowType} · {r.startTime}
                </div>
                {r.status === 'RUNNING' && (
                  <button
                    onClick={(e) => { e.stopPropagation(); terminate(r.workflowId); }}
                    className="mt-2 px-2 py-1 bg-red-600 rounded text-xs"
                  >
                    Terminate
                  </button>
                )}
              </div>
            ))}
          </div>
          <div>
            {detail ? (
              <div className="bg-gray-800 rounded p-4">
                <div className="flex justify-between items-center mb-2">
                  <h3 className="font-bold">Detail</h3>
                  <button
                    onClick={() => startStream(detail.workflowId)}
                    disabled={streaming}
                    className="px-2 py-1 bg-blue-600 rounded text-xs"
                  >
                    {streaming ? 'Streaming...' : 'Live Stream'}
                  </button>
                </div>
                <pre className="text-xs text-green-400 overflow-auto max-h-96">
                  {JSON.stringify(detail, null, 2)}
                </pre>
                {streamEvents.length > 0 && (
                  <div className="mt-4">
                    <h4 className="font-bold text-sm mb-1">Live Events ({streamEvents.length})</h4>
                    <div className="max-h-48 overflow-auto text-xs font-mono space-y-1">
                      {streamEvents.map((e, i) => (
                        <div key={i} className="text-gray-300">
                          [{e.eventId}] {e.type} {e.detail ? `- ${e.detail}` : ''}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <p className="text-gray-400">Run auswählen für Details.</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
