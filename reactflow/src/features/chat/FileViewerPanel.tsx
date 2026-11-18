import { useEffect, useState } from 'react';
import { Files } from '../../services/qocApi';

interface Props {
  filePath: string | null;
}

export function FileViewerPanel({ filePath }: Props) {
  const [content, setContent] = useState<string>('');
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<string>('');
  const [truncated, setTruncated] = useState(false);
  const [status, setStatus] = useState<string>('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!filePath) { setContent(''); setEditing(false); return; }
    void load(filePath);
  }, [filePath]);

  const load = async (p: string) => {
    setLoading(true); setStatus('');
    try {
      const data = await Files.read(p);
      const d = (data?.data ?? {}) as { content?: string; truncated?: boolean };
      setContent(d.content ?? '');
      setTruncated(Boolean(d.truncated));
      setDraft(d.content ?? '');
    } catch (e) {
      setContent('Fehler: ' + (e instanceof Error ? e.message : String(e)));
    } finally { setLoading(false); }
  };

  const save = async () => {
    if (!filePath) return;
    setStatus('Speichere…');
    try {
      await Files.create(filePath, draft);
      setContent(draft);
      setEditing(false);
      setStatus('Gespeichert');
    } catch (e) {
      setStatus('Fehler: ' + (e instanceof Error ? e.message : String(e)));
    }
  };

  const remove = async () => {
    if (!filePath) return;
    if (!window.confirm('Löschen: ' + filePath + '?')) return;
    try { await Files.delete(filePath); setContent(''); setEditing(false); setStatus('Gelöscht'); }
    catch (e) { setStatus('Fehler: ' + (e instanceof Error ? e.message : String(e))); }
  };

  const fileName = filePath ? filePath.split('/').pop() : '';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 6, flexWrap: 'wrap' }}>
        <span style={{ color: '#9aa0a6', fontSize: 12, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {fileName || 'Keine Datei'}
        </span>
        {filePath && !editing ? (
          <button onClick={() => { setEditing(true); setDraft(content); }} style={btnStyle} title="Bearbeiten">✎</button>
        ) : null}
        {filePath && !editing ? (
          <button onClick={() => void remove()} style={btnStyle} title="Löschen">🗑</button>
        ) : null}
      </div>
      {loading ? <p style={{ color: '#999', fontSize: 12 }}>Lade…</p> : null}
      {!editing ? (
        <div className="chat-viewer">
          <pre>{content || 'Datei auswählen…'}</pre>
          {truncated ? <p style={{ color: '#d29922', fontSize: 11 }}>… gekürzt</p> : null}
        </div>
      ) : (
        <>
          <textarea className="chat-editor" value={draft} onChange={(e) => setDraft(e.target.value)} />
          <div style={{ display: 'flex', gap: 6, marginTop: 6, alignItems: 'center' }}>
            <button onClick={() => void save()} style={primaryBtn}>Save</button>
            <button onClick={() => { setEditing(false); setDraft(content); setStatus(''); }} style={btnStyle}>Cancel</button>
            <span style={{ color: '#9aa0a6', fontSize: 11 }}>{status}</span>
          </div>
        </>
      )}
    </div>
  );
}

const btnStyle: React.CSSProperties = { background: '#0b0d12', border: '1px solid #333', color: '#d4d4d4', cursor: 'pointer', borderRadius: 4, padding: '2px 8px', fontSize: 12 };
const primaryBtn: React.CSSProperties = { ...btnStyle, background: '#337ab7', border: 'none' };