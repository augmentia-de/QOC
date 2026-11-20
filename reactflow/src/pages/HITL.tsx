import { useEffect, useState } from 'react';

interface Run { runId: string; sessionId: string; workflowId: string; status: string; startedAt: string; }
interface Permission { id: string; sessionId: string; action: string; resources: string[]; message?: string; }

export default function HITL() {
  const [runs, setRuns] = useState<Run[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<Run | null>(null);
  const [pending, setPending] = useState<Permission[]>([]);
  const [loadingPending, setLoadingPending] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/workflows/runs?limit=50', { headers: { 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') } });
      const text = await res.text();
      const data = text ? JSON.parse(text) : {};
      setRuns(Array.isArray(data.runs) ? data.runs : []);
    } catch (e) { console.error(e); setRuns([]); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const loadPending = async (run: Run) => {
    setSelected(run); setPending([]);
    if (!run.sessionId) return;
    setLoadingPending(true);
    try {
      const res = await fetch(`/api/session/${encodeURIComponent(run.sessionId)}/permission?includeChildren=true`, { headers: { 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') } });
      const text = await res.text();
      const data = text ? JSON.parse(text) : {};
      setPending(Array.isArray(data.data) ? data.data : (Array.isArray(data) ? data : []));
    } catch (e) { console.error(e); setPending([]); }
    finally { setLoadingPending(false); }
  };

  const decide = async (perm: Permission, approved: boolean) => {
    try {
      await fetch(`/api/session/${encodeURIComponent(perm.sessionId)}/permission/${encodeURIComponent(perm.id)}/reply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-QOC-User': (localStorage.getItem('qoc-user') || 'dev') },
        body: JSON.stringify({ reply: approved ? 'ONCE' : 'REJECT' }),
      });
      if (selected) await loadPending(selected);
      load();
    } catch (e) { console.error(e); alert('Entscheidung fehlgeschlagen'); }
  };

  const btn = (bg: string) => ({ padding: '4px 10px', background: bg, color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 });

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 4 }}>HITL (Human-in-the-Loop)</h1>
      <p style={{ color: '#999', fontSize: 13, marginBottom: 12 }}>Select a run to handle pending permissions.</p>
      {loading ? <p style={{ color: '#999' }}>Loading...</p> : runs.length === 0 ? <p style={{ color: '#999' }}>No runs available.</p> : (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div style={{ display: 'grid', gap: 6 }}>
            {runs.map((r) => (
              <div key={r.runId} onClick={() => loadPending(r)} style={{ padding: 10, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', cursor: 'pointer', outline: selected?.runId === r.runId ? '2px solid #337ab7' : 'none' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ fontFamily: 'monospace', fontSize: 12, color: '#5bc0de' }}>{r.runId}</span>
                  <span style={{ fontSize: 12, color: '#999' }}>{r.status}</span>
                </div>
                <div style={{ fontSize: 11, color: '#555', marginTop: 4 }}>{r.workflowId} · {r.startedAt}</div>
              </div>
            ))}
          </div>
          <div>
            {!selected ? <p style={{ color: '#999' }}>Select a run.</p> : loadingPending ? <p style={{ color: '#999' }}>Loading permissions...</p> : pending.length === 0 ? <p style={{ color: '#999' }}>Keine ausstehenden Entscheidungen.</p> : (
              <div style={{ display: 'grid', gap: 6 }}>
                {pending.map((p) => (
                  <div key={p.id} style={{ padding: 12, background: '#1e1e1e', borderRadius: 6, border: '1px solid #333' }}>
                    <div style={{ fontSize: 13, fontWeight: 'bold', color: '#f0ad4e' }}>{p.action}</div>
                    <div style={{ fontSize: 11, color: '#d4d4d4', marginTop: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{(p.resources || []).join('\n')}</div>
                    {p.message && <div style={{ fontSize: 11, color: '#555', marginTop: 4 }}>{p.message}</div>}
                    <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                      <button onClick={() => decide(p, true)} style={btn('#27ae60')}>Genehmigen</button>
                      <button onClick={() => decide(p, false)} style={btn('#c0392b')}>Ablehnen</button>
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