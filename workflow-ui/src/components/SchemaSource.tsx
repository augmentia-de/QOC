import { useEffect, useState } from 'react';
import type { ChangeEvent } from 'react';
import { SchemaApi } from '../services/api';

type Mode = 'manual' | 'java' | 'openapi' | 'yaml';

interface SchemaSourceProps {
  value: Record<string, unknown> | null;
  onChange: (schema: Record<string, unknown> | null) => void;
  disabled?: boolean;
  label?: string;
}

const MODE_LABELS: Record<Mode, string> = {
  manual: 'Manuell (JSON)',
  java: 'Java-Quellcode',
  openapi: 'OpenAPI (YAML/JSON)',
  yaml: 'YAML (JSON Schema)',
};

export function SchemaSource({ value, onChange, disabled, label }: SchemaSourceProps) {
  const [mode, setMode] = useState<Mode>('manual');
  const [text, setText] = useState(value ? JSON.stringify(value, null, 2) : '');
  const [schemaNames, setSchemaNames] = useState<string[]>([]);
  const [selectedName, setSelectedName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Keep the manual textarea in sync with external value changes.
  useEffect(() => {
    if (mode === 'manual') {
      setText(value ? JSON.stringify(value, null, 2) : '');
    }
  }, [value, mode]);

  const handleFileUpload = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      setText(String(reader.result || ''));
      setError(null);
    };
    reader.readAsText(file);
  };

  const handleManualChange = (raw: string) => {
    setText(raw);
    setError(null);
    const trimmed = raw.trim();
    if (!trimmed) {
      onChange(null);
      return;
    }
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        onChange(parsed as Record<string, unknown>);
      } else {
        setError('Schema must be a JSON object.');
      }
    } catch {
      setError('Invalid JSON.');
    }
  };

  const generateFromJava = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await SchemaApi.fromJava(text);
      onChange(res.data.schema);
      setText(JSON.stringify(res.data.schema, null, 2));
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.message || 'Error generating.');
    } finally {
      setLoading(false);
    }
  };

  const loadSchemaNames = async () => {
    setLoading(true);
    setError(null);
    setSchemaNames([]);
    setSelectedName('');
    try {
      const res = await SchemaApi.fromOpenApi(text);
      if (res.data.schemas) {
        setSchemaNames(res.data.schemas);
        if (res.data.schemas.length === 1) setSelectedName(res.data.schemas[0]);
      } else if (res.data.schema) {
        onChange(res.data.schema);
        setText(JSON.stringify(res.data.schema, null, 2));
      }
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.message || 'Error loading.');
    } finally {
      setLoading(false);
    }
  };

  const importYamlSchema = async () => {
    setLoading(true);
    setError(null);
    setSchemaNames([]);
    setSelectedName('');
    try {
      const res = await SchemaApi.fromYaml(text);
      if (res.data.schemas) {
        setSchemaNames(res.data.schemas);
        if (res.data.schemas.length === 1) setSelectedName(res.data.schemas[0]);
      } else if (res.data.schema) {
        onChange(res.data.schema);
        setText(JSON.stringify(res.data.schema, null, 2));
      }
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.message || 'Error parsing.');
    } finally {
      setLoading(false);
    }
  };

  const importSelectedSchema = async () => {
    if (!selectedName) return;
    setLoading(true);
    setError(null);
    try {
      const res = mode === 'openapi'
        ? await SchemaApi.fromOpenApi(text, selectedName)
        : await SchemaApi.fromYaml(text, selectedName);
      if (res.data.schema) {
        onChange(res.data.schema);
        setText(JSON.stringify(res.data.schema, null, 2));
      }
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.message || 'Error importing.');
    } finally {
      setLoading(false);
    }
  };

  const validateSchema = async () => {
    if (!value) {
      setError('No schema to validate.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await SchemaApi.validate(value);
      if (res.data.valid) {
        setError(null);
        alert('Schema is valid.');
      } else {
        setError(res.data.message || res.data.error || 'Schema invalid.');
      }
    } catch (e: any) {
      setError(e?.response?.data?.error || e?.message || 'Error validating.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="mb-3">
      <label className="block text-sm text-gray-400 mb-1">{label ?? 'Response Schema (JSON Schema)'}</label>
      <div className="mb-2 flex flex-wrap gap-1">
        {(Object.keys(MODE_LABELS) as Mode[]).map((m) => (
          <button
            key={m}
            type="button"
            onClick={() => {
              setMode(m);
              setError(null);
              setSchemaNames([]);
              setSelectedName('');
            }}
            className={`rounded px-2 py-0.5 text-xs ${
              mode === m ? 'bg-blue-600 text-white' : 'bg-gray-700 text-gray-300'
            }`}
            disabled={disabled}
          >
            {MODE_LABELS[m]}
          </button>
        ))}
      </div>

      {mode === 'manual' && (
        <textarea
          value={text}
          onChange={(e) => handleManualChange(e.target.value)}
          className="h-32 w-full rounded bg-gray-700 px-2 py-1 font-mono text-sm"
          placeholder='{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}'
          disabled={disabled}
        />
      )}

      {mode === 'java' && (
        <>
          <input
            type="file"
            accept=".java"
            onChange={handleFileUpload}
            className="mb-2 w-full text-xs text-gray-300"
            disabled={disabled}
          />
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            className="h-32 w-full rounded bg-gray-700 px-2 py-1 font-mono text-sm"
            placeholder="public record Person(String name, int age) {}"
            disabled={disabled}
          />
          <button
            type="button"
            onClick={generateFromJava}
            disabled={disabled || loading || !text.trim()}
            className="mt-2 rounded bg-blue-600 px-3 py-1 text-xs font-bold disabled:opacity-50"
          >
            {loading ? 'Generiere…' : 'Schema generieren'}
          </button>
        </>
      )}

      {(mode === 'openapi' || mode === 'yaml') && (
        <>
          <input
            type="file"
            accept={mode === 'openapi' ? '.yaml,.yml,.json' : '.yaml,.yml'}
            onChange={handleFileUpload}
            className="mb-2 w-full text-xs text-gray-300"
            disabled={disabled}
          />
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            className="h-32 w-full rounded bg-gray-700 px-2 py-1 font-mono text-sm"
            placeholder={mode === 'openapi' ? 'openapi: 3.0.0\n...' : 'type: object\nproperties:\n  name:\n    type: string'}
            disabled={disabled}
          />
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              onClick={mode === 'openapi' ? loadSchemaNames : importYamlSchema}
              disabled={disabled || loading || !text.trim()}
              className="rounded bg-blue-600 px-3 py-1 text-xs font-bold disabled:opacity-50"
            >
              {loading ? 'Lade…' : mode === 'openapi' ? 'Schemas laden' : 'Schema parsen'}
            </button>
            {schemaNames.length > 0 && (
              <>
                <select
                  value={selectedName}
                  onChange={(e) => setSelectedName(e.target.value)}
                  className="rounded bg-gray-700 px-2 py-1 text-xs"
                >
                  <option value="">– Schema –</option>
                  {schemaNames.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={importSelectedSchema}
                  disabled={disabled || loading || !selectedName}
                  className="rounded bg-green-600 px-3 py-1 text-xs font-bold disabled:opacity-50"
                >
                  Apply
                </button>
              </>
            )}
          </div>
        </>
      )}

      {value && (
        <div className="mt-2">
          <details className="text-xs text-gray-400">
            <summary className="cursor-pointer">Aktuelles Schema (Vorschau)</summary>
            <pre className="mt-1 max-h-32 overflow-auto rounded bg-gray-900 p-2 font-mono text-[10px]">
              {JSON.stringify(value, null, 2)}
            </pre>
          </details>
        </div>
      )}

      <div className="mt-2 flex items-center gap-2">
        <button
          type="button"
          onClick={validateSchema}
          disabled={disabled || loading || !value}
          className="rounded bg-gray-600 px-3 py-1 text-xs disabled:opacity-50"
        >
          Schema validieren
        </button>
        {error && <span className="text-xs text-red-400">{error}</span>}
      </div>
      <p className="mt-1 text-xs text-gray-500">
        Empty = no structured output. Import via /api/schema/* possible.
      </p>
    </div>
  );
}
