import { useState } from 'react';
import { QocApi } from '../services/qocApi.ts';

export default function Generate() {
  const [prompt, setPrompt] = useState('');
  const [generating, setGenerating] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const inp = { width: '100%', padding: '8px 10px', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, fontSize: 13 };

  const generate = async () => {
    if (!prompt.trim()) return;
    setGenerating(true); setResult(null);
    try {
      const data = await QocApi.workflows.generate(prompt.trim());
      setResult(`Workflow erstellt: ${data.id || data.workflowId || 'OK'}`);
    } catch (e: any) {
      console.error(e);
      setResult('Generierung fehlgeschlagen: ' + (e?.message || e));
    } finally { setGenerating(false); }
  };

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 8 }}>Generate</h1>
      <p style={{ color: '#999', marginBottom: 12, fontSize: 13 }}>Workflow aus Prompt generieren</p>
      <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="Describe the workflow you want to create..." style={{ ...inp, height: 120, resize: 'vertical', marginBottom: 12 }} />
      <button onClick={generate} disabled={generating || !prompt.trim()} style={{ padding: '8px 16px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13, opacity: generating || !prompt.trim() ? 0.5 : 1 }}>
        {generating ? 'Generiere...' : 'Generieren'}
      </button>
      {result && <p style={{ marginTop: 12, color: result.startsWith('Workflow') ? '#5cb85c' : '#d9534f', fontSize: 13 }}>{result}</p>}
    </div>
  );
}
