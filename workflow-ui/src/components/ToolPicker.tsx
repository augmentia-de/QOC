import { useState, useEffect } from 'react';
import { ToolApi } from '../services/api';

interface ToolPickerProps {
  selected: string[];
  onChange: (tools: string[]) => void;
}

export function ToolPicker({ selected, onChange }: ToolPickerProps) {
  const [tools, setTools] = useState<{ name: string; description: string; source: string }[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);

  const fetchTools = async () => {
    setLoading(true);
    try {
      const res = await ToolApi.list();
      setTools(res.data.tools || []);
    } catch {
      setTools([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTools();
  }, []);

  const toggle = (name: string) => {
    if (selected.includes(name)) {
      onChange(selected.filter((t) => t !== name));
    } else {
      onChange([...selected, name]);
    }
  };

  // Group by source
  const groups = new Map<string, typeof tools>();
  for (const t of tools) {
    const g = t.source || 'other';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g)!.push(t);
  }

  return (
    <div className="border border-gray-600 rounded p-2">
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs font-semibold text-gray-300">Tools ({selected.length})</span>
        <div className="flex gap-1">
          <button
            type="button"
            onClick={fetchTools}
            className="text-xs px-2 py-0.5 bg-gray-700 hover:bg-gray-600 rounded"
            disabled={loading}
          >
            {loading ? '...' : 'Refresh'}
          </button>
          <button
            type="button"
            onClick={() => setOpen(!open)}
            className="text-xs px-2 py-0.5 bg-gray-700 hover:bg-gray-600 rounded"
          >
            {open ? 'Close' : 'Edit'}
          </button>
        </div>
      </div>
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1 mb-1">
          {selected.map((t) => (
            <span key={t} className="text-xs bg-blue-600 px-1.5 py-0.5 rounded">
              {t}
            </span>
          ))}
        </div>
      )}
      {open && (
        <div className="max-h-48 overflow-y-auto mt-1">
          {Array.from(groups.entries()).map(([source, groupTools]) => (
            <div key={source} className="mb-2">
              <div className="text-xs text-gray-400 font-semibold mb-0.5">{source}</div>
              {groupTools.map((t) => (
                <label key={t.name} className="flex items-center gap-1 text-xs py-0.5 cursor-pointer hover:bg-gray-700 px-1 rounded">
                  <input
                    type="checkbox"
                    checked={selected.includes(t.name)}
                    onChange={() => toggle(t.name)}
                    className="w-3 h-3"
                  />
                  <span className="truncate" title={t.description}>{t.name}</span>
                </label>
              ))}
            </div>
          ))}
          {tools.length === 0 && !loading && (
            <div className="text-xs text-gray-500">No tools available</div>
          )}
        </div>
      )}
    </div>
  );
}