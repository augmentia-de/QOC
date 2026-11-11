import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { HealthApi } from '../services/api';

export default function Dashboard() {
  const [health, setHealth] = useState<'up' | 'down' | 'unknown'>('unknown');

  useEffect(() => {
    HealthApi.check()
      .then(() => setHealth('up'))
      .catch(() => setHealth('down'));
  }, []);

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-6">Dashboard</h1>
      <div className="grid grid-cols-3 gap-4 mb-8">
        <div className="bg-gray-800 rounded p-4">
          <div className="text-gray-400 text-sm">Backend Status</div>
          <div className={`text-2xl font-bold ${health === 'up' ? 'text-green-400' : health === 'down' ? 'text-red-400' : 'text-gray-400'}`}>
            {health === 'up' ? 'UP' : health === 'down' ? 'DOWN' : '...'}
          </div>
        </div>
        <div className="bg-gray-800 rounded p-4">
          <div className="text-gray-400 text-sm">Workflows</div>
          <div className="text-2xl font-bold">—</div>
        </div>
        <div className="bg-gray-800 rounded p-4">
          <div className="text-gray-400 text-sm">Runs</div>
          <div className="text-2xl font-bold">—</div>
        </div>
      </div>
      <div className="flex gap-4">
        <Link to="/workflows" className="px-4 py-2 bg-blue-600 rounded text-sm">
          Workflows verwalten →
        </Link>
        <Link to="/editor" className="px-4 py-2 bg-gray-700 rounded text-sm">
          Graph Editor →
        </Link>
      </div>
    </div>
  );
}