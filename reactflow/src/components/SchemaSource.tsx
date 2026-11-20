import { useEffect, useState } from 'react';

const MODE_LABELS = { manual: 'Manuell (JSON)', java: 'Java-Quellcode', openapi: 'OpenAPI (YAML/JSON)', yaml: 'YAML (JSON Schema)' };

async function safeFetch(url: string, options: RequestInit) {
  const res = await fetch(url, options);
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new Error(data?.error || data?.message || ('HTTP ' + res.status));
  return data;
}

export function SchemaSource({ value, onChange, disabled = false, label }) {
  const [mode, setMode] = useState('manual');
  const [text, setText] = useState(value ? JSON.stringify(value, null, 2) : '');
  const [schemaNames, setSchemaNames] = useState([]);
  const [selectedName, setSelectedName] = useState('');
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (mode === 'manual') setText(value ? JSON.stringify(value, null, 2) : '');
  }, [value, mode]);

  const handleFileUpload = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => { setText(String(reader.result || '')); setError(null); };
    reader.readAsText(file);
  };

  const handleManualChange = (raw) => {
    setText(raw); setError(null);
    const trimmed = raw.trim();
    if (!trimmed) { onChange(null); return; }
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) onChange(parsed);
      else setError('Schema must be a JSON object.');
    } catch { setError('Invalid JSON.'); }
  };

  const generateFromJava = async () => {
    setLoading(true); setError(null);
    try {
      const data = await safeFetch('/api/schema/from-java', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source: text }) });
      if (data.schema) { onChange(data.schema); setText(JSON.stringify(data.schema, null, 2)); }
      else setError(data.error || 'Error generating.');
    } catch (e) { setError(e?.message || 'Error'); }
    finally { setLoading(false); }
  };

  const loadSchemaNames = async () => {
    setLoading(true); setError(null); setSchemaNames([]); setSelectedName('');
    try {
      const data = await safeFetch('/api/schema/from-openapi', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ spec: text }) });
      if (data.schemas) { setSchemaNames(data.schemas); if (data.schemas.length === 1) setSelectedName(data.schemas[0]); }
      else if (data.schema) { onChange(data.schema); setText(JSON.stringify(data.schema, null, 2)); }
      else setError(data.error || 'Error loading.');
    } catch (e) { setError(e?.message || 'Error'); }
    finally { setLoading(false); }
  };

  const importYamlSchema = async () => {
    setLoading(true); setError(null); setSchemaNames([]); setSelectedName('');
    try {
      const data = await safeFetch('/api/schema/from-yaml', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ yaml: text }) });
      if (data.schemas) { setSchemaNames(data.schemas); if (data.schemas.length === 1) setSelectedName(data.schemas[0]); }
      else if (data.schema) { onChange(data.schema); setText(JSON.stringify(data.schema, null, 2)); }
      else setError(data.error || 'Error parsing.');
    } catch (e) { setError(e?.message || 'Error'); }
    finally { setLoading(false); }
  };

  const importSelectedSchema = async () => {
    if (!selectedName) return;
    setLoading(true); setError(null);
    try {
      const data = await safeFetch(mode === 'openapi' ? '/api/schema/from-openapi' : '/api/schema/from-yaml', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ spec: text, schemaName: selectedName, yaml: text }) });
      if (data.schema) { onChange(data.schema); setText(JSON.stringify(data.schema, null, 2)); }
    } catch (e) { setError(e?.message || 'Error'); }
    finally { setLoading(false); }
  };

  const validateSchema = async () => {
    if (!value) { setError('No schema to validate.'); return; }
    setLoading(true); setError(null);
    try {
      const data = await safeFetch('/api/schema/validate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ schema: value }) });
      if (data.valid) { setError(null); alert('Schema is valid.'); }
      else setError(data.message || data.error || 'Schema invalid.');
    } catch (e) { setError(e?.message || 'Error'); }
    finally { setLoading(false); }
  };

  return (
    <div style={{ marginTop: 8 }}>
      <label style={{ display: 'block', fontSize: 11, color: '#999', marginBottom: 4 }}>{label || 'Response Schema (JSON Schema)'}</label>
      <div style={{ display: 'flex', gap: 4, marginBottom: 4, flexWrap: 'wrap' }}>
        {Object.keys(MODE_LABELS).map((m) => (
          <button key={m} type="button" onClick={() => { setMode(m); setError(null); setSchemaNames([]); setSelectedName(''); }} disabled={disabled}
            style={{ fontSize: 10, padding: '2px 6px', borderRadius: 4, border: '1px solid #555', background: mode === m ? '#337ab7' : '#2d2d2d', color: '#d4d4d4' }}>
            {MODE_LABELS[m]}
          </button>
        ))}
      </div>
      {mode === 'manual' && (
        <textarea value={text} onChange={(e) => handleManualChange(e.target.value)} disabled={disabled}
          placeholder='{"type":"object","properties":{"answer":{"type":"string"}}}'
          style={{ width: '100%', height: 80, background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', fontFamily: 'monospace', fontSize: 11 }} />
      )}
      {mode === 'java' && (
        <>
          <input type="file" accept=".java" onChange={handleFileUpload} disabled={disabled} style={{ marginBottom: 4, fontSize: 11, color: '#d4d4d4' }} />
          <textarea value={text} onChange={(e) => setText(e.target.value)} disabled={disabled} placeholder="public record Person(String name, int age) {}"
            style={{ width: '100%', height: 80, background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', fontFamily: 'monospace', fontSize: 11 }} />
          <button onClick={generateFromJava} disabled={disabled || loading || !text.trim()} style={{ marginTop: 4, fontSize: 10, padding: '2px 6px', borderRadius: 4, border: '1px solid #555', background: '#2d2d2d', color: '#d4d4d4' }}>
            {loading ? '...' : 'Schema generieren'}
          </button>
        </>
      )}
      {(mode === 'openapi' || mode === 'yaml') && (
        <>
          <textarea value={text} onChange={(e) => setText(e.target.value)} disabled={disabled} placeholder={mode === 'openapi' ? 'openapi: 3.0.0...' : 'type: object\nproperties:...'}
            style={{ width: '100%', height: 80, background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', fontFamily: 'monospace', fontSize: 11 }} />
          <div style={{ display: 'flex', gap: 4, marginTop: 4 }}>
            <button onClick={mode === 'openapi' ? loadSchemaNames : importYamlSchema} disabled={disabled || loading || !text.trim()} style={{ fontSize: 10, padding: '2px 6px', borderRadius: 4, border: '1px solid #555', background: '#2d2d2d', color: '#d4d4d4' }}>
              {loading ? '...' : (mode === 'openapi' ? 'Schemas laden' : 'Importieren')}
            </button>
            {schemaNames.length > 1 && (
              <>
                <select value={selectedName} onChange={(e) => setSelectedName(e.target.value)} disabled={disabled} style={{ fontSize: 10, padding: '2px 4px', borderRadius: 4, border: '1px solid #555', background: '#1e1e1e', color: '#d4d4d4' }}>
                  <option value="">-- Schema wählen --</option>
                  {schemaNames.map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
                <button onClick={importSelectedSchema} disabled={disabled || loading || !selectedName} style={{ fontSize: 10, padding: '2px 6px', borderRadius: 4, border: '1px solid #555', background: '#2d2d2d', color: '#d4d4d4' }}>
                  Importieren
                </button>
              </>
            )}
          </div>
        </>
      )}
      {error && <div style={{ color: '#d9534f', fontSize: 11, marginTop: 4 }}>{error}</div>}
      {value && <div style={{ marginTop: 4 }}><button onClick={validateSchema} disabled={disabled || loading} style={{ fontSize: 10, padding: '2px 6px', borderRadius: 4, border: '1px solid #555', background: '#2d2d2d', color: '#d4d4d4' }}>Validieren</button></div>}
    </div>
  );
}