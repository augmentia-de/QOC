import { useEffect, useMemo, useState } from 'react';
import { McpClientApi, ToolApi, WorkflowApi } from '../services/api';
import type { McpServerInfo, McpToolInfo } from '../services/api';
import type { Branch } from '../types/workflow';

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
  responseSchema?: Record<string, unknown> | null;
}

interface NodeOption {
  id: string;
  type: string;
  data: Record<string, unknown>;
}

interface EdgeOption {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

const MODELS = ['deepseek-v4.1-flash', 'gpt-4o', 'gpt-4o-mini', 'claude-3.5-sonnet'];
const TOOL_OPTIONS = ['Read', 'Write', 'Edit', 'Glob', 'Grep', 'Patch', 'WebFetch', 'Shell'];
const GUARDRAIL_OPTIONS = ['pii', 'sql-injection', 'prompt-injection'];
const HOOK_OPTIONS = ['pii-masking', 'audit'];
const ASSIGNEE_OPTIONS = ['human', 'reviewer', 'admin', 'operator'];

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
  responseSchema: null,
};

interface PropertyPanelProps {
  selectedNode: { id: string; type: string; data: Record<string, unknown> } | null;
  onUpdate: (nodeId: string, data: Record<string, unknown>) => void;
  nodes?: NodeOption[];
  edges?: EdgeOption[];
  agents?: AgentConfig[];
  onAgentChange?: (agent: AgentConfig) => void | Promise<void>;
  onCopyConfig?: (agent: AgentConfig) => void;
}

function asString(value: unknown, fallback = ''): string {
  return value === undefined || value === null ? fallback : String(value);
}

