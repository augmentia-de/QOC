import { useEffect, useState } from 'react';

export default function Observe() {
  const [runs, setRuns] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/workflows/runs?limit=100', { headers: { 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') } });
      const data = await res.json();
      setRuns(Array.isArray(data.runs) ? data.runs : []);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const statusColor = (s: string) => s === 'COMPLETED' ? '#5cb85c' : s === 'RUNNING' ? '#5bc0de' : s === 'PAUSED' ? '#f0ad4e' : s === 'FAILED' ? '#d9534f' : '#555';

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 4 }}>Observe</h1>
      <p style={{ color: '#999', fontSize: 13, marginBottom: 12 }}>Live monitoring of all runs</p>
<button onClick={load} style={{ marginBottom: 12, padding: '4px 10px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Aktualisieren</button>
      {loading ? <p style={{ color: '#999' }}>Loading...</p> : runs.length === 0 ? <p style={{ color: '#999' }}>Keine Runs vorhanden.</p> : (
        <div style={{ display: 'grid', gap: 6 }}>
          {runs.map((r: any) => (
            <div key={r.runId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: 10, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333' }}>
              <div style={{ width: 10, height: 10, borderRadius: '50%', background: statusColor(r.status) }} />
              <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#5bc0de', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.runId}</span>
              <span style={{ fontSize: 11, color: '#555' }}>{r.workflowId}</span>
              <span style={{ fontSize: 11, color: '#555' }}>{r.currentNodeId}</span>
              <span style={{ fontSize: 12, color: '#999' }}>{r.status}</span>
              <span style={{ fontSize: 11, color: '#555' }}>{r.startedAt}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
