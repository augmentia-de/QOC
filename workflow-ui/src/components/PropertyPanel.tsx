import { useState, useEffect } from 'react';

interface AgentConfig {
  id: string;
  name: string;
  version: string;
  category: string;
  model: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  tools: string[];
  guardrailsInput: string[];
  guardrailsOutput: string[];
  hooks: string[];
  jsonOutput: boolean;
  active: boolean;
  systemPrompt: string;
}

const MODELS = ['deepseek-v4.1-flash', 'gpt-4o', 'gpt-4o-mini', 'claude-3.5-sonnet'];
const TOOL_OPTIONS = ['Read', 'Write', 'Edit', 'Glob', 'Grep', 'Patch', 'WebFetch', 'Shell'];
const GUARDRAIL_OPTIONS = ['pii', 'sql-injection', 'prompt-injection'];
const HOOK_OPTIONS = ['pii-masking', 'audit'];

const BUILD_AGENT: AgentConfig = {
  id: 'build',
  name: 'Build Agent',
  version: '1.0.0',
  category: 'core',
  model: 'deepseek-v4.1-flash',
  temperature: 0.3,
  maxTokens: 4096,
  topP: 1.0,
  tools: [],
  guardrailsInput: [],
  guardrailsOutput: [],
  hooks: [],
  jsonOutput: false,
  active: true,
  systemPrompt: 'You are a helpful build agent. Reply concisely.',
};

interface PropertyPanelProps {
  selectedNode: { id: string; type: string; data: Record<string, unknown> } | null;
  onUpdate: (nodeId: string, data: Record<string, unknown>) => void;
  agents?: AgentConfig[];
  onAgentChange?: (agent: AgentConfig) => void;
  onCopyConfig?: (agent: AgentConfig) => void;
}

