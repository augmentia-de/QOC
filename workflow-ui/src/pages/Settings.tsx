import { useEffect, useState } from 'react';

const STORAGE_KEY = 'qoc-ui-settings';

interface UiSettings {
  backendUrl: string;
  theme: string;
}

const DEFAULTS: UiSettings = {
  backendUrl: `${window.location.origin}/api`,
  theme: 'Dark',
};

export default function Settings() {
  const [backendUrl, setBackendUrl] = useState(DEFAULTS.backendUrl);
  const [theme, setTheme] = useState(DEFAULTS.theme);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const stored = JSON.parse(raw) as Partial<UiSettings>;
        if (stored.backendUrl) setBackendUrl(stored.backendUrl);
        if (stored.theme) setTheme(stored.theme);
      }
    } catch {
      // ignore corrupt settings
    }
  }, []);

  const save = () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ backendUrl, theme } satisfies UiSettings));
    document.documentElement.dataset.theme = theme.toLowerCase();
    setSaved(true);
    window.setTimeout(() => setSaved(false), 2500);
  };

  const reset = () => {
    localStorage.removeItem(STORAGE_KEY);
    setBackendUrl(DEFAULTS.backendUrl);
    setTheme(DEFAULTS.theme);
    setSaved(false);
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold mb-4">Settings</h1>
      <p className="text-sm text-gray-400 mb-4">
        UI-Einstellungen werden lokal im Browser gespeichert. Die API wird relativ
        über <code>/api</code> angesprochen (nginx-/Vite-Proxy).
      </p>
      <div className="space-y-4 max-w-md">
        <div>
          <label className="block text-sm text-gray-400 mb-1">Backend URL (Anzeige)</label>
          <input
            value={backendUrl}
            onChange={(e) => setBackendUrl(e.target.value)}
            className="w-full px-3 py-2 bg-gray-800 rounded border border-gray-700"
          />
        </div>
        <div>
          <label className="block text-sm text-gray-400 mb-1">Theme</label>
          <select
            value={theme}
            onChange={(e) => setTheme(e.target.value)}
            className="w-full px-3 py-2 bg-gray-800 rounded border border-gray-700"
          >
            <option value="Dark">Dark</option>
            <option value="Light">Light</option>
          </select>
        </div>
        <div className="flex items-center gap-3">
          <button onClick={save} className="px-4 py-2 bg-blue-600 rounded text-sm">
            Speichern
          </button>
          <button onClick={reset} className="px-4 py-2 bg-gray-700 rounded text-sm">
            Zurücksetzen
          </button>
          {saved && <span className="text-green-400 text-sm">Gespeichert.</span>}
        </div>
      </div>
    </div>
  );
}
