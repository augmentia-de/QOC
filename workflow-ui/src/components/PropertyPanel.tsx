import { useState } from 'react';

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

const EMPTY_AGENT: AgentConfig = {
  id: '',
  name: '',
  version: '1.0.0',
  category: '',
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
  systemPrompt: '',
};

interface PropertyPanelProps {
  selectedNode: { id: string; type: string; data: Record<string, unknown> } | null;
  onUpdate: (nodeId: string, data: Record<string, unknown>) => void;
  agents?: AgentConfig[];
  onAgentChange?: (agent: AgentConfig) => void;
  onCopyConfig?: (agent: AgentConfig) => void;
}

export function PropertyPanel({ selectedNode, onUpdate, agents = [], onAgentChange, onCopyConfig }: PropertyPanelProps) {
  const [title, setTitle] = useState(String(selectedNode?.data?.title || ''));
  const [agentRef, setAgentRef] = useState(String(selectedNode?.data?.agentRef || ''));
  const [requiresHITL, setRequiresHITL] = useState(Boolean(selectedNode?.data?.requiresHumanApproval));

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

  // Load agent config when agentRef changes
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
    }
  };

  const applyBuildAgent = () => {
    setAgentName(BUILD_AGENT.name);
    setAgentVersion(BUILD_AGENT.version);
    setAgentCategory(BUILD_AGENT.category);
    setModel(BUILD_AGENT.model);
    setTemperature(BUILD_AGENT.temperature);
    setMaxTokens(BUILD_AGENT.maxTokens);
    setTopP(BUILD_AGENT.topP);
    setSystemPrompt(BUILD_AGENT.systemPrompt);
    setJsonOutput(BUILD_AGENT.jsonOutput);
    setActive(BUILD_AGENT.active);
    setTools(BUILD_AGENT.tools.join(', '));
    setGuardrailsInput(BUILD_AGENT.guardrailsInput.join(', '));
    setGuardrailsOutput(BUILD_AGENT.guardrailsOutput.join(', '));
    setHooks(BUILD_AGENT.hooks.join(', '));
    setAgentRef(BUILD_AGENT.id);
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
    onUpdate(selectedNode.id, {
      title,
      agentRef,
      requiresHumanApproval: requiresHITL,
    });
    if (onAgentChange) {
      onAgentChange(getCurrentAgent());
    }
  };

  const handleCopy = () => {
    const agent = getCurrentAgent();
    navigator.clipboard.writeText(JSON.stringify(agent, null, 2));
    if (onCopyConfig) onCopyConfig(agent);
    alert('Agent-Config kopiert');
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
      <h3 className="font-bold mb-4">Properties: {selectedNode.id}</h3>

      <div className="mb-4">
        <label className="block text-sm text-gray-400 mb-1">Title</label>
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
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

          <div className="mb-4 border-t border-gray-700 pt-4">
            <h4 className="font-semibold text-sm mb-2">Agent Config (QocEngine.AgentConfig)</h4>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Name</label>
              <input value={agentName} onChange={(e) => setAgentName(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Version</label>
              <input value={agentVersion} onChange={(e) => setAgentVersion(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Category</label>
              <input value={agentCategory} onChange={(e) => setAgentCategory(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Model</label>
              <select value={model} onChange={(e) => setModel(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm">
                {MODELS.map((m) => (<option key={m} value={m}>{m}</option>))}
              </select>
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Temperature: {temperature}</label>
              <input type="range" min="0" max="2" step="0.1" value={temperature} onChange={(e) => setTemperature(parseFloat(e.target.value))} className="w-full" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Max Tokens</label>
              <input type="number" value={maxTokens} onChange={(e) => setMaxTokens(parseInt(e.target.value) || 4096)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Top P: {topP}</label>
              <input type="range" min="0" max="1" step="0.05" value={topP} onChange={(e) => setTopP(parseFloat(e.target.value))} className="w-full" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">System Prompt</label>
              <textarea value={systemPrompt} onChange={(e) => setSystemPrompt(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-24" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Tools (comma-separated)</label>
              <input value={tools} onChange={(e) => setTools(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="Read, Write, Edit, Glob, Grep" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Guardrails Input (comma-separated)</label>
              <input value={guardrailsInput} onChange={(e) => setGuardrailsInput(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Guardrails Output (comma-separated)</label>
              <input value={guardrailsOutput} onChange={(e) => setGuardrailsOutput(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="block text-sm text-gray-400 mb-1">Hooks (comma-separated)</label>
              <input value={hooks} onChange={(e) => setHooks(e.target.value)} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
            </div>

            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={jsonOutput} onChange={(e) => setJsonOutput(e.target.checked)} />
                JSON Output
              </label>
            </div>

            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
                Active
              </label>
            </div>

            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={requiresHITL} onChange={(e) => setRequiresHITL(e.target.checked)} />
                Requires Human Approval
              </label>
            </div>
          </div>
        </>
      )}

      {selectedNode.type === 'decision' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Decision Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Default Target</label>
            <input value={String(selectedNode.data?.defaultTarget || '')} onChange={(e) => onUpdate(selectedNode.id, { defaultTarget: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
          </div>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Branches (JSON)</label>
            <textarea value={JSON.stringify(selectedNode.data?.branches || [], null, 2)} onChange={(e) => { try { onUpdate(selectedNode.id, { branches: JSON.parse(e.target.value) }); } catch {} }} className="w-full px-2 py-1 bg-gray-700 rounded text-sm h-24 font-mono" />
          </div>
        </div>
      )}

      {selectedNode.type === 'subworkflow' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Sub-Workflow Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Workflow Ref</label>
            <input value={String(selectedNode.data?.workflowRef || '')} onChange={(e) => onUpdate(selectedNode.id, { workflowRef: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="workflow-id" />
          </div>
        </div>
      )}

      {selectedNode.type === 'loop' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Loop Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Max Iterations</label>
            <input type="number" value={Number(selectedNode.data?.maxIterations || 3)} onChange={(e) => onUpdate(selectedNode.id, { maxIterations: parseInt(e.target.value) || 3 })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" />
          </div>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Exit Condition</label>
            <input value={String(selectedNode.data?.exitCondition || '')} onChange={(e) => onUpdate(selectedNode.id, { exitCondition: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm" placeholder="contains:done" />
          </div>
        </div>
      )}

      {selectedNode.type === 'join' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="font-semibold text-sm mb-2">Join Config</h4>
          <div className="mb-3">
            <label className="block text-sm text-gray-400 mb-1">Join Type</label>
            <select value={String(selectedNode.data?.joinType || 'all')} onChange={(e) => onUpdate(selectedNode.id, { joinType: e.target.value })} className="w-full px-2 py-1 bg-gray-700 rounded text-sm">
              <option value="all">All</option>
              <option value="any">Any</option>
              <option value="n">N of M</option>
            </select>
          </div>
        </div>
      )}

      <div className="flex gap-2">
        <button onClick={handleSave} className="flex-1 px-3 py-2 bg-blue-600 rounded text-sm font-bold">
          Save
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
