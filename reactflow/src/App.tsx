import { Link, Routes, Route } from 'react-router-dom';
import { useIsMobile } from './hooks/useMediaQuery.ts';
import EditorPage from './EditorPage.tsx';
import ChatPage from './features/chat/ChatPage.tsx';
import Dashboard from './pages/Dashboard.tsx';
import Workflows from './pages/Workflows.tsx';
import Agents from './pages/Agents.tsx';
import McpServers from './pages/McpServers.tsx';
import Generate from './pages/Generate.tsx';
import Runs from './pages/Runs.tsx';
import HITL from './pages/HITL.tsx';
import Testbench from './pages/Testbench.tsx';
import Settings from './pages/Settings.tsx';
import { isOidcEnabled, loginOrLogout, getToken } from '@shared/auth';
import { useState, useEffect } from 'react';

const NAV = [
  { to: '/', label: 'Dashboard' },
  { to: '/chat', label: 'Chat' },
  { to: '/workflows', label: 'Workflows' },
  { to: '/editor', label: 'Editor' },
  { to: '/runs', label: 'Runs' },
  { to: '/agents', label: 'Agents' },
  { to: '/mcp-servers', label: 'MCP Servers' },
  { to: '/testbench', label: 'Testbench' },
  { to: '/hitl', label: 'HITL' },
  { to: '/settings', label: 'Settings' },
];

function Stub({ title }: { title: string }) {
  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h2 style={{ color: '#5bc0de' }}>{title}</h2>
      <p style={{ color: '#999' }}>Coming soon.</p>
    </div>
  );
}

export default function App() {
  const isMobile = useIsMobile();
  const [authed, setAuthed] = useState<boolean>(!!getToken());
  const generateEnabled = (typeof window !== 'undefined' && (window as any).QOC_AUTH?.generateEnabled === true);
  const oidc = isOidcEnabled();
  useEffect(() => {
    // handle OIDC callback if present
    (async () => {
      const { handleOidcCallback } = await import('@shared/auth');
      const handled = await handleOidcCallback();
      if (handled) {
        // Reload so every view mounts with the freshly stored token instead of
        // rendering one cycle with the pre-login (401) API results.
        window.location.replace(window.location.pathname);
        return;
      }
      setAuthed(!!getToken());
    })();
  }, []);
  const handleAuth = async () => {
    await loginOrLogout();
    setAuthed(!!getToken());
  };
  const loginBtn = oidc ? (
    <button onClick={handleAuth} style={{ color: '#d4d4d4', fontSize: 12, padding: '4px 8px', borderRadius: 4, background: authed ? '#5cb85c' : '#337ab7', border: 'none', cursor: 'pointer' }}>
      {authed ? 'Logout' : 'Login'}
    </button>
  ) : null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', width: '100vw', height: '100vh', background: '#161616', color: '#d4d4d4' }}>
      {!isMobile && (
        <nav style={{ display: 'flex', gap: 12, padding: '8px 16px', background: '#1e1e1e', borderBottom: '1px solid #333', alignItems: 'center', flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 'bold', color: '#5bc0de', marginRight: 8 }}>QOC</span>
          {NAV.map((n) => (
            <Link key={n.to} to={n.to} style={{ color: '#d4d4d4', textDecoration: 'none', fontSize: 12, padding: '4px 8px', borderRadius: 4, background: '#2d2d2d' }}>
              {n.label}
            </Link>
          ))}
          {generateEnabled ? (
            <Link to="/generate" style={{ color: '#d4d4d4', textDecoration: 'none', fontSize: 12, padding: '4px 8px', borderRadius: 4, background: '#2d2d2d' }}>Generate</Link>
          ) : (
            <span title="Workflow-Generierung deaktiviert (ENGINE_WORKFLOW_GENERATE_ENABLED)" style={{ color: '#666', fontSize: 12, padding: '4px 8px', borderRadius: 4, background: '#1a1a1a', cursor: 'not-allowed', opacity: 0.5 }}>Generate</span>
          )}
          {loginBtn && <span style={{ marginLeft: 'auto' }}>{loginBtn}</span>}
        </nav>
      )}
      {isMobile && (
        <div style={{ padding: '8px 12px', background: '#1e1e1e', borderBottom: '1px solid #333', display: 'flex', gap: 8, overflowX: 'auto', alignItems: 'center' }}>
          {NAV.map((n) => (
            <Link key={n.to} to={n.to} style={{ color: '#d4d4d4', textDecoration: 'none', fontSize: 11, padding: '4px 6px', borderRadius: 4, background: '#2d2d2d', whiteSpace: 'nowrap' }}>
              {n.label}
            </Link>
          ))}
          {generateEnabled ? (
            <Link to="/generate" style={{ color: '#d4d4d4', textDecoration: 'none', fontSize: 11, padding: '4px 6px', borderRadius: 4, background: '#2d2d2d', whiteSpace: 'nowrap' }}>Generate</Link>
          ) : (
            <span style={{ color: '#666', fontSize: 11, padding: '4px 6px', borderRadius: 4, background: '#1a1a1a', whiteSpace: 'nowrap', opacity: 0.5, cursor: 'not-allowed' }}>Generate</span>
          )}
          {loginBtn}
        </div>
      )}
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/workflows" element={<Workflows />} />
          <Route path="/workflows/:id" element={<EditorPage />} />
          <Route path="/editor" element={<EditorPage />} />
          <Route path="/runs" element={<Runs />} />
          <Route path="/agents" element={<Agents />} />
          <Route path="/mcp-servers" element={<McpServers />} />
          <Route path="/generate" element={<Generate />} />
          <Route path="/testbench" element={<Testbench />} />
          <Route path="/hitl" element={<HITL />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </div>
    </div>
  );
}
