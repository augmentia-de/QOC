import { useState, useEffect } from 'react';
import { QocApi } from '../services/qocApi.ts';

const inp = { width: '100%', padding: '6px 10px', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, fontSize: 13 };

export default function McpServers() {
  const [servers, setServers] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [newName, setNewName] = useState('');
  const [newType, setNewType] = useState('remote');
  const [newUrl, setNewUrl] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [serverTools, setServerTools] = useState<Record<string, any[]>>({});

  const fetchServers = async () => {
    setLoading(true);
    try {
      const res = await QocApi.mcpClient.listServers();
      setServers(Array.isArray(res.servers) ? res.servers : []);
    } catch { setServers([]); }
    finally { setLoading(false); }
  };

  useEffect(() => {
    fetchServers();
    const interval = setInterval(fetchServers, 30000);
    return () => clearInterval(interval);
  }, []);

  const reload = async (name: string) => {
    try { await QocApi.mcpClient.reloadServer(name); await fetchServers(); }
    catch (e) { console.error(e); }
  };

  const remove = async (name: string) => {
    try { await QocApi.mcpClient.removeServer(name); await fetchServers(); }
    catch (e) { console.error(e); }
  };

  const addServer = async () => {
    if (!newName.trim()) return;
    try {
      const config: any = { name: newName, type: newType };
      if (newType === 'remote') config.url = newUrl;
      else config.command = newUrl.split(' ');
      await QocApi.mcpClient.addServer(config);
      setNewName(''); setNewUrl(''); await fetchServers();
    } catch (e) { console.error(e); alert('Add failed'); }
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

  const statusColor = (s: string) => s === 'CONNECTED' ? '#5cb85c' : s === 'FAILED' ? '#d9534f' : s === 'DISABLED' ? '#555' : '#f0ad4e';

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 16 }}>MCP Servers</h1>
      <button onClick={fetchServers} disabled={loading} style={{ marginBottom: 12, padding: '6px 12px', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>
        {loading ? 'Loading...' : 'Refresh'}
      </button>

      <div style={{ display: 'grid', gap: 8, marginBottom: 20 }}>
        {servers.map((s: any) => (
          <div key={s.name} style={{ background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', padding: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div>
                <span style={{ fontWeight: 'bold' }}>{s.name}</span>
                <span style={{ marginLeft: 8, fontSize: 11, color: statusColor(s.status) }}>{s.status}</span>
                <span style={{ marginLeft: 8, fontSize: 11, color: '#999' }}>({s.toolCount} tools)</span>
                {s.error && <div style={{ fontSize: 11, color: '#d9534f', marginTop: 4 }}>{s.error}</div>}
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <button onClick={() => toggleExpand(s.name)} style={{ padding: '4px 8px', background: '#555', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>{expanded === s.name ? 'Hide' : 'Tools'}</button>
                <button onClick={() => reload(s.name)} style={{ padding: '4px 8px', background: '#337ab7', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Reload</button>
                <button onClick={() => remove(s.name)} style={{ padding: '4px 8px', background: '#c0392b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 11 }}>Remove</button>
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

      <div style={{ background: '#1e1e1e', borderRadius: 6, border: '1px solid #333', padding: 16 }}>
        <h2 style={{ fontSize: 14, color: '#5bc0de', marginBottom: 10 }}>Add Server</h2>
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
