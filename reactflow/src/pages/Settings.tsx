import { useEffect, useState } from 'react';
import { QocApi } from '../services/qocApi.ts';

const STORAGE_KEY = 'qoc-ui-settings';
const DEFAULTS = { backendUrl: `${window.location.origin}/api`, theme: 'Dark' };

export default function Settings() {
  const [backendUrl, setBackendUrl] = useState(DEFAULTS.backendUrl);
  const [theme, setTheme] = useState(DEFAULTS.theme);
  const [saved, setSaved] = useState(false);

  // MCP setup + tool preview
  const [servers, setServers] = useState<any[]>([]);
  const [newName, setNewName] = useState('');
  const [newType, setNewType] = useState('remote');
  const [newUrl, setNewUrl] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [serverTools, setServerTools] = useState<Record<string, any[]>>({});

  const loadServers = async () => {
    try {
      const res = await QocApi.mcpClient.listServers();
      setServers(Array.isArray(res.servers) ? res.servers : []);
    } catch { setServers([]); }
  };

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const stored = JSON.parse(raw);
        if (stored.backendUrl) setBackendUrl(stored.backendUrl);
        if (stored.theme) setTheme(stored.theme);
      }
    } catch { /* ignore */ }
    loadServers();
  }, []);

  const save = () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ backendUrl, theme }));
    document.documentElement.dataset.theme = theme.toLowerCase();
    setSaved(true);
    window.setTimeout(() => setSaved(false), 2500);
  };

  const [reloadingConfig, setReloadingConfig] = useState(false);

  const reset = () => {
    localStorage.removeItem(STORAGE_KEY);
    setBackendUrl(DEFAULTS.backendUrl);
    setTheme(DEFAULTS.theme);
    setSaved(false);
  };

  const reloadConfig = async () => {
    setReloadingConfig(true);
    try {
      await QocApi.config.reload();
      alert('Config reloaded');
    } catch (e) {
      console.error(e);
      alert('Config reload failed: ' + (e instanceof Error ? e.message : String(e)));
    } finally {
      setReloadingConfig(false);
    }
  };

  const addServer = async () => {
    if (!newName.trim()) return;
    try {
      const config: any = { name: newName, type: newType };
      if (newType === 'remote') config.url = newUrl;
      else config.command = newUrl.split(' ');
      await QocApi.mcpClient.addServer(config);
      setNewName(''); setNewUrl(''); loadServers();
    } catch (e) { console.error(e); alert('Add failed'); }
  };

  const remove = async (name: string) => {
    try { await QocApi.mcpClient.removeServer(name); loadServers(); }
    catch (e) { console.error(e); }
  };

  const reload = async (name: string) => {
    try { await QocApi.mcpClient.reloadServer(name); loadServers(); }
    catch (e) { console.error(e); }
  };

  const toggleExpand = async (name: string) => {
    if (expanded === name) { setExpanded(null); return; }
    setExpanded(name);
    if (!serverTools[name]) {
      try {
        const res = await QocApi.mcpClient.getServerTools(name);
        setServerTools((prev) => ({ ...prev, [name]: Array.isArray(res.tools) ? res.tools : [] }));
      } catch { setServerTools((prev) => ({ ...prev, [name]: [] })); }
    }
  };

  const inp = { width: '100%', padding: '6px 10px', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, fontSize: 13 };

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 16 }}>Settings</h1>

      <div style={{ background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', padding: 16, marginBottom: 20, maxWidth: 480 }}>
        <h2 style={{ fontSize: 14, color: '#5bc0de', marginBottom: 10 }}>UI Settings</h2>
        <div style={{ display: 'grid', gap: 10 }}>
          <div>
            <label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Backend URL (Anzeige)</label>
            <input value={backendUrl} onChange={(e) => setBackendUrl(e.target.value)} style={inp} />
          </div>
          <div>
            <label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 2 }}>Theme</label>
            <select value={theme} onChange={(e) => setTheme(e.target.value)} style={inp}>
              <option value="Dark">Dark</option>
              <option value="Light">Light</option>
            </select>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button onClick={save} style={{ padding: '6px 14px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Save</button>
            <button onClick={reset} style={{ padding: '6px 14px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Reset</button>
            <button onClick={reloadConfig} disabled={reloadingConfig} style={{ padding: '6px 14px', background: '#f0ad4e', color: '#000', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13, opacity: reloadingConfig ? 0.5 : 1 }}>Reload Config</button>
            {saved && <span style={{ color: '#5cb85c', fontSize: 12 }}>Saved.</span>}
          </div>
        </div>
      </div>

      <div style={{ background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', padding: 16, marginBottom: 20 }}>
        <h2 style={{ fontSize: 14, color: '#5bc0de', marginBottom: 10 }}>MCP Servers (Setup + Tool Preview)</h2>
        <div style={{ display: 'grid', gap: 8, marginBottom: 12 }}>
          {servers.map((s: any) => (
            <div key={s.name} style={{ padding: 10, background: '#161616', borderRadius: 4, border: '1px solid #333' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div>
                  <span style={{ fontWeight: 'bold' }}>{s.name}</span>
                  <span style={{ marginLeft: 8, fontSize: 11, color: s.status === 'CONNECTED' ? '#5cb85c' : s.status === 'FAILED' ? '#d9534f' : '#f0ad4e' }}>{s.status}</span>
                  <span style={{ marginLeft: 8, fontSize: 11, color: '#999' }}>({s.toolCount} tools)</span>
                  {s.error && <div style={{ fontSize: 11, color: '#d9534f', marginTop: 2 }}>{s.error}</div>}
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button onClick={() => toggleExpand(s.name)} style={{ padding: '3px 8px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>{expanded === s.name ? 'Hide' : 'Tools'}</button>
                  <button onClick={() => reload(s.name)} style={{ padding: '3px 8px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Reload</button>
                  <button onClick={() => remove(s.name)} style={{ padding: '3px 8px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Remove</button>
                </div>
              </div>
              {expanded === s.name && (
                <div style={{ marginTop: 8, borderTop: '1px solid #333', paddingTop: 8 }}>
                  {(serverTools[s.name] || []).map((t: any) => (
                    <div key={t.name} style={{ fontSize: 11, padding: '2px 0' }}>
                      <span style={{ fontFamily: 'monospace', color: '#c084fc' }}>{t.name}</span>
                      <span style={{ color: '#999', marginLeft: 8 }}>{t.description}</span>
                    </div>
                  ))}
                  {(serverTools[s.name] || []).length === 0 && <div style={{ fontSize: 11, color: '#555' }}>No tools discovered</div>}
                </div>
              )}
            </div>
          ))}
          {servers.length === 0 && <div style={{ color: '#555', fontSize: 13 }}>No MCP servers configured</div>}
        </div>

        <div style={{ display: 'grid', gap: 8, maxWidth: 400 }}>
          <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="Server name" style={inp} />
          <select value={newType} onChange={(e) => setNewType(e.target.value)} style={inp}>
            <option value="remote">Remote (HTTP/SSE)</option>
            <option value="local">Local (stdio)</option>
          </select>
          <input value={newUrl} onChange={(e) => setNewUrl(e.target.value)} placeholder={newType === 'remote' ? 'https://...' : 'command arg1 arg2'} style={inp} />
          <button onClick={addServer} style={{ padding: '6px 14px', background: '#27ae60', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13, justifySelf: 'start' }}>Add</button>
        </div>
      </div>
    </div>
  );
}
