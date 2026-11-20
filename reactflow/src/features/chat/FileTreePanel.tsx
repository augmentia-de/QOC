import { useEffect, useState } from 'react';
import { Files } from '../../services/qocApi';

interface Entry { name: string; path: string; relative: string; directory: boolean; size: number }

interface Props {
  path: string;
  onPathChange: (path: string) => void;
  onOpenFile: (path: string) => void;
  selected: string;
}

export function FileTreePanel({ path, onPathChange, onOpenFile, selected }: Props) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');

  const load = async (p: string) => {
    setLoading(true); setError('');
    try {
      const data = await Files.list(p);
      const list = Array.isArray(data?.data) ? (data.data as Entry[]) : [];
      setEntries(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setLoading(false); }
  };

  useEffect(() => { void load(path); }, [path]);

  const newFile = async () => {
    const name = window.prompt('Dateiname (relativ zum aktuellen Pfad):');
    if (!name) return;
    const full = path === '.' ? name : path + '/' + name;
    try { await Files.create(full, ''); await load(path); onOpenFile(full); }
    catch (e) { window.alert('Anlegen fehlgeschlagen: ' + (e instanceof Error ? e.message : String(e))); }
  };

  const uploadFiles = async (files: FileList | null) => {
    if (!files) return;
    for (const f of Array.from(files)) {
      const full = path === '.' ? f.name : path + '/' + f.name;
      try {
        const buf = await f.arrayBuffer();
        await Files.upload(full, buf);
      } catch (e) {
        window.alert('Upload fehlgeschlagen (' + f.name + '): ' + (e instanceof Error ? e.message : String(e)));
      }
    }
    await load(path);
  };

  const del = async (entry: Entry) => {
    if (!window.confirm('Löschen: ' + entry.relative + '?')) return;
    try { await Files.delete(entry.path); await load(path); }
    catch (e) { window.alert('Löschen fehlgeschlagen: ' + (e instanceof Error ? e.message : String(e))); }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ display: 'flex', gap: 4, marginBottom: 6, flexWrap: 'wrap' }}>
        <button onClick={() => void load(path)} title="Aktualisieren" style={btnStyle}>↻</button>
        <button onClick={() => void newFile()} title="Neue Datei" style={btnStyle}>+</button>
        <label style={btnStyle} title="Dateien hochladen">
          ⤒
          <input type="file" multiple hidden onChange={(e) => { void uploadFiles(e.target.files); e.target.value = ''; }} />
        </label>
        <label style={btnStyle} title="Ordner hochladen">
          ⇪
          <input type="file" {...({ webkitdirectory: '' } as any)} multiple hidden onChange={(e) => { void uploadFiles(e.target.files); e.target.value = ''; }} />
        </label>
      </div>
      <div style={{ fontSize: 11, color: '#999', marginBottom: 4, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {path}
      </div>
      {loading ? <p style={{ color: '#999', fontSize: 12 }}>Lade…</p> : null}
      {error ? <p style={{ color: '#f85149', fontSize: 12 }}>{error}</p> : null}
      <div className="chat-filetree">
        {path !== '.' && path !== '' ? (
          <div className="entry" onClick={() => {
            const parts = path.split('/').filter(Boolean);
            parts.pop();
            const joined = parts.join('/');
            onPathChange(joined ? (path.startsWith('/') ? '/' + joined : joined) : '.');
          }}>
            <span>..</span>
          </div>
        ) : null}
        {entries.map((entry) => (
          <div key={entry.path} className={'entry' + (entry.path === selected ? ' selected' : '')} onClick={() => {
            if (entry.directory) onPathChange(entry.path);
            else onOpenFile(entry.path);
          }}>
            <span className={entry.directory ? 'dir' : 'file'} />
            <span className="name">{entry.name}</span>
            <span className="actions">
              {!entry.directory ? (
                <button title="Löschen" onClick={(e) => { e.stopPropagation(); void del(entry); }}>🗑</button>
              ) : null}
            </span>
          </div>
        ))}
        {entries.length === 0 && !loading ? <p style={{ color: '#999', fontSize: 12 }}>Leer.</p> : null}
      </div>
    </div>
  );
}

const btnStyle: React.CSSProperties = {
  background: '#0b0d12',
  border: '1px solid #333',
  color: '#d4d4d4',
  cursor: 'pointer',
  borderRadius: 4,
  padding: '2px 8px',
  fontSize: 12,
};