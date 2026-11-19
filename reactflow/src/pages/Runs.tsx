import { useEffect, useRef, useState } from 'react';

interface Run { runId: string; sessionId: string; workflowId: string; status: string; currentNodeId: string; startedAt: string; updatedAt: string; error?: string; active?: boolean; }

export default function Runs() {
  const [runs, setRuns] = useState<Run[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<Run | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [streamEvents, setStreamEvents] = useState<any[]>([]);
  const [streaming, setStreaming] = useState(false);
  const streamRef = useRef<EventSource | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/workflows/runs?limit=50', { headers: { 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') } });
      const data = await res.json();
      setRuns(Array.isArray(data.runs) ? data.runs : []);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);
  useEffect(() => {
    if (!autoRefresh) return;
    let t: ReturnType<typeof setInterval> | null = null;
    const start = () => { if (t === null && document.visibilityState === 'visible') t = setInterval(load, 3000); };
    const stop = () => { if (t !== null) { clearInterval(t); t = null; } };
    const onVis = () => { if (document.visibilityState === 'visible') { load(); start(); } else stop(); };
    start();
    document.addEventListener('visibilitychange', onVis);
    return () => { stop(); document.removeEventListener('visibilitychange', onVis); };
  }, [autoRefresh]);
  useEffect(() => () => streamRef.current?.close(), []);

  const showDetail = async (runId: string) => {
    setSelected(runId);
    try {
      const res = await fetch(`/api/workflows/runs/${encodeURIComponent(runId)}`, { headers: { 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') } });
      setDetail(await res.json());
    } catch (e) { console.error(e); }
  };

  const startStream = (run: Run) => {
    streamRef.current?.close();
    setStreamEvents([]); setStreaming(true);
    const es = new EventSource(`/api/event?session=${encodeURIComponent(run.sessionId)}`);
    streamRef.current = es;
    es.onmessage = (e) => { try { setStreamEvents((prev) => [...prev, JSON.parse(e.data)]); } catch { setStreamEvents((prev) => [...prev, { raw: e.data }]); } };
    es.onerror = () => { es.close(); setStreaming(false); };
  };
  const stopStream = () => { streamRef.current?.close(); streamRef.current = null; setStreaming(false); };

  const terminate = async (run: Run) => {
    if (!window.confirm(`Run ${run.runId} wirklich beenden?`)) return;
    try {
      await fetch(`/api/session/${encodeURIComponent(run.sessionId)}/interrupt`, { method: 'POST', headers: { 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') } });
      load();
    } catch (e: any) { console.error(e); alert('Beenden fehlgeschlagen'); }
  };

  const statusColor = (s: string) => s === 'COMPLETED' ? '#5cb85c' : s === 'RUNNING' ? '#5bc0de' : s === 'FAILED' ? '#d9534f' : s === 'PAUSED' ? '#f0ad4e' : '#999';

  const btn = (bg: string) => ({ padding: '6px 12px', background: bg, color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 });

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 12 }}>Runs</h1>
      <div style={{ marginBottom: 12 }}>
        <button onClick={load} disabled={autoRefresh} style={{ ...btn('#555'), opacity: autoRefresh ? 0.5 : 1, cursor: autoRefresh ? 'not-allowed' : 'pointer' }}>Aktualisieren</button>
        <button onClick={() => setAutoRefresh(!autoRefresh)} style={{ ...btn(autoRefresh ? '#27ae60' : '#555'), marginLeft: 8 }}>Auto-Refresh: {autoRefresh ? 'AN' : 'AUS'}</button>
      </div>
      {loading ? <p style={{ color: '#999' }}>Lade...</p> : runs.length === 0 ? <p style={{ color: '#999' }}>Keine Runs vorhanden.</p> : (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div style={{ display: 'grid', gap: 6 }}>
            {runs.map((r) => (
              <div key={r.runId} onClick={() => showDetail(r.runId)} style={{ padding: 10, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', cursor: 'pointer', outline: selected === r.runId ? '2px solid #337ab7' : 'none' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#5bc0de' }}>{r.runId}</span>
                  <span style={{ fontSize: 12, fontWeight: 'bold', color: statusColor(r.status) }}>{r.status}</span>
                </div>
                <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>{r.workflowId} · {r.currentNodeId || '—'} · {r.startedAt}</div>
                {(r.status === 'RUNNING' || r.active) && (
                  <button onClick={(e) => { e.stopPropagation(); terminate(r); }} style={{ ...btn('#c0392b'), marginTop: 6, fontSize: 11 }}>Terminate</button>
                )}
              </div>
            ))}
          </div>
          <div>
            {detail ? (
              <div style={{ background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', padding: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <h3 style={{ fontSize: 14, color: '#5bc0de' }}>Detail</h3>
                  {streaming
                    ? <button onClick={stopStream} style={btn('#c0392b')}>Stop Stream</button>
                    : <button onClick={() => startStream(detail)} disabled={!detail.sessionId} style={{ ...btn('#337ab7'), opacity: !detail.sessionId ? 0.5 : 1 }}>Live Stream</button>}
                </div>
                <pre style={{ fontSize: 11, color: '#5cb85c', overflow: 'auto', maxHeight: 300, background: '#161616', padding: 8, borderRadius: 4 }}>{JSON.stringify(detail, null, 2)}</pre>
                {streamEvents.length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <h4 style={{ fontSize: 12, color: '#5bc0de', marginBottom: 4 }}>Live Events ({streamEvents.length})</h4>
                    <div style={{ maxHeight: 180, overflow: 'auto', fontSize: 11, fontFamily: 'monospace' }}>
                      {streamEvents.map((e, i) => (
                        <div key={i} style={{ color: '#d4d4d4' }}>
                          <span style={{ color: '#5bc0de' }}>{e.type || e.eventType || 'event'}</span> {JSON.stringify(e)}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ) : <p style={{ color: '#999' }}>Select a run for details.</p>}
          </div>
        </div>
      )}
    </div>
  );
}
