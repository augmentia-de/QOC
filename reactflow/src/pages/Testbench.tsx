import { useState } from 'react';
import { QocApi } from '../services/qocApi.ts';

export default function Testbench() {
  const [workflowId, setWorkflowId] = useState('');
  const [input, setInput] = useState('');
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<any>(null);
  const inp = { width: '100%', padding: '8px 10px', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, fontSize: 13 };

  const run = async () => {
    if (!workflowId.trim()) return;
    setRunning(true); setResult(null);
    try {
      const wf = await QocApi.workflows.get(workflowId.trim());
      const res = await QocApi.workflows.run(wf, input);
      setResult(res);
    } catch (e) { console.error(e); setResult({ error: 'Execution failed' }); }
    finally { setRunning(false); }
  };

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 4 }}>Testbench</h1>
      <p style={{ color: '#999', fontSize: 13, marginBottom: 12 }}>Test and run workflow</p>
      <div style={{ display: 'grid', gap: 10, maxWidth: 600 }}>
        <input value={workflowId} onChange={(e) => setWorkflowId(e.target.value)} placeholder="Workflow ID" style={inp} />
        <textarea value={input} onChange={(e) => setInput(e.target.value)} placeholder="Input for the workflow" style={{ ...inp, height: 80, resize: 'vertical' }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <button onClick={run} disabled={running || !workflowId.trim()} style={{ padding: '8px 16px', background: '#27ae60', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13, opacity: running || !workflowId.trim() ? 0.5 : 1 }}>
            {running ? 'Running...' : 'Run'}
          </button>
          {!workflowId.trim() && <span style={{ fontSize: 11, color: '#999' }}>Workflow ID erforderlich</span>}
        </div>
        {result && <pre style={{ background: '#161616', padding: 12, borderRadius: 4, fontSize: 11, color: '#5cb85c', overflow: 'auto', maxHeight: 300 }}>{JSON.stringify(result, null, 2)}</pre>}
      </div>
    </div>
  );
}