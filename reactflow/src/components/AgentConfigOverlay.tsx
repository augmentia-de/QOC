import { SchemaSource } from './SchemaSource.tsx';

export type AgentForm = {
  name: string;
  version: string;
  category: string;
  model: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  permissions: string;
  prompt: string;
  jsonOutput: boolean;
  schema: string;
  tools: string[];
  guardrailsInput: string;
  guardrailsOutput: string;
  hooks: string;
  mode: string;
  skills: string;
  ownerId: string;
  maxTurns: number;
  maxCost: number;
  active: boolean;
  requiresHITL: boolean;
};

export const TOOL_OPTIONS = ['read', 'write', 'edit', 'glob', 'grep', 'patch', 'shell', 'process', 'webfetch', 'secure_rest', 'firecrawl', 'todo', 'skill', 'worktree_create', 'worktree_list', 'worktree_remove'];

type Props = {
  open: boolean;
  agent: AgentForm;
  onChange: (patch: Partial<AgentForm>) => void;
  onClose: () => void;
  onSave?: () => void;
  onSaveRef?: () => void;
  onSaveToFile?: () => void;
  onResetToRef?: () => void;
  showWorkflowActions?: boolean;
  showRef?: boolean;
};

export function AgentConfigOverlay({ open, agent, onChange, onClose, onSave, onSaveRef, onSaveToFile, onResetToRef, showWorkflowActions = false, showRef = true }: Props) {
  if (!open) return null;
  return (
    <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
      <div style={{ background: '#2d2d2d', padding: 16, borderRadius: 8, width: 560, maxHeight: '85vh', overflow: 'auto', color: '#d4d4d4', fontSize: 12 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <h3 style={{ margin: 0 }}>Agent Config</h3>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer', fontSize: 16 }}>x</button>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <label>Name<br /><input value={agent.name} onChange={(e) => onChange({ name: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Version<br /><input value={agent.version} onChange={(e) => onChange({ version: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Category<br /><input value={agent.category} onChange={(e) => onChange({ category: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Model (disabled — agent uses default model)<br />
            <select value="" disabled style={{ width: '100%', background: '#1e1e1e', color: '#888', border: '1px solid #555' }}>
              <option value="">(default)</option>
            </select>
          </label>
          <label>Temperature<br /><input type="number" step="0.1" value={agent.temperature} onChange={(e) => onChange({ temperature: parseFloat(e.target.value) || 0.3 })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Max Tokens<br /><input type="number" value={agent.maxTokens} onChange={(e) => onChange({ maxTokens: parseInt(e.target.value) || 4096 })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Top P<br /><input type="number" step="0.05" value={agent.topP} onChange={(e) => onChange({ topP: parseFloat(e.target.value) || 1.0 })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
          <label>Roles allowed (comma, * = all)<br /><input value={agent.permissions} onChange={(e) => onChange({ permissions: e.target.value })} placeholder="*, admin, reviewer" style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
        </div>
        <label style={{ display: 'block', marginTop: 8 }}>System Prompt<br />
          <textarea value={agent.prompt} onChange={(e) => onChange({ prompt: e.target.value })} style={{ width: '100%', height: 80, background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>
          <input type="checkbox" checked={agent.jsonOutput} onChange={(e) => onChange({ jsonOutput: e.target.checked })} />
          {' '}JSON Output
        </label>
        <SchemaSource value={agent.schema.trim() ? (() => { try { return JSON.parse(agent.schema); } catch { return null; } })() : null} onChange={(schema) => onChange({ schema: schema ? JSON.stringify(schema, null, 2) : '' })} label="Response Schema (JSON)" />
        <div style={{ marginTop: 8 }}>
          <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>Tools (leer = keine Tools; * ist kein Eintrag — nutze keine Auswahl für "keine")</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {TOOL_OPTIONS.map((tool) => (
              <label key={tool} style={{ fontSize: 11 }}>
                <input type="checkbox" checked={agent.tools.includes(tool)} onChange={(e) => {
                  if (e.target.checked) onChange({ tools: [...agent.tools, tool] });
                  else onChange({ tools: agent.tools.filter((t) => t !== tool) });
                }} />
                {' '}{tool}
              </label>
            ))}
          </div>
        </div>
        <label style={{ display: 'block', marginTop: 8 }}>Guardrails Input (comma)<br />
          <input value={agent.guardrailsInput} onChange={(e) => onChange({ guardrailsInput: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Guardrails Output (comma)<br />
          <input value={agent.guardrailsOutput} onChange={(e) => onChange({ guardrailsOutput: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Hooks (comma)<br />
          <input value={agent.hooks} onChange={(e) => onChange({ hooks: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Mode<br />
          <select value={agent.mode} onChange={(e) => onChange({ mode: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }}>
            <option value="all">all</option>
            <option value="read">read</option>
            <option value="write">write</option>
          </select>
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Skills (comma)<br />
          <input value={agent.skills} onChange={(e) => onChange({ skills: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Owner ID<br />
          <input value={agent.ownerId} onChange={(e) => onChange({ ownerId: e.target.value })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Max Turns<br />
          <input type="number" value={agent.maxTurns} onChange={(e) => onChange({ maxTurns: parseInt(e.target.value) || 10 })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>Max Cost (USD)<br />
          <input type="number" step="0.1" value={agent.maxCost} onChange={(e) => onChange({ maxCost: parseFloat(e.target.value) || 1.0 })} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>
          <input type="checkbox" checked={agent.active} onChange={(e) => onChange({ active: e.target.checked })} />
          {' '}Active
        </label>
        <label style={{ display: 'block', marginTop: 8 }}>
          <input type="checkbox" checked={agent.requiresHITL} onChange={(e) => onChange({ requiresHITL: e.target.checked })} />
          {' '}Requires Human Approval
        </label>
        <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {onSave && <button onClick={onSave} style={{ background: '#5cb85c', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Save</button>}
          {showWorkflowActions && onSaveRef && <button onClick={onSaveRef} style={{ background: '#337ab7', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Save Ref</button>}
          {showWorkflowActions && onSaveToFile && <button onClick={onSaveToFile} style={{ background: '#17a2b8', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Save ref to file</button>}
          {showWorkflowActions && onResetToRef && <button onClick={onResetToRef} style={{ background: '#f0ad4e', color: '#000', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Reset to Ref</button>}
          <button onClick={onClose} style={{ background: '#555', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Close</button>
        </div>
      </div>
    </div>
  );
}