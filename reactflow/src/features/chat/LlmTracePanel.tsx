import { useEffect, useState } from 'react';
import { LlmTrace } from '../../services/qocApi';

interface Props { active: boolean }

export function LlmTracePanel({ active }: Props) {
  const [text, setText] = useState<string>('');
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const data = await LlmTrace.get(50000, 50000);
      const raw = (data?.data ?? data?.text ?? '') as string;
      setText(typeof raw === 'string' ? raw : JSON.stringify(raw));
    } catch (e) {
      setText('Fehler: ' + (e instanceof Error ? e.message : String(e)));
    } finally { setLoading(false); }
  };

  useEffect(() => { if (active) void load(); }, [active]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ display: 'flex', gap: 4, marginBottom: 6 }}>
        <button onClick={() => void load()} style={btnStyle} title="Aktualisieren">↻</button>
        {loading ? <span style={{ color: '#999', fontSize: 11 }}>Lade…</span> : null}
      </div>
      <div className="chat-llm">{text || 'Keine LLM-Traces.'}</div>
    </div>
  );
}

const btnStyle: React.CSSProperties = { background: '#0b0d12', border: '1px solid #333', color: '#d4d4d4', cursor: 'pointer', borderRadius: 4, padding: '2px 8px', fontSize: 12 };