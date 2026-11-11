import { useState, useEffect } from 'react';
import { McpClientApi } from '../services/api';

export default function McpServers() {
  const [servers, setServers] = useState<{ name: string; status: string; toolCount: number; error: string | null }[]>([]);
  const [loading, setLoading] = useState(false);
  const [newName, setNewName] = useState('');
  const [newType, setNewType] = useState('remote');
  const [newUrl, setNewUrl] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [serverTools, setServerTools] = useState<Record<string, { name: string; description: string }[]>>({});

  const fetchServers = async () => {
    setLoading(true);
    try {
      const res = await McpClientApi.listServers();
      setServers(res.data.servers || []);
    } catch {
      setServers([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchServers();
    const interval = setInterval(fetchServers, 30000);
    return () => clearInterval(interval);
  }, []);

  const reload = async (name: string) => {
    try {
      await McpClientApi.reloadServer(name);
      await fetchServers();
    } catch (e) {
      console.error(e);
    }
  };

  const remove = async (name: string) => {
    try {
      await McpClientApi.removeServer(name);
      await fetchServers();
    } catch (e) {
      console.error(e);
    }
  };

  const addServer = async () => {
    if (!newName.trim()) return;
    try {
      const config: Record<string, unknown> = { name: newName, type: newType };
      if (newType === 'remote') config.url = newUrl;
      else config.command = newUrl.split(' ');
      await McpClientApi.addServer(config);
      setNewName('');
      setNewUrl('');
      await fetchServers();
    } catch (e) {
      console.error(e);
    }
  };

  const toggleExpand = async (name: string) => {
    if (expanded === name) {
      setExpanded(null);
      return;
    }
    setExpanded(name);
    if (!serverTools[name]) {
      try {
        const res = await McpClientApi.getServerTools(name);
        setServerTools((prev) => ({ ...prev, [name]: res.data.tools }));
      } catch {
        setServerTools((prev) => ({ ...prev, [name]: [] }));
      }
    }
  };

  const statusColor = (s: string) => {
    if (s === 'CONNECTED') return 'text-green-400';
    if (s === 'FAILED') return 'text-red-400';
    if (s === 'DISABLED') return 'text-gray-500';
    return 'text-yellow-400';
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">MCP Servers</h1>
      <button onClick={fetchServers} className="mb-4 px-3 py-1 bg-gray-700 rounded text-sm" disabled={loading}>
        {loading ? 'Loading...' : 'Refresh'}
      </button>

      <div className="space-y-3 mb-6">
        {servers.map((s) => (
          <div key={s.name} className="bg-gray-800 rounded border border-gray-700 p-3">
            <div className="flex items-center justify-between">
              <div>
                <span className="font-semibold">{s.name}</span>
                <span className={`ml-2 text-xs ${statusColor(s.status)}`}>{s.status}</span>
                <span className="ml-2 text-xs text-gray-400">({s.toolCount} tools)</span>
                {s.error && <div className="text-xs text-red-400 mt-1">{s.error}</div>}
              </div>
              <div className="flex gap-2">
                <button onClick={() => toggleExpand(s.name)} className="px-2 py-1 bg-gray-700 rounded text-xs">
                  {expanded === s.name ? 'Hide' : 'Tools'}
                </button>
                <button onClick={() => reload(s.name)} className="px-2 py-1 bg-blue-600 rounded text-xs">Reload</button>
                <button onClick={() => remove(s.name)} className="px-2 py-1 bg-red-600 rounded text-xs">Remove</button>
              </div>
            </div>
            {expanded === s.name && (
              <div className="mt-2 border-t border-gray-700 pt-2">
                {(serverTools[s.name] || []).map((t) => (
                  <div key={t.name} className="text-xs py-0.5">
                    <span className="font-mono text-purple-300">{t.name}</span>
                    <span className="text-gray-400 ml-2">{t.description}</span>
                  </div>
                ))}
                {(serverTools[s.name] || []).length === 0 && (
                  <div className="text-xs text-gray-500">No tools discovered</div>
                )}
              </div>
            )}
          </div>
        ))}
        {servers.length === 0 && <div className="text-gray-500 text-sm">No MCP servers configured</div>}
      </div>

      <div className="bg-gray-800 rounded border border-gray-700 p-4">
        <h2 className="font-semibold mb-3">Add Server</h2>
        <div className="space-y-2 max-w-md">
          <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="Server name" className="w-full px-3 py-2 bg-gray-700 rounded text-sm" />
          <select value={newType} onChange={(e) => setNewType(e.target.value)} className="w-full px-3 py-2 bg-gray-700 rounded text-sm">
            <option value="remote">Remote (HTTP/SSE)</option>
            <option value="local">Local (stdio)</option>
          </select>
          <input value={newUrl} onChange={(e) => setNewUrl(e.target.value)} placeholder={newType === 'remote' ? 'https://...' : 'command arg1 arg2'} className="w-full px-3 py-2 bg-gray-700 rounded text-sm" />
          <button onClick={addServer} className="px-4 py-2 bg-green-600 rounded text-sm">Add</button>
        </div>
      </div>
    </div>
  );
}