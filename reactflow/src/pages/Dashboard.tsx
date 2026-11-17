import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { QocApi } from '../services/qocApi.ts';

export default function Dashboard() {
  const [health, setHealth] = useState<'up' | 'down' | 'unknown'>('unknown');
  const [wfCount, setWfCount] = useState<number | null>(null);

  useEffect(() => {
    QocApi.health.check().then(() => setHealth('up')).catch(() => setHealth('down'));
    QocApi.workflows.list().then((r: any) => setWfCount(Array.isArray(r.workflows) ? r.workflows.length : null)).catch(() => {});
  }, []);

  const box = (label: string, value: string, color: string) => (
    <div style={{ background: '#1e1e1e', borderRadius: 8, padding: 16, border: '1px solid #333' }}>
      <div style={{ color: '#999', fontSize: 12 }}>{label}</div>
      <div style={{ fontSize: 24, fontWeight: 'bold', color }}>{value}</div>
    </div>
  );

  return (
    <div style={{ padding: 24, color: '#d4d4d4' }}>
      <h1 style={{ fontSize: 22, color: '#5bc0de', marginBottom: 16 }}>Dashboard</h1>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 24 }}>
        {box('Backend Status', health === 'up' ? 'UP' : health === 'down' ? 'DOWN' : '...', health === 'up' ? '#5cb85c' : health === 'down' ? '#d9534f' : '#999')}
        {box('Workflows', wfCount === null ? '—' : String(wfCount), '#5bc0de')}
        {box('Runs', '—', '#999')}
      </div>
      <div style={{ display: 'flex', gap: 12 }}>
        <Link to="/workflows" style={{ padding: '8px 16px', background: '#337ab7', color: '#fff', borderRadius: 4, textDecoration: 'none', fontSize: 13 }}>Workflows verwalten →</Link>
        <Link to="/editor" style={{ padding: '8px 16px', background: '#2d2d2d', color: '#d4d4d4', borderRadius: 4, textDecoration: 'none', fontSize: 13, border: '1px solid #555' }}>Graph Editor →</Link>
      </div>
    </div>
  );
}