function asNumber(value: unknown, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function asArray(value: unknown): any[] {
  return Array.isArray(value) ? value : [];
}

function FieldLabel({ children }: { children: React.ReactNode }) {
  return <label className="mb-1 block text-sm text-gray-400">{children}</label>;
}

function SelectField({
  label,
  value,
  options,
  onChange,
  emptyLabel,
  hint,
}: {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  emptyLabel?: string;
  hint?: string;
}) {
  return (
    <div className="mb-3">
      <FieldLabel>{label}</FieldLabel>
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
      >
        {emptyLabel !== undefined && <option value="">{emptyLabel}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
        {value && !options.some((option) => option.value === value) && (
          <option value={value}>{value} (unbekannt)</option>
        )}
      </select>
      {hint && <p className="mt-1 text-xs text-gray-500">{hint}</p>}
    </div>
  );
}

function NodeSelectField({
  label,
  value,
  nodes,
  excludeId,
  onChange,
  hint,
}: {
  label: string;
  value: string;
  nodes: NodeOption[];
  excludeId?: string;
  onChange: (value: string) => void;
  hint?: string;
}) {
  return (
    <SelectField
      label={label}
      value={value}
      emptyLabel="– Ziel –"
      onChange={onChange}
      hint={hint}
      options={nodes
        .filter((node) => node.id !== excludeId)
        .map((node) => ({ value: node.id, label: `${node.id} (${node.type})` }))}
    />
  );
}

function CheckboxList({
  label,
  options,
  values,
  onChange,
  hint,
}: {
  label: string;
  options: { value: string; label: string }[];
  values: string[];
  onChange: (values: string[]) => void;
  hint?: string;
}) {
  const selected = new Set(values);
  return (
    <div className="mb-3">
      <FieldLabel>{label}</FieldLabel>
      <div className="max-h-36 space-y-1 overflow-y-auto rounded border border-gray-700 p-2">
        {options.length === 0 && <p className="text-xs text-gray-500">Keine Optionen verfügbar.</p>}
        {options.map((option) => (
          <label key={option.value} className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={selected.has(option.value)}
              onChange={(event) => {
                const next = new Set(selected);
                if (event.target.checked) next.add(option.value);
                else next.delete(option.value);
                onChange(Array.from(next));
              }}
            />
            {option.label}
          </label>
        ))}
      </div>
      {hint && <p className="mt-1 text-xs text-gray-500">{hint}</p>}
    </div>
  );
}

function BranchListEditor({
  branches,
  nodes,
  excludeId,
  onChange,
}: {
  branches: Branch[];
  nodes: NodeOption[];
  excludeId: string;
  onChange: (branches: Branch[]) => void;
}) {
  const list = Array.isArray(branches) ? branches : [];
  const targetOptions = nodes.filter((node) => node.id !== excludeId);

  const updateBranch = (index: number, patch: Partial<Branch>) => {
    onChange(list.map((branch, current) => (current === index ? { ...branch, ...patch } : branch)));
  };

  const addBranch = () => {
    const numbers = list
      .map((branch) => Number.parseInt(String(branch.branchId || '').replace(/\D/g, ''), 10))
      .filter((value) => Number.isFinite(value));
    const next = (numbers.length > 0 ? Math.max(...numbers) : 0) + 1;
    onChange([...list, { branchId: `b${next}`, condition: '', target: '' }]);
  };

  return (
    <div className="mb-3">
      <div className="mb-1 flex items-center justify-between">
        <FieldLabel>Branches</FieldLabel>
        <button
          type="button"
          onClick={addBranch}
          className="rounded bg-gray-700 px-2 py-0.5 text-xs hover:bg-gray-600"
        >
          + Branch
        </button>
      </div>
      {list.length === 0 && (
        <p className="mb-2 text-xs text-gray-500">
          Noch keine Branches. Jeder Branch erhält einen eigenen Output-Handle.
        </p>
      )}
      <div className="space-y-2">
        {list.map((branch, index) => (
          <div key={branch.branchId || index} className="space-y-1 rounded border border-gray-700 p-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-[10px] text-gray-500">{branch.branchId}</span>
              <button
                type="button"
                onClick={() => onChange(list.filter((_, current) => current !== index))}
                className="ml-auto text-xs text-red-400 hover:text-red-300"
              >
                entfernen
              </button>
            </div>
            <input
              value={asString(branch.condition)}
              onChange={(event) => updateBranch(index, { condition: event.target.value })}
              placeholder="condition, z. B. status == 'SUCCESS'"
              className="w-full rounded bg-gray-700 px-2 py-1 text-xs"
            />
            <select
              value={asString(branch.target)}
              onChange={(event) => updateBranch(index, { target: event.target.value })}
              className="w-full rounded bg-gray-700 px-2 py-1 text-xs"
            >
              <option value="">– Ziel –</option>
              {targetOptions.map((node) => (
                <option key={node.id} value={node.id}>
                  {node.id} ({node.type})
                </option>
              ))}
              {branch.target && !targetOptions.some((node) => node.id === branch.target) && (
                <option value={branch.target}>{branch.target}</option>
              )}
            </select>
          </div>
        ))}
      </div>
      <p className="mt-1 text-xs text-gray-500">
        Der Output-Handle wird als <code>branch-&lt;id&gt;</code> gespeichert und als Edge-Label angezeigt.
      </p>
    </div>
  );
}

function MultiSelect({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: string[];
  value: string;
  onChange: (value: string) => void;
}) {
  const selected = value
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean);
  return (
    <div className="mb-3">
      <FieldLabel>{label}</FieldLabel>
      <div className="mb-1 flex flex-wrap gap-2">
        {options.map((option) => (
          <label key={option} className="flex items-center gap-1 text-xs">
            <input
              type="checkbox"
              checked={selected.includes(option)}
              onChange={(event) => {
                let next = [...selected];
                if (event.target.checked) {
                  if (!next.includes(option)) next.push(option);
                } else {
                  next = next.filter((entry) => entry !== option);
                }
                onChange(next.join(', '));
              }}
            />
            {option}
          </label>
        ))}
      </div>
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
        placeholder="custom, ..."
      />
    </div>
  );
}

function Collapsible({
  title,
  children,
  defaultOpen = true,
}: {
  title: string;
  children: React.ReactNode;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="mb-4 border-t border-gray-700 pt-4">
      <button
        onClick={() => setOpen(!open)}
        className="mb-2 flex w-full items-center gap-1 text-left text-sm font-semibold"
      >
        <span>{open ? '▼' : '▶'}</span> {title}
      </button>
      {open && <div>{children}</div>}
    </div>
  );
}

function ChipList({ label, values, empty }: { label: string; values: string[]; empty: string }) {
  return (
    <div className="mb-3">
      <FieldLabel>{label}</FieldLabel>
      <div className="flex flex-wrap gap-1">
        {values.length === 0 && <span className="text-xs text-gray-500">{empty}</span>}
        {values.map((value, index) => (
          <span
            key={`${value}-${index}`}
            className="rounded bg-gray-700 px-2 py-0.5 font-mono text-[10px] text-gray-200"
          >
            {value}
          </span>
        ))}
      </div>
    </div>
  );
}

export function PropertyPanel({
  selectedNode,
  onUpdate,
  nodes = [],
  edges = [],
  agents = [],
  onAgentChange,
  onCopyConfig,
}: PropertyPanelProps) {
  const [title, setTitle] = useState('');
  const [agentRef, setAgentRef] = useState('');
  const [requiresHITL, setRequiresHITL] = useState(false);
  const [draft, setDraft] = useState<Record<string, unknown>>({});
  const [isDirty, setIsDirty] = useState(false);
  const [mcpArgsText, setMcpArgsText] = useState('{}');

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
  const [responseSchemaText, setResponseSchemaText] = useState('');
  const [active, setActive] = useState(true);
  const [tools, setTools] = useState('');
  const [guardrailsInput, setGuardrailsInput] = useState('');
  const [guardrailsOutput, setGuardrailsOutput] = useState('');
  const [hooks, setHooks] = useState('');

  // Options loaded from the backend
  const [workflows, setWorkflows] = useState<string[]>([]);
  const [mcpServers, setMcpServers] = useState<McpServerInfo[]>([]);
  const [mcpTools, setMcpTools] = useState<McpToolInfo[]>([]);
  const [toolOptions, setToolOptions] = useState<string[]>(TOOL_OPTIONS);

  const markDirty = () => setIsDirty(true);

  const updateField = (key: string, value: unknown) => {
    setDraft((previous) => ({ ...previous, [key]: value }));
    markDirty();
  };

  const draftString = (key: string, fallback = '') => asString(draft[key], fallback);
  const draftNumber = (key: string, fallback: number) => asNumber(draft[key], fallback);
  const draftList = (key: string) => asArray(draft[key]);

  const mcpServer = draftString('mcpServer');
  const mcpArgsValid = useMemo(() => {
    try {
      JSON.parse(mcpArgsText.trim() || '{}');
      return true;
    } catch {
      return false;
    }
  }, [mcpArgsText]);

  const loadAgentFields = (agent: AgentConfig | undefined) => {
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
      setResponseSchemaText(agent.responseSchema ? JSON.stringify(agent.responseSchema, null, 2) : '');
      setActive(agent.active);
      setTools(agent.tools.join(', '));
      setGuardrailsInput(agent.guardrailsInput.join(', '));
      setGuardrailsOutput(agent.guardrailsOutput.join(', '));
      setHooks(agent.hooks.join(', '));
      return;
    }
    setAgentName('');
    setAgentVersion('1.0.0');
    setAgentCategory('');
    setModel('deepseek-v4.1-flash');
    setTemperature(0.3);
    setMaxTokens(4096);
    setTopP(1.0);
    setSystemPrompt('');
    setJsonOutput(false);
    setResponseSchemaText('');
    setActive(true);
    setTools('');
    setGuardrailsInput('');
    setGuardrailsOutput('');
    setHooks('');
  };

  // Load dropdown options once. Failures are non-fatal: the panel remains usable.
  useEffect(() => {
    WorkflowApi.list()
      .then((response) => setWorkflows(response.data.workflows || []))
      .catch(() => setWorkflows([]));
    McpClientApi.listServers()
      .then((response) => setMcpServers(response.data.servers || []))
      .catch(() => setMcpServers([]));
    ToolApi.list()
      .then((response) =>
        setToolOptions((previous) => Array.from(new Set([...previous, ...response.data.tools.map((tool) => tool.name)]))),
      )
      .catch(() => undefined);
  }, []);

  // Load the tool list of the selected MCP server.
  useEffect(() => {
    if (!mcpServer) {
      setMcpTools([]);
      return;
    }
    McpClientApi.getServerTools(mcpServer)
      .then((response) => setMcpTools(response.data.tools || []))
      .catch(() => setMcpTools([]));
  }, [mcpServer]);

  // Reset the node draft whenever a different node is selected or the graph
  // updates the selected node (e.g. after a decision edge was connected).
  useEffect(() => {
    if (!selectedNode) return;
    const data = { ...(selectedNode.data ?? {}) };
    setDraft(data);
    setTitle(asString(data.title));
    setAgentRef(asString(data.agentRef));
    setRequiresHITL(Boolean(data.requiresHumanApproval));
    setMcpArgsText(data.mcpArgs ? JSON.stringify(data.mcpArgs, null, 2) : '{}');
    setIsDirty(false);
  }, [selectedNode]);

  // Agent fields follow the selected agent reference.
  useEffect(() => {
    if (!selectedNode) return;
    const agent = agents.find(
      (candidate) => candidate.id === asString(selectedNode.data?.agentRef),
    );
    loadAgentFields(agent);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNode?.id, selectedNode?.data?.agentRef, agents]);

  const loadAgent = (ref: string) => {
    setAgentRef(ref);
    loadAgentFields(agents.find((candidate) => candidate.id === ref));
    markDirty();
  };

  const applyBuildAgent = () => {
    setAgentRef('build');
    loadAgentFields(BUILD_AGENT);
    markDirty();
  };

  const getCurrentAgent = (): AgentConfig => ({
    id: agentRef || '',
    name: agentName || agentRef,
    version: agentVersion,
    category: agentCategory,
    model,
    temperature,
    maxTokens,
    topP,
    tools: tools.split(',').map((entry) => entry.trim()).filter(Boolean),
    guardrailsInput: guardrailsInput.split(',').map((entry) => entry.trim()).filter(Boolean),
    guardrailsOutput: guardrailsOutput.split(',').map((entry) => entry.trim()).filter(Boolean),
    hooks: hooks.split(',').map((entry) => entry.trim()).filter(Boolean),
    jsonOutput,
    active,
    systemPrompt,
    responseSchema: responseSchemaText.trim()
      ? (() => {
          try {
            return JSON.parse(responseSchemaText);
          } catch {
            return null;
          }
        })()
      : null,
  });

  const handleSave = async () => {
    if (!selectedNode) return;

    let mcpArgs = draft.mcpArgs;
    if (selectedNode.type === 'mcp_call') {
      try {
        mcpArgs = mcpArgsText.trim() ? JSON.parse(mcpArgsText) : {};
      } catch {
        alert('MCP Args ist kein gültiges JSON.');
        return;
      }
    }

    const nodeData: Record<string, unknown> = {
      ...draft,
      title,
      agentRef,
      requiresHumanApproval: requiresHITL,
    };
    if (selectedNode.type === 'mcp_call') nodeData.mcpArgs = mcpArgs;

    onUpdate(selectedNode.id, nodeData);
    if (selectedNode.type === 'agent' && onAgentChange) {
      await onAgentChange(getCurrentAgent());
    }
    setIsDirty(false);
  };

  const handleReset = () => {
    if (!selectedNode) return;
    const data = { ...(selectedNode.data ?? {}) };
    setDraft(data);
    setTitle(asString(data.title));
    setAgentRef(asString(data.agentRef));
    setRequiresHITL(Boolean(data.requiresHumanApproval));
    setMcpArgsText(data.mcpArgs ? JSON.stringify(data.mcpArgs, null, 2) : '{}');
    loadAgentFields(agents.find((candidate) => candidate.id === asString(data.agentRef)));
    setIsDirty(false);
  };

  const handleMcpArgsChange = (text: string) => {
    setMcpArgsText(text);
    markDirty();
    try {
      const parsed = text.trim() ? JSON.parse(text) : {};
      setDraft((previous) => ({ ...previous, mcpArgs: parsed }));
    } catch {
      // Keep the last valid args in the draft; Save blocks on invalid JSON.
    }
  };

  const handleCopy = () => {
    const agent = getCurrentAgent();
    navigator.clipboard.writeText(JSON.stringify(agent, null, 2));
    if (onCopyConfig) onCopyConfig(agent);
  };

  if (!selectedNode) {
    return (
      <div className="w-80 border-l border-gray-700 bg-gray-800 p-4">
        <p className="text-sm text-gray-400">Node auswählen</p>
      </div>
    );
  }

  const outgoingTargets = edges
    .filter((edge) => edge.source === selectedNode.id)
    .map((edge) => edge.target);
  const incomingSources = edges
    .filter((edge) => edge.target === selectedNode.id)
    .map((edge) => edge.source);
  const nodeOptions = nodes.filter((node) => node.id !== selectedNode.id);
  const agentCategories = Array.from(new Set(agents.map((agent) => agent.category).filter(Boolean)));

  return (
    <div className="w-80 overflow-y-auto border-l border-gray-700 bg-gray-800 p-4">
      <h3 className="mb-4 font-bold">
        Properties: {selectedNode.id}
        {isDirty && <span className="ml-2 text-xs text-amber-400">● ungespeichert</span>}
      </h3>

      <div className="mb-4">
        <FieldLabel>Title</FieldLabel>
        <input
          value={title}
          onChange={(event) => {
            setTitle(event.target.value);
            markDirty();
          }}
          className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
        />
      </div>

      {selectedNode.type === 'agent' && (
        <>
          <div className="mb-4">
            <FieldLabel>Agent-Ref</FieldLabel>
            <div className="flex gap-2">
              <select
                value={agentRef}
                onChange={(event) => loadAgent(event.target.value)}
                className="flex-1 rounded bg-gray-700 px-2 py-1 text-sm"
              >
                <option value="">-- neu --</option>
                {agents.map((agent) => (
                  <option key={agent.id} value={agent.id}>
                    {agent.id} ({agent.name})
                  </option>
                ))}
                {agentRef && !agents.some((agent) => agent.id === agentRef) && (
                  <option value={agentRef}>{agentRef} (unbekannt)</option>
                )}
              </select>
              <button
                onClick={applyBuildAgent}
                className="rounded bg-amber-600 px-2 py-1 text-xs"
                title="Build Agent laden"
              >
                Build
              </button>
            </div>
          </div>

          <Collapsible title="Agent Config (QocEngine.AgentConfig)" defaultOpen={false}>
            <div className="mb-3">
              <FieldLabel>Name</FieldLabel>
              <input
                value={agentName}
                onChange={(event) => {
                  setAgentName(event.target.value);
                  markDirty();
                }}
                className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              />
            </div>
            <div className="mb-3">
              <FieldLabel>Version</FieldLabel>
              <input
                value={agentVersion}
                onChange={(event) => {
                  setAgentVersion(event.target.value);
                  markDirty();
                }}
                className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              />
            </div>
            <div className="mb-3">
              <FieldLabel>Category</FieldLabel>
              <input
                list="agent-category-options"
                value={agentCategory}
                onChange={(event) => {
                  setAgentCategory(event.target.value);
                  markDirty();
                }}
                className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              />
              <datalist id="agent-category-options">
                {agentCategories.map((category) => (
                  <option key={category} value={category} />
                ))}
              </datalist>
            </div>
            <div className="mb-3">
              <FieldLabel>Model</FieldLabel>
              <select
                value={model}
                onChange={(event) => {
                  setModel(event.target.value);
                  markDirty();
                }}
                className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              >
                {MODELS.map((entry) => (
                  <option key={entry} value={entry}>
                    {entry}
                  </option>
                ))}
              </select>
            </div>
            <div className="mb-3">
              <FieldLabel>Temperature: {temperature}</FieldLabel>
              <input
                type="range"
                min="0"
                max="2"
                step="0.1"
                value={temperature}
                onChange={(event) => {
                  setTemperature(parseFloat(event.target.value));
                  markDirty();
                }}
                className="w-full"
              />
            </div>
            <div className="mb-3">
              <FieldLabel>Max Tokens</FieldLabel>
              <input
                type="number"
                value={maxTokens}
                onChange={(event) => {
                  setMaxTokens(parseInt(event.target.value) || 4096);
                  markDirty();
                }}
                className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              />
            </div>
            <div className="mb-3">
              <FieldLabel>Top P: {topP}</FieldLabel>
              <input
                type="range"
                min="0"
                max="1"
                step="0.05"
                value={topP}
                onChange={(event) => {
                  setTopP(parseFloat(event.target.value));
                  markDirty();
                }}
                className="w-full"
              />
            </div>
            <div className="mb-3">
              <FieldLabel>System Prompt</FieldLabel>
              <textarea
                value={systemPrompt}
                onChange={(event) => {
                  setSystemPrompt(event.target.value);
                  markDirty();
                }}
                className="h-24 w-full rounded bg-gray-700 px-2 py-1 text-sm"
                placeholder="You are a helpful agent. Use ${node_1.output.field} for data-piping."
              />
              <p className="mt-1 text-xs text-gray-500">
                Data-Piping: ${'node_id.output.field'} für Werte aus Vorgänger-Nodes
              </p>
            </div>
            <MultiSelect
              label="Tools"
              options={toolOptions}
              value={tools}
              onChange={(value) => {
                setTools(value);
                markDirty();
              }}
            />
            <MultiSelect
              label="Guardrails Input"
              options={GUARDRAIL_OPTIONS}
              value={guardrailsInput}
              onChange={(value) => {
                setGuardrailsInput(value);
                markDirty();
              }}
            />
            <MultiSelect
              label="Guardrails Output"
              options={GUARDRAIL_OPTIONS}
              value={guardrailsOutput}
              onChange={(value) => {
                setGuardrailsOutput(value);
                markDirty();
              }}
            />
            <MultiSelect
              label="Hooks"
              options={HOOK_OPTIONS}
              value={hooks}
              onChange={(value) => {
                setHooks(value);
                markDirty();
              }}
            />
            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={jsonOutput}
                  onChange={(event) => {
                    setJsonOutput(event.target.checked);
                    markDirty();
                  }}
                />
                JSON Output
              </label>
            </div>
            <div className="mb-3">
              <FieldLabel>Response Schema (JSON Schema)</FieldLabel>
              <textarea
                value={responseSchemaText}
                onChange={(event) => {
                  setResponseSchemaText(event.target.value);
                  markDirty();
                }}
                className="h-32 w-full rounded bg-gray-700 px-2 py-1 font-mono text-sm"
                placeholder='{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}'
              />
              <p className="mt-1 text-xs text-gray-500">
                Strukturierte Ausgabe des Agents. Leer = keine.
              </p>
            </div>
            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={active}
                  onChange={(event) => {
                    setActive(event.target.checked);
                    markDirty();
                  }}
                />
                Active
              </label>
            </div>
            <div className="mb-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={requiresHITL}
                  onChange={(event) => {
                    setRequiresHITL(event.target.checked);
                    markDirty();
                  }}
                />
                Requires Human Approval
              </label>
            </div>
          </Collapsible>
        </>
      )}

      {selectedNode.type === 'decision' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">Decision Config</h4>
          <NodeSelectField
            label="Default Target"
            value={draftString('defaultTarget')}
            nodes={nodeOptions}
            onChange={(value) => updateField('defaultTarget', value)}
            hint="Wird verwendet, wenn keine Branch-Condition zutrifft."
          />
          <BranchListEditor
            branches={draftList('branches') as Branch[]}
            nodes={nodes}
            excludeId={selectedNode.id}
            onChange={(branches) => updateField('branches', branches)}
          />
        </div>
      )}

      {selectedNode.type === 'subworkflow' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">Sub-Workflow Config</h4>
          <SelectField
            label="Workflow Ref"
            value={draftString('workflowRef')}
            options={workflows.map((workflow) => ({ value: workflow, label: workflow }))}
            onChange={(value) => updateField('workflowRef', value)}
            emptyLabel="– Workflow –"
            hint={workflows.length === 0 ? 'Keine Workflows geladen.' : undefined}
          />
        </div>
      )}

      {selectedNode.type === 'loop' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">Loop Config</h4>
          <div className="mb-3">
            <FieldLabel>Max Iterations</FieldLabel>
            <input
              type="number"
              min={1}
              value={draftNumber('maxIterations', 3)}
              onChange={(event) =>
                updateField('maxIterations', parseInt(event.target.value) || 3)
              }
              className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
            />
          </div>
          <div className="mb-3">
            <FieldLabel>Exit Condition</FieldLabel>
            <input
              value={draftString('exitCondition')}
              onChange={(event) => updateField('exitCondition', event.target.value)}
              className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              placeholder="contains:done oder status == 'SUCCESS'"
            />
          </div>
          <CheckboxList
            label="Body Steps"
            options={nodeOptions.map((node) => ({
              value: node.id,
              label: `${node.id} (${node.type})`,
            }))}
            values={draftList('bodySteps').map(String)}
            onChange={(values) => updateField('bodySteps', values)}
            hint="Die Nodes werden pro Iteration in dieser Reihenfolge ausgeführt."
          />
        </div>
      )}

      {selectedNode.type === 'join' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">Join Config</h4>
          <SelectField
            label="Join Type"
            value={draftString('joinType', 'all')}
            options={[
              { value: 'all', label: 'All' },
              { value: 'any', label: 'Any' },
              { value: 'n', label: 'N of M' },
            ]}
            onChange={(value) => updateField('joinType', value)}
            emptyLabel={undefined}
          />
          {draftString('joinType', 'all') === 'n' && (
            <div className="mb-3">
              <FieldLabel>N (of M)</FieldLabel>
              <input
                type="number"
                min={1}
                max={Math.max(1, incomingSources.length)}
                value={draftNumber('joinN', 2)}
                onChange={(event) => updateField('joinN', parseInt(event.target.value) || 2)}
                className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              />
            </div>
          )}
          <ChipList
            label="Eingehende Kanten"
            values={incomingSources}
            empty="Noch keine eingehenden Kanten."
          />
        </div>
      )}

      {selectedNode.type === 'fork' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">Fork Config</h4>
          <ChipList
            label="Ausgehende Kanten"
            values={outgoingTargets}
            empty="Noch keine ausgehenden Kanten."
          />
          <p className="text-xs text-gray-500">
            Neue Ausgänge über die <code>Out +</code>-Handles im Graph verbinden.
          </p>
        </div>
      )}

      {selectedNode.type === 'hitl' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">HITL Config</h4>
          <div className="mb-3">
            <FieldLabel>Assignee</FieldLabel>
            <input
              list="assignee-options"
              value={draftString('assignee', 'human')}
              onChange={(event) => updateField('assignee', event.target.value)}
              className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              placeholder="human"
            />
            <datalist id="assignee-options">
              {ASSIGNEE_OPTIONS.map((assignee) => (
                <option key={assignee} value={assignee} />
              ))}
            </datalist>
          </div>
        </div>
      )}

      {selectedNode.type === 'llm_eval' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">LLM Eval Config</h4>
          <div className="mb-3">
            <FieldLabel>Prompt Template</FieldLabel>
            <textarea
              value={title}
              onChange={(event) => {
                setTitle(event.target.value);
                markDirty();
              }}
              className="h-20 w-full rounded bg-gray-700 px-2 py-1 text-sm"
              placeholder="Evaluate: ${lastResult}"
            />
            <p className="mt-1 text-xs text-gray-500">
              Data-Piping: ${'node_id.output.field'}
            </p>
          </div>
          <div className="mb-3">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={Boolean(draft.jsonOutput ?? true)}
                onChange={(event) => {
                  updateField('jsonOutput', event.target.checked);
                  markDirty();
                }}
              />
              JSON Output (structured)
            </label>
          </div>
        </div>
      )}

      {selectedNode.type === 'llm_summarize' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">LLM Summarize Config</h4>
          <div className="mb-3">
            <FieldLabel>Input Source</FieldLabel>
            <input
              list="llm-input-options"
              value={draftString('defaultTarget')}
              onChange={(event) => updateField('defaultTarget', event.target.value)}
              className="w-full rounded bg-gray-700 px-2 py-1 text-sm"
              placeholder="${node_1.output} oder ${lastResult}"
            />
            <datalist id="llm-input-options">
              <option value="${lastResult}" />
              {nodeOptions.flatMap((node) => [
                <option key={`${node.id}-output`} value={`\${${node.id}.output}`} />,
                <option key={`${node.id}-data`} value={`\${${node.id}.output.data}`} />,
              ])}
            </datalist>
            <p className="mt-1 text-xs text-gray-500">
              Data-Piping: ${'node_id.output.field'}
            </p>
          </div>
          <div className="mb-3">
            <FieldLabel>Prompt Template</FieldLabel>
            <textarea
              value={title}
              onChange={(event) => {
                setTitle(event.target.value);
                markDirty();
              }}
              className="h-16 w-full rounded bg-gray-700 px-2 py-1 text-sm"
              placeholder="Summarize the following:"
            />
          </div>
        </div>
      )}

      {selectedNode.type === 'mcp_call' && (
        <div className="mb-4 border-t border-gray-700 pt-4">
          <h4 className="mb-2 text-sm font-semibold">MCP Call Config</h4>
          <SelectField
            label="MCP Server"
            value={draftString('mcpServer')}
            options={mcpServers.map((server) => ({
              value: server.name,
              label: `${server.name} (${server.status})`,
            }))}
            onChange={(value) => {
              updateField('mcpServer', value);
              updateField('mcpTool', '');
            }}
            emptyLabel="– Server –"
            hint={mcpServers.length === 0 ? 'Keine MCP-Server geladen.' : undefined}
          />
          <SelectField
            label="Tool Name"
            value={draftString('mcpTool')}
            options={mcpTools.map((tool) => ({
              value: tool.name,
              label: tool.name,
            }))}
            onChange={(value) => updateField('mcpTool', value)}
            emptyLabel="– Tool –"
            hint={
              mcpServer && mcpTools.length === 0
                ? 'Für diesen Server wurden keine Tools gefunden.'
                : undefined
            }
          />
          <div className="mb-3">
            <FieldLabel>Args (JSON)</FieldLabel>
            <textarea
              value={mcpArgsText}
              onChange={(event) => handleMcpArgsChange(event.target.value)}
              className={`h-24 w-full rounded bg-gray-700 px-2 py-1 font-mono text-sm ${
                mcpArgsValid ? '' : 'border-2 border-red-500'
              }`}
              placeholder='{"title": "${node_1.output.title}"}'
            />
            {!mcpArgsValid && (
              <p className="mt-1 text-xs text-red-400">Ungültiges JSON — Speichern ist blockiert.</p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              Data-Piping: ${'node_id.output.field'}
            </p>
          </div>
        </div>
      )}

      <div className="flex gap-2">
        <button
          onClick={handleSave}
          className="flex-1 rounded bg-blue-600 px-3 py-2 text-sm font-bold disabled:opacity-50"
          disabled={!isDirty}
        >
          Save {isDirty ? '●' : ''}
        </button>
        <button
          onClick={handleReset}
          className="rounded bg-gray-600 px-3 py-2 text-sm disabled:opacity-50"
          disabled={!isDirty}
          title="Änderungen verwerfen"
        >
          ↺ Reset
        </button>
        {selectedNode.type === 'agent' && (
          <button
            onClick={handleCopy}
            className="rounded bg-gray-600 px-3 py-2 text-sm"
            title="Agent-Config kopieren"
          >
            📋 Copy
          </button>
        )}
      </div>
    </div>
  );
}
