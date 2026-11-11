import { useState } from 'react';

export default function Settings() {
  const [backendUrl, setBackendUrl] = useState('http://localhost:8097');

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Settings</h1>
      <div className="space-y-4 max-w-md">
        <div>
          <label className="block text-sm text-gray-400 mb-1">Backend URL</label>
          <input
            value={backendUrl}
            onChange={(e) => setBackendUrl(e.target.value)}
            className="w-full px-3 py-2 bg-gray-800 rounded border border-gray-700"
          />
        </div>
        <div>
          <label className="block text-sm text-gray-400 mb-1">Theme</label>
          <select className="w-full px-3 py-2 bg-gray-800 rounded border border-gray-700">
            <option>Dark</option>
            <option>Light</option>
          </select>
        </div>
        <button className="px-4 py-2 bg-blue-600 rounded text-sm">Speichern</button>
      </div>
    </div>
  );
}