function MultiSelect({ label, options, value, onChange }: { label: string; options: string[]; value: string; onChange: (v: string) => void }) {
  const selected = value.split(',').map((s) => s.trim()).filter(Boolean);
  return (
    <div className="mb-3">
      <label className="block text-sm text-gray-400 mb-1">{label}</label>
      <div className="flex flex-wrap gap-2 mb-1">
        {options.map((opt) => (
          <label key={opt} className="flex items-center gap-1 text-xs">
            <input
              type="checkbox"
              checked={selected.includes(opt)}
              onChange={(e) => {
                let next = [...selected];
                if (e.target.checked) {
                  if (!next.includes(opt)) next.push(opt);
                } else {
                  next = next.filter((x) => x !== opt);
                }
                onChange(next.join(', '));
              }}
            />
            {opt}
          </label>
        ))}
      </div>
      <input value={value} onChange={(e) => onChange(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="custom, ..." />
    </div>
  );
}

function Collapsible({ title, children, defaultOpen = true }: { title: string; children: React.ReactNode; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="mb-4 border-t border-gray-700 pt-4">
      <button onClick={() => setOpen(!open)} className="font-semibold text-sm mb-2 flex items-center gap-1 w-full text-left">
        <span>{open ? '▼' : '▶'}</span> {title}
      </button>
      {open && <div>{children}</div>}
    </div>
  );
}

export function PropertyPanel({ selectedNode, onUpdate, agents = [], onAgentChange, onCopyConfig }: PropertyPanelProps) {
  const [title, setTitle] = useState('');
  const [agentRef, setAgentRef] = useState('');
  const [requiresHITL, setRequiresHITL] = useState(false);
  const [isDirty, setIsDirty] = useState(false);

  // Agent config state
  const [agentName, setAgentName] = useState('');
  const [agentVersion, setAgentVersion] = useState('1.0.0');
  const [agentCategory, setAgentCategory] = useState('');
  const [model, setModel] = useState('deepseek-v4.1-flash');
  const [temperature, setTemperature] = useState(0.3);
  const [maxTokens, setMaxTokens] = useState(4096);
  const [topP, setTopP] = useState(1.0);
  const [systemPrompt, setSystemPrompt] = useState('');
  const [jsonOutput, setJsonOutput] = useState(false);
  const [active, setActive] = useState(true);
  const [tools, setTools] = useState('');
  const [guardrailsInput, setGuardrailsInput] = useState('');
  const [guardrailsOutput, setGuardrailsOutput] = useState('');
  const [hooks, setHooks] = useState('');

  const markDirty = () => setIsDirty(true);

  // Sync state when selectedNode changes
  useEffect(() => {
    if (!selectedNode) return;
    setTitle(String(selectedNode.data?.title || ''));
    setAgentRef(String(selectedNode.data?.agentRef || ''));
    setRequiresHITL(Boolean(selectedNode.data?.requiresHumanApproval));
    setIsDirty(false);
    const agent = agents.find((a) => a.id === String(selectedNode.data?.agentRef || ''));
    if (agent) {
      setAgentName(agent.name);
      setAgentVersion(agent.version);
      setAgentCategory(agent.category);
      setModel(agent.model);
      setTemperature(agent.temperature);
      setMaxTokens(agent.maxTokens);
      setTopP(agent.topP);
      setSystemPrompt(agent.systemPrompt);
      setJsonOutput(agent.jsonOutput);
      setActive(agent.active);
      setTools(agent.tools.join(', '));
      setGuardrailsInput(agent.guardrailsInput.join(', '));
      setGuardrailsOutput(agent.guardrailsOutput.join(', '));
      setHooks(agent.hooks.join(', '));
    } else {
      setAgentName('');
      setAgentVersion('1.0.0');
      setAgentCategory('');
      setModel('deepseek-v4.1-flash');
      setTemperature(0.3);
      setMaxTokens(4096);
      setTopP(1.0);
      setSystemPrompt('');
      setJsonOutput(false);
      setActive(true);
      setTools('');
      setGuardrailsInput('');
      setGuardrailsOutput('');
      setHooks('');
    }
  }, [selectedNode?.id, agents]);

  const loadAgent = (ref: string) => {
    const agent = agents.find((a) => a.id === ref);
    if (agent) {
      setAgentName(agent.name);
      setAgentVersion(agent.version);
      setAgentCategory(agent.category);
      setModel(agent.model);
      setTemperature(agent.temperature);
      setMaxTokens(agent.maxTokens);
      setTopP(agent.topP);
      setSystemPrompt(agent.systemPrompt);
      setJsonOutput(agent.jsonOutput);
      setActive(agent.active);
      setTools(agent.tools.join(', '));
      setGuardrailsInput(agent.guardrailsInput.join(', '));
      setGuardrailsOutput(agent.guardrailsOutput.join(', '));
      setHooks(agent.hooks.join(', '));
      markDirty();
    }
  };

  const applyBuildAgent = () => {
    setAgentName('Build Agent');
    setAgentVersion('1.0.0');
    setAgentCategory('core');
    setModel('deepseek-v4.1-flash');
    setTemperature(0.3);
    setMaxTokens(4096);
    setTopP(1.0);
    setSystemPrompt('You are a helpful build agent. Reply concisely.');
    setJsonOutput(false);
    setActive(true);
    setTools('');
    setGuardrailsInput('');
    setGuardrailsOutput('');
    setHooks('');
    setAgentRef('build');
    markDirty();
  };

  const getCurrentAgent = (): AgentConfig => ({
    id: agentRef || 'agent-' + Date.now(),
    name: agentName || agentRef,
    version: agentVersion,
    category: agentCategory,
    model,
    temperature,
    maxTokens,
    topP,
    tools: tools.split(',').map((t) => t.trim()).filter(Boolean),
    guardrailsInput: guardrailsInput.split(',').map((t) => t.trim()).filter(Boolean),
    guardrailsOutput: guardrailsOutput.split(',').map((t) => t.trim()).filter(Boolean),
    hooks: hooks.split(',').map((t) => t.trim()).filter(Boolean),
    jsonOutput,
    active,
    systemPrompt,
  });

  const handleSave = () => {
    if (!selectedNode) return;
    onUpdate(selectedNode.id, { title, agentRef, requiresHumanApproval: requiresHITL });
    if (onAgentChange) onAgentChange(getCurrentAgent());
    setIsDirty(false);
  };

  const handleReset = () => {
    if (!selectedNode) return;
    setTitle(String(selectedNode.data?.title || ''));
    setAgentRef(String(selectedNode.data?.agentRef || ''));
    setRequiresHITL(Boolean(selectedNode.data?.requiresHumanApproval));
    setIsDirty(false);
  };

  const handleCopy = () => {
    const agent = getCurrentAgent();
    navigator.clipboard.writeText(JSON.stringify(agent, null, 2));
    if (onCopyConfig) onCopyConfig(agent);
  };

  if (!selectedNode) {
    return (
      <div className="w-80 bg-gray-800 p-4 border-l border-gray-700">
        <p className="text-gray-400 text-sm">Node auswählen</p>
      </div>
    );
  }

  return (
    <div className="w-80 bg-gray-800 p-4 border-l border-gray-700 overflow-y-auto">
      <h3 className="font-bold mb-4">
        Properties: {selectedNode.id}
        {isDirty && <span className="text-amber-400 text-xs ml-2">● ungespeichert</span>}
      </h3>

      <div className="mb-4">
        <label className="block text-sm text-gray-400 mb-1">Title</label>
        <input
          value={title}
          onChange={(e) => { setTitle(e.target.value); markDirty(); }}
          className="w-full px-2 py-1 bg-gray-700 rounded text-sm"
        />
      </div>

      {selectedNode.type === 'agent' && (
        <>
          <div className="mb-4">
            <label className="block text-sm text-gray-400 mb-1">Agent-Ref</label>
            <div className="flex gap-2">
              <select
                value={agentRef}
                onChange={(e) => { setAgentRef(e.target.value); loadAgent(e.target.value); }}
                className="flex-1 px-2 py-1 bg-gray-700 rounded text-sm"
              >
                <option value="">-- neu --</option>
                {agents.map((a) => (
                  <option key={a.id} value={a.id}>{a.id} ({a.name})</option>
                ))}
              </select>
              <button onClick={applyBuildAgent} className="px-2 py-1 bg-amber-600 rounded text-xs" title="Build Agent laden">
                Build
              </button>
            </div>
          </div>

          <Collapsible title="Agent Config (QocEngine.AgentConfig)" defaultOpen={false}>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Name</label>
              <input value={agentName} onChange={(e) => { setAgentName(e.target.value); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Version</label>
              <input value={agentVersion} onChange={(e) => { setAgentVersion(e.target.value); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Category</label>
              <input value={agentCategory} onChange={(e) => { setAgentCategory(e.target.value); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Model</label>
              <select value={model} onChange={(e) => { setModel(e.target.value); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm">
                {MODELS.map((m) => (<option key={m} value={m}>{m}</option>))}
              </select>
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Temperature: {temperature}</label>
              <input type="range" min="0" max="2" step="0.1" value={temperature} onChange={(e) => { setTemperature(parseFloat(e.target.value)); markDirty(); }} className="w-full" />
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Max Tokens</label>
              <input type="number" value={maxTokens} onChange={(e) => { setMaxTokens(parseInt(e.target.value) || 4096); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Top P: {topP}</label>
              <input type="range" min="0" max="1" step="0.05" value={topP} onChange={(e) => { setTopP(parseFloat(e.target.value)); markDirty(); }} className="w-full" />
            </div>
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">System Prompt</label>
              <textarea value={systemPrompt} onChange={(e) => { setSystemPrompt(e.target.value); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-24" placeholder="You are a helpful agent. Use ${node_1.output.field} for data-piping." />
              <p className="text-xs text-gray-500 mt-1">Data-Piping: ${'node_id.output.field'} für Werte aus Vorgänger-Nodes</p>
            </div>
            <MultiSelect label="Tools" options={TOOL_OPTIONS} value={tools} onChange={(v) => { setTools(v); markDirty(); }} />
            <MultiSelect label="Guardrails Input" options={GUARDRAIL_OPTIONS} value={guardrailsInput} onChange={(v) => { setGuardrailsInput(v); markDirty(); }} />
            <MultiSelect label="Guardrails Output" options={GUARDRAIL_OPTIONS} value={guardrailsOutput} onChange={(v) => { setGuardrailsOutput(v); markDirty(); }} />
            <MultiSelect label="Hooks" options={HOOK_OPTIONS} value={hooks} onChange={(v) => { setHooks(v); markDirty(); }} />
            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={jsonOutput} onChange={(e) => { setJsonOutput(e.target.checked); markDirty(); }} />
                JSON Output
              </label>
            </div>
            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={active} onChange={(e) => { setActive(e.target.checked); markDirty(); }} />
                Active
              </label>
            </div>
            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={requiresHITL} onChange={(e) => { setRequiresHITL(e.target.checked); markDirty(); }} />
                Requires Human Approval
              </label>
            </div>
          </Collapsible>
        </>
      )}

      {selectedNode.type === 'decision' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Decision Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Default Target</label>
            <input value={String(selectedNode.data?.defaultTarget || '')} onChange={(e) => { onUpdate(selectedNode.id, { defaultTarget: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
          </div>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Branches (condition → target)</label>
            <textarea value={JSON.stringify(selectedNode.data?.branches || [], null, 2)} onChange={(e) => { try { onUpdate(selectedNode.id, { branches: JSON.parse(e.target.value) }); markDirty(); } catch (err) { /* ignore */ } }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-24 font-mono" />
            <p className="text-xs text-gray-500 mt-1">Format: condition -&gt; target (e.g. status == SUCCESS)</p>
          </div>
        </div>
      )}

      {selectedNode.type === 'subworkflow' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Sub-Workflow Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Workflow Ref</label>
            <input value={String(selectedNode.data?.workflowRef || '')} onChange={(e) => { onUpdate(selectedNode.id, { workflowRef: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="workflow-id" />
          </div>
        </div>
      )}

      {selectedNode.type === 'loop' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Loop Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Max Iterations</label>
            <input type="number" value={Number(selectedNode.data?.maxIterations || 3)} onChange={(e) => { onUpdate(selectedNode.id, { maxIterations: parseInt(e.target.value) || 3 }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
          </div>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Exit Condition</label>
            <input value={String(selectedNode.data?.exitCondition || '')} onChange={(e) => { onUpdate(selectedNode.id, { exitCondition: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="contains:done or status == 'SUCCESS'" />
          </div>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Body Steps (comma-separated node IDs)</label>
            <input value={String((selectedNode.data?.bodySteps as string[] || []).join(', '))} onChange={(e) => { onUpdate(selectedNode.id, { bodySteps: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="node_a, node_b" />
          </div>
        </div>
      )}

      {selectedNode.type === 'join' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Join Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Join Type</label>
            <select value={String(selectedNode.data?.joinType || 'all')} onChange={(e) => { onUpdate(selectedNode.id, { joinType: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm">
              <option value="all">All</option>
              <option value="any">Any</option>
              <option value="n">N of M</option>
            </select>
          </div>
          {String(selectedNode.data?.joinType) === 'n' && (
            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">N (of M)</label>
              <input type="number" value={Number(selectedNode.data?.joinN || 2)} onChange={(e) => { onUpdate(selectedNode.id, { joinN: parseInt(e.target.value) || 2 }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>
          )}
        </div>
      )}

      {selectedNode.type === 'fork' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Fork Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Branch Targets (comma-separated node IDs)</label>
            <input value={String(selectedNode.data?.defaultTarget || '')} onChange={(e) => { onUpdate(selectedNode.id, { defaultTarget: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="node_a, node_b" />
          </div>
        </div>
      )}

      {selectedNode.type === 'hitl' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">HITL Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Assignee</label>
            <input value={String(selectedNode.data?.assignee || 'human')} onChange={(e) => { onUpdate(selectedNode.id, { assignee: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="human" />
          </div>
        </div>
      )}

      {selectedNode.type === 'llm_eval' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">LLM Eval Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Prompt Template</label>
            <textarea value={String(selectedNode.data?.title || '')} onChange={(e) => { onUpdate(selectedNode.id, { title: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-20" placeholder="Evaluate: ${lastResult}" />
            <p className="text-xs text-gray-500 mt-1">Data-Piping: ${'node_id.output.field'}</p>
          </div>
          <div className="mb-3">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={Boolean(selectedNode.data?.jsonOutput ?? true)} onChange={(e) => { onUpdate(selectedNode.id, { jsonOutput: e.target.checked }); markDirty(); }} />
              JSON Output (structured)
            </label>
          </div>
        </div>
      )}

      {selectedNode.type === 'llm_summarize' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">LLM Summarize Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Input Source</label>
            <input value={String(selectedNode.data?.defaultTarget || '')} onChange={(e) => { onUpdate(selectedNode.id, { defaultTarget: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="${node_1.output} or ${node_1.output.data}" />
            <p className="text-xs text-gray-500 mt-1">Data-Piping: ${'node_id.output.field'}</p>
          </div>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Prompt Template</label>
            <textarea value={String(selectedNode.data?.title || '')} onChange={(e) => { onUpdate(selectedNode.id, { title: e.target.value }); markDirty(); }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-16" placeholder="Summarize the following:" />
          </div>
        </div>
      )}

      <div className="flex gap-2">
        <button onClick={handleSave} className="flex-1 px-3 py-2 bg-blue-600 rounded text-sm font-bold" disabled={!isDirty}>
          Save {isDirty ? '●' : ''}
        </button>
        <button onClick={handleReset} className="px-3 py-2 bg-gray-600 rounded text-sm" disabled={!isDirty} title="Änderungen verwerfen">
          ↺ Reset
        </button>
        {selectedNode.type === 'agent' && (
          <button onClick={handleCopy} className="px-3 py-2 bg-gray-600 rounded text-sm" title="Agent-Config kopieren">
            📋 Copy
          </button>
        )}
      </div>
    </div>
  );
}