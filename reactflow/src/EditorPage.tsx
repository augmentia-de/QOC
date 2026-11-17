import { useState, useCallback, useEffect, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useWorkflowStore } from './store/useWorkflowStore.ts';
import {
  ReactFlow,
  MiniMap,
  Controls,
  Background,
  addEdge,
  BackgroundVariant,
} from '@xyflow/react';

import '@xyflow/react/dist/style.css';
import './index.css';
import nodeTypes from './nodeRegistry.ts';
import { useIsMobile } from './hooks/useMediaQuery.ts';
import { SchemaSource } from './components/SchemaSource.tsx';
import NodeListView from './components/NodeListView.tsx';
import { BottomSheet } from './components/BottomSheet.tsx';
import { LabeledEdge } from './components/LabeledEdge.tsx';
import ELK from 'elkjs/lib/elk.bundled.js';
import { listWorkflows, loadWorkflow, saveWorkflow, deleteWorkflow, exportWorkflow, importWorkflow } from './services/workflowStore.ts';
import { listAgents, getAgent, saveAgent, createAgent, seedDummyAgents } from './services/agentStore.ts';

const edgeTypes: any = { labeled: LabeledEdge };

// nodeTypes imported from nodeRegistry.js

const NODE_PALETTE = [
  { type: 'start', label: 'Start', color: '#5cb85c' },
  { type: 'agent', label: 'Agent', color: '#2563eb' },
  { type: 'context_agent_executor', label: 'Context Agent', color: '#0e7490' },
  { type: 'decision', label: 'Decision', color: '#f0ad4e' },
  { type: 'fork', label: 'Fork', color: '#0891b2' },
  { type: 'join', label: 'Join', color: '#0d9488' },
  { type: 'loop', label: 'Loop', color: '#ea580c' },
  { type: 'hitl', label: 'HITL', color: '#9333ea' },
  { type: 'subworkflow', label: 'Sub-Workflow', color: '#4f46e5' },
  { type: 'llm_eval', label: 'LLM Eval', color: '#7c3aed' },
  { type: 'llm_summarize', label: 'LLM Summarize', color: '#4338ca' },
  { type: 'compact', label: 'Compact', color: '#059669' },
  { type: 'mcp_call', label: 'MCP Call', color: '#c026d3' },
  { type: 'rest_call', label: 'REST Call', color: '#0891b2' },
  { type: 'end', label: 'End', color: '#d9534f' },
];

const MOCK_API = 'http://localhost:3001';
const TOOL_OPTIONS = ['Read', 'Write', 'Edit', 'Glob', 'Grep', 'Patch', 'WebFetch', 'Shell'];

function pathKey(nodeId, pathArr) {
  let key = nodeId;
  for (const seg of pathArr) {
    key += typeof seg === 'number' ? '[' + seg + ']' : '.' + seg;
  }
  return key;
}

function hasSelectedBelow(value, path, nodeId, selected) {
  const isObject = value !== null && typeof value === 'object';
  if (!isObject) return false;
  const entries = Array.isArray(value) ? value.map((v, i) => [i, v]) : Object.keys(value).map((k) => [k, value[k]]);
  for (const [k, v] of entries) {
    const childPath = path.concat([k]);
    if (selected.has(pathKey(nodeId, childPath))) return true;
    if (hasSelectedBelow(v, childPath, nodeId, selected)) return true;
  }
  return false;
}

function TreeNode({ value, path, nodeId, selected, onToggle }) {
  const [expanded, setExpanded] = useState(() => hasSelectedBelow(value, path, nodeId, selected));
  const key = pathKey(nodeId, path);
  const checked = selected.has(key);
  const label = path.length ? String(path[path.length - 1]) : 'Kompletter Node';
  const isObject = value !== null && typeof value === 'object';
  const typeLabel = Array.isArray(value) ? 'array[' + value.length + ']' : (value === null ? 'null' : typeof value);
  const childEntries = isObject
    ? (Array.isArray(value) ? value.map((v, i) => [i, v]) : Object.keys(value).map((k) => [k, value[k]]))
    : [];
  return (
    <div style={{ marginLeft: path.length ? 12 : 0, marginTop: 2 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
        {isObject && childEntries.length > 0 && (
          <span
            onClick={() => setExpanded((e) => !e)}
            style={{ cursor: 'pointer', width: 12, textAlign: 'center', fontSize: 10, color: '#999', userSelect: 'none' }}
          >
            {expanded ? '▼' : '▶'}
          </span>
        )}
        {! (isObject && childEntries.length > 0) && <span style={{ width: 12 }} />}
        <input type="checkbox" checked={checked} onChange={() => onToggle(nodeId, path)} />
        <span
          onClick={() => { if (isObject && childEntries.length > 0) setExpanded((e) => !e); }}
          style={{ cursor: isObject && childEntries.length > 0 ? 'pointer' : 'default', fontWeight: 'bold', fontSize: 11 }}
        >
          {label}
        </span>
        <span style={{ fontSize: 9, color: '#999' }}>({typeLabel})</span>
        {!isObject && <span style={{ fontSize: 9, color: '#aaa' }}>= {String(value)}</span>}
        {checked && isObject && <span style={{ fontSize: 9, color: '#5cb85c' }}>subtree</span>}
      </div>
      {isObject && expanded && childEntries.map(([k, v]) => (
        <TreeNode key={String(k)} value={v} path={path.concat([k])} nodeId={nodeId} selected={selected} onToggle={onToggle} />
      ))}
    </div>
  );
}

let idCounter = 100;
function nextId(type) { return type + '-' + (++idCounter); }

const elk = new ELK();

async function autoLayout(nodes, edges) {
  // Build ELK graph with improved spacing to avoid overlaps.
  const graph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.spacing.nodeNode': '60',
      'elk.layered.spacing.nodeNodeBetweenLayers': '100',
      'elk.layered.spacing.edgeNodeBetweenLayers': '40',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
      'elk.padding': '[top=30,left=30,bottom=30,right=30]',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: 200,
      height: 80,
    })),
    edges: edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
  const layouted = await elk.layout(graph);
  const posMap = new Map((layouted.children || []).map((c) => [c.id, { x: c.x, y: c.y }]));
  return {
    nodes: nodes.map((n) => ({ ...n, position: posMap.get(n.id) || n.position })),
    edges,
  };
}

export default function EditorPage() {
  const isMobile = useIsMobile();
  const { id: routeId } = useParams();
  const nodes = useWorkflowStore((s) => s.nodes);
  const edges = useWorkflowStore((s) => s.edges);
  const selectedId = useWorkflowStore((s) => s.selectedId);
  const selected = selectedId ? nodes.find((n) => n.id === selectedId) || null : null;
  const selectedNode = (selected as any);
  const setNodes = useWorkflowStore((s) => s.setNodes);
  const setEdges = useWorkflowStore((s) => s.setEdges);
  const setSelected = (n: any) => useWorkflowStore.getState().setSelectedId(n ? n.id : null);
  const onNodesChange = (changes: any) => setNodes((nds: any) => (changes && typeof changes.applyTo === 'function' ? changes.applyTo(nds) : nds));
  const onEdgesChange = (changes: any) => setEdges((eds: any) => (changes && typeof changes.applyTo === 'function' ? changes.applyTo(eds) : eds));
  const [showInputOverlay, setShowInputOverlay] = useState(false);
  const [selectedInputs, setSelectedInputs] = useState(new Set());
  const [showListOverlay, setShowListOverlay] = useState(false);
  const [remoteWorkflows, setRemoteWorkflows] = useState([]);
  const [history, setHistory] = useState([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const [showJsonOverlay, setShowJsonOverlay] = useState(false);
  const workflowName = useWorkflowStore((s) => s.workflowName);
  const setWorkflowName = useWorkflowStore((s) => s.setWorkflowName);
  const [agents, setAgents] = useState([]);
  const [agentRef, setAgentRef] = useState('');
  const [agentEditMode, setAgentEditMode] = useState(false);
  const [agentModel, setAgentModel] = useState('');
  const [agentPrompt, setAgentPrompt] = useState('');
  const [agentJsonOutput, setAgentJsonOutput] = useState(false);
  const [agentSchema, setAgentSchema] = useState('');
  const [showAgentOverlay, setShowAgentOverlay] = useState(false);
  const [agentName, setAgentName] = useState('');
  const [agentVersion, setAgentVersion] = useState('1.0.0');
  const [agentCategory, setAgentCategory] = useState('');
  const [agentTemperature, setAgentTemperature] = useState(0.3);
  const [agentMaxTokens, setAgentMaxTokens] = useState(4096);
  const [agentTopP, setAgentTopP] = useState(1.0);
  const [agentActive, setAgentActive] = useState(true);
  const [agentTools, setAgentTools] = useState([]);
  const [agentPermissions, setAgentPermissions] = useState('');
  const [agentGuardrailsInput, setAgentGuardrailsInput] = useState('');
  const [agentGuardrailsOutput, setAgentGuardrailsOutput] = useState('');
  const [agentHooks, setAgentHooks] = useState('');
  const [agentMode, setAgentMode] = useState('all');
  const [agentSkills, setAgentSkills] = useState('');
  const [agentOwnerId, setAgentOwnerId] = useState('');
  const [agentMaxTurns, setAgentMaxTurns] = useState(10);
  const [agentMaxCost, setAgentMaxCost] = useState(1.0);
  const [agentRequiresHITL, setAgentRequiresHITL] = useState(false);
  const [ocText, setOcText] = useState('');

  useEffect(() => {
    seedDummyAgents();
    setAgents(listAgents());
    if (listWorkflows().length > 0) {
      setShowListOverlay(true);
    }
    (async () => {
      try {
        const { Api } = await import('./services/api.ts');
        const res = await Api.agents.list();
        if (Array.isArray(res.agents) && res.agents.length) setAgents(res.agents);
      } catch { /* keep local */ }
    })();
  }, []);

  useEffect(() => {
    if (!routeId) return;
    (async () => {
      try {
        const { Api } = await import('./services/api.ts');
        const wf = await Api.workflows.get(routeId);
        setNodes(wf.nodes || []);
        setEdges((wf.edges || []).map((e: any) => ({ ...e, type: e.type || 'labeled' })));
        setWorkflowName(wf.name || routeId);
      } catch { /* keep */ }
    })();
  }, [routeId]);

  useEffect(() => {
    if (!showListOverlay) return;
    (async () => {
      try {
        const { Api } = await import('./services/api.ts');
        const res = await Api.workflows.list();
        setRemoteWorkflows(res.workflows || []);
      } catch {
        setRemoteWorkflows([]);
      }
    })();
  }, [showListOverlay]);

  useEffect(() => {
    if (!selectedNode) return;
    setOcText(selectedNode.data?.outputContract ? JSON.stringify(selectedNode.data.outputContract, null, 2) : '');
  }, [selected?.id]);

  useEffect(() => {
    // Mock data is development-only and must never replace a route-loaded
    // workflow or leak into production builds.
    if (routeId || !import.meta.env.DEV) return;
    const currentNodes = useWorkflowStore.getState().nodes;
    if (currentNodes.length > 0) return;
    fetch(MOCK_API + '/nodes')
      .then((r) => r.json())
      .then((data) => setNodes(data))
      .catch(() => {
        setNodes([
          { id: 'start-1', type: 'start', position: { x: 50, y: 100 }, data: { title: 'Start', structure: '{\n  "input": "any"\n}' } },
          { id: 'agent-1', type: 'agent', position: { x: 200, y: 50 }, data: { title: 'Code Reviewer', agentRef: 'code-reviewer', model: 'gpt-4o-mini', systemPrompt: 'Review the provided code and return issues.', jsonOutput: true, responseSchema: { type: 'object', properties: { summary: { type: 'string' }, issues: { type: 'array' } } } } },
          { id: 'decision-1', type: 'decision', position: { x: 400, y: 50 }, data: { title: 'Severity Check', condition: "input.severity === 'critical'" } },
          { id: 'rest-1', type: 'rest_call', position: { x: 600, y: 50 }, data: { title: 'HTTP Request', mcpArgs: { method: 'GET', url: 'https://api.example.com/data' } } },
          { id: 'end-1', type: 'end', position: { x: 800, y: 100 }, data: { title: 'End' } },
        ]);
      });
    fetch(MOCK_API + '/edges')
      .then((r) => r.json())
      .then((data) => setEdges((data || []).map((e) => ({ ...e, type: e.type || 'labeled' }))))
      .catch(() => {
        setEdges([
          { id: 'e1-2', source: 'start-1', target: 'agent-1', type: 'labeled' },
          { id: 'e2-3', source: 'agent-1', target: 'decision-1', type: 'labeled' },
          { id: 'e3-4', source: 'decision-1', target: 'rest-1', type: 'labeled' },
          { id: 'e4-5', source: 'rest-1', target: 'end-1', type: 'labeled' },
        ]);
      });
  }, [routeId, setNodes, setEdges]);

  const onConnect = useCallback(
    (params) => setEdges((eds) => {
      const next = addEdge({ ...params, type: 'labeled' }, eds);
      pushHistory(nodes, next);
      return next;
    }),
    [setEdges, nodes]
  );

  const onNodeClick = useCallback((event, node) => setSelected(node), []);

  const addNode = (type) => {
    const id = nextId(type);
    const newNode = {
      id: id,
      type: type,
      position: { x: 250, y: 150 },
      data: { title: type.charAt(0).toUpperCase() + type.slice(1), label: type.charAt(0).toUpperCase() + type.slice(1) },
    };
    setNodes((nds) => {
      const next = nds.concat([newNode]);
      pushHistory(next, edges);
      return next;
    });
    setSelected(newNode);
  };

  const updateSelected = (key, value) => {
    if (!selected) return;
    setNodes((nds) =>
      nds.map((n) =>
        n.id === selected.id ? { ...n, data: { ...n.data, [key]: value } } : n
      )
    );
  };

  // selectedNode defined above as any

  const pushHistory = (n, e) => {
    setHistory((prev) => {
      const next = prev.slice(0, historyIndex + 1);
      next.push({ nodes: JSON.parse(JSON.stringify(n)), edges: JSON.parse(JSON.stringify(e)) });
      return next;
    });
    setHistoryIndex((i) => i + 1);
  };

  const undo = () => {
    if (historyIndex <= 0) return;
    const prev = history[historyIndex - 1];
    setNodes(prev.nodes);
    setEdges(prev.edges.map((ed) => ({ ...ed, type: ed.type || 'labeled' })));
    setHistoryIndex((i) => i - 1);
  };

  const redo = () => {
    if (historyIndex >= history.length - 1) return;
    const next = history[historyIndex + 1];
    setNodes(next.nodes);
    setEdges(next.edges.map((ed) => ({ ...ed, type: ed.type || 'labeled' })));
    setHistoryIndex((i) => i + 1);
  };

  useEffect(() => {
    if (!selectedNode || selectedNode.type !== 'agent') return;
    const d = (selectedNode.data || {}) as any;
    setAgentRef(d.agentRef || '');
    // Prefer workflow-stored (custom) values; fall back to ref only for missing fields.
    setAgentModel(d.model || '');
    setAgentPrompt(d.systemPrompt || '');
    setAgentJsonOutput(Boolean(d.jsonOutput));
    setAgentSchema(d.responseSchema ? JSON.stringify(d.responseSchema, null, 2) : (d.structure || ''));
    setAgentName(d.agentName || d.title || '');
    setAgentVersion(d.agentVersion || '1.0.0');
    setAgentCategory(d.agentCategory || '');
    setAgentTemperature(typeof d.temperature === 'number' ? d.temperature : 0.3);
    setAgentMaxTokens(typeof d.maxTokens === 'number' ? d.maxTokens : 4096);
    setAgentTopP(typeof d.topP === 'number' ? d.topP : 1.0);
    setAgentActive(d.active !== false);
    setAgentTools(Array.isArray(d.tools) ? d.tools : []);
    setAgentPermissions(Array.isArray(d.rolesAllowed) ? d.rolesAllowed.join(', ') : (d.permissions || ''));
    setAgentGuardrailsInput(Array.isArray(d.guardrailsInput) ? d.guardrailsInput.join(', ') : '');
    setAgentGuardrailsOutput(Array.isArray(d.guardrailsOutput) ? d.guardrailsOutput.join(', ') : '');
    setAgentHooks(Array.isArray(d.hooks) ? d.hooks.join(', ') : '');
    setAgentMode(d.mode || 'all');
    setAgentSkills(Array.isArray(d.skills) ? d.skills.join(', ') : '');
    setAgentOwnerId(d.ownerId || '');
    setAgentMaxTurns(typeof d.maxTurns === 'number' ? d.maxTurns : 10);
    setAgentMaxCost(typeof d.maxCost === 'number' ? d.maxCost : 1.0);
    setAgentRequiresHITL(Boolean(d.requiresHumanApproval));
    setAgentEditMode(false);
    const ref = d.agentRef;
    if (ref) {
      const a = getAgent(ref);
      if (a) {
        // only fill gaps from ref
        if (!d.model) setAgentModel(a.model || '');
        if (!d.systemPrompt) setAgentPrompt(a.systemPrompt || '');
        if (d.jsonOutput === undefined) setAgentJsonOutput(Boolean(a.jsonOutput));
        if (!d.responseSchema) setAgentSchema(a.responseSchema ? JSON.stringify(a.responseSchema, null, 2) : '');
        if (!d.agentName && !d.title) setAgentName(a.name || '');
        if (!d.agentVersion) setAgentVersion(a.version || '1.0.0');
        if (!d.agentCategory) setAgentCategory(a.category || '');
        if (typeof d.temperature !== 'number') setAgentTemperature(typeof a.temperature === 'number' ? a.temperature : 0.3);
        if (typeof d.maxTokens !== 'number') setAgentMaxTokens(typeof a.maxTokens === 'number' ? a.maxTokens : 4096);
        if (typeof d.topP !== 'number') setAgentTopP(typeof a.topP === 'number' ? a.topP : 1.0);
        if (d.active === undefined) setAgentActive(a.active !== false);
        if (!Array.isArray(d.tools) || d.tools.length === 0) setAgentTools(Array.isArray(a.tools) ? a.tools : []);
        if (!d.rolesAllowed && !d.permissions) setAgentPermissions(Array.isArray(a.rolesAllowed) ? a.rolesAllowed.join(', ') : (a.permissions || ''));
        if (!Array.isArray(d.guardrailsInput)) setAgentGuardrailsInput(Array.isArray(a.guardrailsInput) ? a.guardrailsInput.join(', ') : '');
        if (!Array.isArray(d.guardrailsOutput)) setAgentGuardrailsOutput(Array.isArray(a.guardrailsOutput) ? a.guardrailsOutput.join(', ') : '');
        if (!Array.isArray(d.hooks)) setAgentHooks(Array.isArray(a.hooks) ? a.hooks.join(', ') : '');
        if (!d.mode) setAgentMode(a.mode || 'all');
        if (!Array.isArray(d.skills)) setAgentSkills(Array.isArray(a.skills) ? a.skills.join(', ') : '');
        if (!d.ownerId) setAgentOwnerId(a.ownerId || '');
        if (typeof d.maxTurns !== 'number') setAgentMaxTurns(typeof a.maxTurns === 'number' ? a.maxTurns : 10);
        if (typeof d.maxCost !== 'number') setAgentMaxCost(typeof a.maxCost === 'number' ? a.maxCost : 1.0);
        if (d.requiresHumanApproval === undefined) setAgentRequiresHITL(Boolean(a.requiresHumanApproval));
      }
    }
  }, [selected?.id, selectedNode?.type, agents]);

  function getPredecessors(nodeId) {
    // Only direct predecessors (nodes with an edge into nodeId).
    const direct = new Set();
    edges
      .filter((e) => e.target === nodeId)
      .forEach((e) => {
        if (e.source && e.source !== nodeId) direct.add(e.source);
      });
    return [...direct].map((id) => nodes.find((n) => n.id === id)).filter(Boolean);
  }

  const predecessors = useMemo(
    () => (selected ? getPredecessors(selected.id) : []),
    [selected, nodes, edges]
  );

  const resolveOutputTree = (node) => {
    const visited = new Set();
    const walk = (n) => {
      if (!n || visited.has(n.id)) return (n && n.data) || {};
      visited.add(n.id);
      const d = (n.data || {}) as any;
      const contract = d.outputContract || d.responseSchema;
      if (contract && typeof contract === 'object' && contract.properties && typeof contract.properties === 'object') {
        return contract.properties;
      }
      // Pass-through nodes: inherit from direct predecessors.
      const preds = edges.filter((e) => e.target === n.id).map((e) => nodes.find((x) => x.id === e.source)).filter(Boolean);
      for (const p of preds) {
        const t = walk(p);
        if (t && typeof t === 'object' && Object.keys(t).length > 0) return t;
      }
      return d;
    };
    return walk(node);
  };

  function pathKey(nodeId, pathArr) {
    let key = nodeId;
    for (const seg of pathArr) {
      key += typeof seg === 'number' ? '[' + seg + ']' : '.' + seg;
    }
    return key;
  }

  const toggleInput = (nodeId, pathArr) => {
    const key = pathKey(nodeId, pathArr);
    setSelectedInputs((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  function collectSelected(node, pathArr, value, selectedSet, out) {
    const key = pathKey(node.id, pathArr);
    if (selectedSet.has(key)) {
      out.push({ nodeId: node.id, type: node.type, path: key, value: value });
      return; // selected subtree covers children
    }
    if (Array.isArray(value)) {
      value.forEach((v, i) => collectSelected(node, pathArr.concat([i]), v, selectedSet, out));
    } else if (value !== null && typeof value === 'object') {
      Object.keys(value).forEach((k) => collectSelected(node, pathArr.concat([k]), value[k], selectedSet, out));
    }
  }

  const applyInputs = () => {
    if (!selected) return;
    let effective = selectedInputs;
    if (!effective || effective.size === 0) {
      effective = new Set(predecessors.map((p) => {
        const d = p.data || {};
        if (d.lastMessage !== undefined) return p.id + '.lastMessage';
        if (d.lastResult !== undefined) return p.id + '.lastResult';
        return p.id;
      }));
    }
    const chosen = [];
    predecessors.forEach((p) => {
      const treeValue = resolveOutputTree(p);
      collectSelected(p, [], treeValue, effective, chosen);
    });
    updateSelected('inputs', chosen);
    // Bridge: input-source selections become runtime contextRefs (single source of truth).
    updateSelected('contextRefs', chosen.map((c: any) => c.path || c.nodeId));
    setShowInputOverlay(false);
  };

  const openInputOverlay = () => {
    const current = (selectedNode && selectedNode.data && selectedNode.data.inputs) || [];
    const refs = (selectedNode && selectedNode.data && selectedNode.data.contextRefs) || [];
    // Fall back to contextRefs when inputs (UI-only) is not persisted by the backend.
    const seed = current.length > 0
      ? current.map((i) => i.path || i.nodeId)
      : refs;
    setSelectedInputs(new Set(seed));
    setShowInputOverlay(true);
  };

  const openAgentOverlay = () => {
    setShowAgentOverlay(true);
  };

  const saveAgentOverlayToNode = () => {
    const schema = agentSchema.trim() ? (() => { try { return JSON.parse(agentSchema); } catch { return null; } })() : null;
    updateSelected('title', agentName || selectedNode?.data?.title);
    updateSelected('label', agentName || selectedNode?.data?.title);
    updateSelected('agentName', agentName);
    updateSelected('agentVersion', agentVersion);
    updateSelected('agentCategory', agentCategory);
    updateSelected('model', agentModel);
    updateSelected('systemPrompt', agentPrompt);
    updateSelected('jsonOutput', agentJsonOutput);
    updateSelected('responseSchema', schema);
    updateSelected('temperature', agentTemperature);
    updateSelected('maxTokens', agentMaxTokens);
    updateSelected('topP', agentTopP);
    updateSelected('active', agentActive);
    updateSelected('tools', agentTools);
    updateSelected('permissions', agentPermissions);
    updateSelected('rolesAllowed', agentPermissions.split(',').map((s) => s.trim()).filter(Boolean));
    updateSelected('guardrailsInput', agentGuardrailsInput.split(',').map((s) => s.trim()).filter(Boolean));
    updateSelected('guardrailsOutput', agentGuardrailsOutput.split(',').map((s) => s.trim()).filter(Boolean));
    updateSelected('hooks', agentHooks.split(',').map((s) => s.trim()).filter(Boolean));
    updateSelected('mode', agentMode);
    updateSelected('skills', agentSkills.split(',').map((s) => s.trim()).filter(Boolean));
    updateSelected('ownerId', agentOwnerId);
    updateSelected('maxTurns', agentMaxTurns);
    updateSelected('maxCost', agentMaxCost);
    updateSelected('requiresHumanApproval', agentRequiresHITL);
    updateSelected('customized', true);
  };

  const saveAgentOverlayRef = async () => {
    const schema = agentSchema.trim() ? (() => { try { return JSON.parse(agentSchema); } catch { return null; } })() : null;
    const agent = {
      id: agentRef || ('agent-' + Date.now()),
      name: agentName || agentRef || 'inline',
      version: agentVersion,
      category: agentCategory,
      model: agentModel,
      temperature: agentTemperature,
      maxTokens: agentMaxTokens,
      topP: agentTopP,
      tools: agentTools,
      guardrailsInput: [],
      guardrailsOutput: [],
      hooks: [],
      jsonOutput: agentJsonOutput,
      active: agentActive,
      systemPrompt: agentPrompt,
      responseSchema: schema,
      ownerId: '',
      rolesAllowed: agentPermissions.split(',').map((s) => s.trim()).filter(Boolean),
      mode: 'all',
      skills: [],
      permissions: agentPermissions,
    };
    if (!agentRef) {
      const created = createAgent(agent);
      setAgentRef(created.id);
      updateSelected('agentRef', created.id);
    } else {
      saveAgent(agent);
    }
    setAgents(listAgents());
    try {
      const { Api } = await import('./services/api.ts');
      if (agentRef) await Api.agents.save(agentRef, agent); else { const c = await Api.agents.create(agent); setAgentRef(c.id); updateSelected('agentRef', c.id); }
    } catch { /* keep local */ }
  };

  const handleSaveAgentRefToFile = async () => {
    const schema = agentSchema.trim() ? (() => { try { return JSON.parse(agentSchema); } catch { return null; } })() : null;
    const agent = {
      id: agentRef || ('agent-' + Date.now()),
      name: agentName || agentRef || 'inline',
      version: agentVersion,
      category: agentCategory,
      model: agentModel,
      temperature: agentTemperature,
      maxTokens: agentMaxTokens,
      topP: agentTopP,
      tools: agentTools,
      guardrailsInput: agentGuardrailsInput.split(',').map((s) => s.trim()).filter(Boolean),
      guardrailsOutput: agentGuardrailsOutput.split(',').map((s) => s.trim()).filter(Boolean),
      hooks: agentHooks.split(',').map((s) => s.trim()).filter(Boolean),
      mode: agentMode,
      skills: agentSkills.split(',').map((s) => s.trim()).filter(Boolean),
      ownerId: agentOwnerId,
      maxTurns: agentMaxTurns,
      maxCost: agentMaxCost,
      requiresHumanApproval: agentRequiresHITL,
      jsonOutput: agentJsonOutput,
      active: agentActive,
      systemPrompt: agentPrompt,
      responseSchema: schema,
      permissions: agentPermissions,
      rolesAllowed: agentPermissions.split(',').map((s) => s.trim()).filter(Boolean),
    };
    const id = agent.id;
    try {
      const { Api } = await import('./services/api.ts');
      if (agentRef) await Api.agents.save(id, agent); else { const c = await Api.agents.create(agent); setAgentRef(c.id); updateSelected('agentRef', c.id); }
      alert('Gespeichert in data/agents/' + id + '.json');
      return;
    } catch {
      const blob = new Blob([JSON.stringify(agent, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = id + '.json';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    }
  };

  const resetAgentOverlayToRef = () => {
    const a = getAgent(agentRef);
    if (!a) return;
    updateSelected('customized', false);
    setAgentName(a.name || '');
    setAgentVersion(a.version || '1.0.0');
    setAgentCategory(a.category || '');
    setAgentModel(a.model || '');
    setAgentPrompt(a.systemPrompt || '');
    setAgentJsonOutput(Boolean(a.jsonOutput));
    setAgentSchema(a.responseSchema ? JSON.stringify(a.responseSchema, null, 2) : '');
    setAgentTemperature(typeof a.temperature === 'number' ? a.temperature : 0.3);
    setAgentMaxTokens(typeof a.maxTokens === 'number' ? a.maxTokens : 4096);
    setAgentTopP(typeof a.topP === 'number' ? a.topP : 1.0);
    setAgentActive(a.active !== false);
    setAgentTools(Array.isArray(a.tools) ? a.tools : []);
    setAgentPermissions(Array.isArray(a.rolesAllowed) ? a.rolesAllowed.join(', ') : (a.permissions || ''));
  };

  const handleSave = async () => {
    const name = workflowName.trim() || 'untitled';
    try {
      const { Api } = await import('./services/api.ts');
      await Api.workflows.save(name, { id: name, name, version: '1.0.0', status: 'draft', nodes, edges, agents: [], ownerId: '', systemPrompt: '', inputSchema: null });
    } catch {
      saveWorkflow(name, nodes, edges);
    }
  };

  const handleSaveToFile = async () => {
    const name = workflowName.trim() || 'untitled';
    const def = { id: name, name, version: '1.0.0', status: 'draft', nodes, edges, agents: [], ownerId: '', systemPrompt: '', inputSchema: null };
    try {
      const { Api } = await import('./services/api.ts');
      await Api.workflows.save(name, def);
      alert('Gespeichert in data/workflows/' + name + '.json');
      return;
    } catch {
      // Fallback: Download
      const json = exportWorkflow ? exportWorkflow(name, nodes, edges) : JSON.stringify(def, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name.replace(/[^a-z0-9-_]/gi, '_') + '.json';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    }
  };

  const handleLoadFromFile = (event) => {
    const file = event.target.files && event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const id = importWorkflow(reader.result);
        const wf = loadWorkflow(id);
        if (wf) {
          setNodes(wf.nodes || []);
          setEdges((wf.edges || []).map((e) => ({ ...e, type: e.type || 'labeled' })));
          setWorkflowName(wf.name || '');
        }
        setShowListOverlay(false);
      } catch (e) {
        alert('Import fehlgeschlagen: ' + (e && e.message ? e.message : e));
      }
    };
    reader.readAsText(file);
    event.target.value = '';
  };

  const handleLoad = async (id) => {
    try {
      const { Api } = await import('./services/api.ts');
      const wf = await Api.workflows.get(id);
      setNodes(wf.nodes || []);
      setEdges((wf.edges || []).map((e) => ({ ...e, type: e.type || 'labeled' })));
      setWorkflowName(wf.name || id);
      setShowListOverlay(false);
      return;
    } catch { /* fallback */ }
    const wf = loadWorkflow(id);
    if (wf) {
      setNodes(wf.nodes || []);
      setEdges(wf.edges || []);
      setShowListOverlay(false);
    }
  };

  const handleDelete = (id) => {
    if (!window.confirm('Delete this workflow?')) return;
    deleteWorkflow(id);
    setShowListOverlay(false);
  };

  const handleValidate = async () => {
    const def = { id: workflowName.trim() || 'untitled', name: workflowName.trim() || 'untitled', nodes, edges };
    try {
      const { QocApi } = await import('./services/qocApi.ts');
      const res = await QocApi.workflows.validate(def);
      if (res.valid) alert('Workflow is valid! ' + (res.summary || ''));
      else alert('Validation errors:\n' + (res.errors || []).join('\n'));
      return;
    } catch { /* fallback */ }
    const errors = [];
    if (nodes.length === 0) errors.push('No nodes');
    const ids = new Set(nodes.map((n) => n.id));
    edges.forEach((e) => {
      if (!ids.has(e.source)) errors.push('Edge ' + e.id + ': unknown source ' + e.source);
      if (!ids.has(e.target)) errors.push('Edge ' + e.id + ': unknown target ' + e.target);
    });
    const hasStart = nodes.some((n) => n.type === 'start');
    const hasEnd = nodes.some((n) => n.type === 'end');
    if (!hasStart) errors.push('Missing start node');
    if (!hasEnd) errors.push('Missing end node');
    if (errors.length) alert('Validation errors:\n' + errors.join('\n'));
    else alert('Workflow is valid!');
  };

  const handleRun = async () => {
    const errors = [];
    if (nodes.length === 0) errors.push('No nodes');
    const hasStart = nodes.some((n) => n.type === 'start');
    if (!hasStart) errors.push('Missing start node');
    if (errors.length) { alert('Cannot run:\n' + errors.join('\n')); return; }
    const def = { id: workflowName.trim() || 'untitled', name: workflowName.trim() || 'untitled', nodes, edges };
    try {
      const { QocApi } = await import('./services/qocApi.ts');
      const res = await QocApi.workflows.run(def, 'Hello');
      alert('Ergebnis: ' + (res.result || 'OK') + '\\nOrder: ' + (res.order || []).join(', '));
      return;
    } catch { /* fallback */ }
    alert('Running workflow with ' + nodes.length + ' nodes and ' + edges.length + ' edges...');
  };

  const handleAutoLayout = async () => {
    if (nodes.length === 0) return;
    try {
      const result = await autoLayout(nodes, edges);
      setNodes(result.nodes);
      setEdges(result.edges.map((e) => ({ ...e, type: e.type || 'labeled' })));
      pushHistory(result.nodes, result.edges);
    } catch (e) {
      console.error('Auto-layout failed', e);
      alert('Auto-Layout fehlgeschlagen: ' + (e && e.message ? e.message : e));
    }
  };

  if (isMobile) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', width: '100vw', height: '100vh' }}>
        <div style={{ padding: 8, background: '#161616', color: '#d4d4d4', borderBottom: '1px solid #333' }}>
          <input value={workflowName} onChange={(e) => setWorkflowName(e.target.value)} placeholder="Workflow name" style={{ background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, padding: '4px 8px', fontSize: 12, width: '100%' }} />
        </div>
        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <div style={{ width: 200, borderRight: '1px solid #333', overflow: 'auto' }}>
            <NodeListView nodes={nodes} selectedId={selected?.id} onSelect={(id) => setSelected(nodes.find((n) => n.id === id) || null)} onAddNode={() => { const t = prompt('Node-Typ:', 'agent'); if (t) addNode(t); }} onDeleteNode={(id) => setNodes((nds) => nds.filter((n) => n.id !== id))} />
          </div>
          <div style={{ flex: 1 }}>
            <ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={onConnect} onNodeClick={onNodeClick} nodeTypes={nodeTypes} edgeTypes={edgeTypes} defaultEdgeOptions={{ type: 'labeled' }} deleteKeyCode={['Backspace', 'Delete']} fitView>
              <Controls />
              <Background variant={BackgroundVariant.Dots} gap={12} size={1} />
            </ReactFlow>
          </div>
        </div>
        <BottomSheet open={!!selectedNode} onClose={() => setSelected(null)} title={selectedNode?.id}>
          {selectedNode && (
            <div style={{ fontSize: 12 }}>
              {/* reuse minimal props */}
              <div>Label: {selectedNode.data?.label || selectedNode.data?.title}</div>
            </div>
          )}
        </BottomSheet>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', width: '100vw', height: '100vh' }}>
      <div style={{ display: 'flex', gap: 8, padding: 8, background: '#161616', color: '#d4d4d4', borderBottom: '1px solid #333', alignItems: 'center' }}>
        <span style={{ fontWeight: 'bold', color: '#5bc0de' }}>Workflow Editor</span>
        <input value={workflowName} onChange={(e) => setWorkflowName(e.target.value)} placeholder="Workflow name" style={{ background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 4, padding: '4px 8px', fontSize: 12 }} />
        <button onClick={handleSave} style={{ background: '#5cb85c', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Save</button>
        <button onClick={handleSaveToFile} style={{ background: '#17a2b8', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Save to file</button>
        <button onClick={undo} disabled={historyIndex <= 0} style={{ background: '#6c757d', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12, opacity: historyIndex <= 0 ? 0.5 : 1 }}>Undo</button>
        <button onClick={redo} disabled={historyIndex >= history.length - 1} style={{ background: '#6c757d', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12, opacity: historyIndex >= history.length - 1 ? 0.5 : 1 }}>Redo</button>
        <button onClick={() => setShowListOverlay(true)} style={{ background: '#337ab7', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Load/List</button>
        <button onClick={() => { useWorkflowStore.getState().clear(); }} style={{ background: '#888', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Clear</button>
        <button onClick={handleValidate} style={{ background: '#f0ad4e', color: '#000', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Validate</button>
        <button onClick={handleAutoLayout} style={{ background: '#6f42c1', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Auto-Layout</button>
        <button onClick={() => setShowJsonOverlay(true)} style={{ background: '#6c757d', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>JSON</button>
        <button onClick={handleRun} style={{ background: '#d9534f', color: '#fff', border: 'none', padding: '4px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>Run</button>
      </div>
      {isMobile && (
        <div style={{ display: 'flex', gap: 4, padding: 6, background: '#161616', overflowX: 'auto', borderBottom: '1px solid #333' }}>
          {NODE_PALETTE.map((n) => (
            <button
              key={n.type}
              onClick={() => addNode(n.type)}
              style={{ flex: '0 0 auto', padding: '4px 8px', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid ' + n.color, borderRadius: 4, cursor: 'pointer', fontSize: 11 }}
            >
              {n.label}
            </button>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        {!isMobile && (
        <div style={{ width: 160, padding: 12, background: '#161616', color: '#d4d4d4', borderRight: '1px solid #333' }}>
          <h4 style={{ margin: '0 0 12px' }}>Nodes</h4>
          {NODE_PALETTE.map((n) => (
            <button
              key={n.type}
              onClick={() => addNode(n.type)}
              style={{ display: 'block', width: '80%', marginBottom: 6, padding: '4px 4px', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid ' + n.color, borderRadius: 4, cursor: 'pointer', fontSize: 11 }}
            >
              {n.label}
            </button>
          ))}
        </div>
        )}

        <div style={{ flex: 1 }}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            deleteKeyCode={['Backspace', 'Delete']}
            onNodesDelete={(deleted) => setNodes((nds) => nds.filter((n) => !deleted.some((d) => d.id === n.id)))}
            onEdgesDelete={(deleted) => setEdges((eds) => eds.filter((e) => !deleted.some((d) => d.id === e.id)))}
            onNodeClick={onNodeClick}
            onNodeDragStop={(event, node, draggedNodes) => {
              const dragged = draggedNodes || [];
              const draggedIds = new Set(dragged.map((n) => n.id));
              setNodes((prev) => prev.map((n) => (draggedIds.has(n.id) ? (dragged.find((d) => d.id === n.id) || n) : n)));
            }}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            defaultEdgeOptions={{ type: 'labeled' }}
            fitView
          >
            <Controls />
            <MiniMap />
            <Background variant={BackgroundVariant.Dots} gap={12} size={1} />
          </ReactFlow>
        </div>

        {selectedNode && (
          <div style={{ width: 240, padding: 8, background: '#1e1e1e', color: '#d4d4d4', overflow: 'auto', borderLeft: '1px solid #333', fontSize: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h4 style={{ margin: 0 }}>{selectedNode.id}</h4>
              <button onClick={() => setSelected(null)} style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer' }}>x</button>
            </div>
            <label style={{ display: 'block', marginTop: 8 }}>Label<br />
              <input value={selectedNode.data?.label || ''} onChange={(e) => updateSelected('label', e.target.value)} disabled={selectedNode.type === 'agent' || selectedNode.type === 'context_agent_executor'} style={{ width: '100%', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', opacity: (selectedNode.type === 'agent' || selectedNode.type === 'context_agent_executor') ? 0.6 : 1 }} />
            </label>
            {selectedNode.type !== 'start' && selectedNode.type !== 'end' && (
              <>
                <hr style={{ border: 'none', borderTop: '1px solid #444', margin: '8px 0' }} />
                <div style={{ marginTop: 0 }}>
                  <button onClick={openInputOverlay} disabled={predecessors.length === 0} style={{ fontSize: 11, background: '#444', color: '#ddd', border: '1px solid #666', padding: '4px 8px', borderRadius: 4 }}>
                    Inputs ({predecessors.length})
                  </button>
                </div>
                {selectedNode.data && selectedNode.data.inputs && (
                  <div style={{ fontSize: 10, color: '#999', marginTop: 4 }}>
                    {selectedNode.data.inputs.map((i) => i.path || i.nodeId).join(', ')}
                  </div>
                )}
                <hr style={{ border: 'none', borderTop: '1px solid #444', margin: '8px 0' }} />
              </>
            )}
            {(selectedNode.type === 'agent' || selectedNode.type === 'context_agent_executor') && (
              <div>
                <label style={{ display: 'block', marginTop: 8 }}>Agent-Ref<br />
                  <select value={agentRef} disabled={Boolean(selectedNode.data?.customized)} onChange={(e) => {
                    const v = e.target.value;
                    setAgentRef(v);
                    updateSelected('agentRef', v);
                    let a = getAgent(v);
                    // Fallback: fetch from backend when not in local store
                    if (!a && v) {
                      (async () => {
                        try {
                          const { Api } = await import('./services/api.ts');
                          const fetched = await Api.agents.get(v);
                          if (fetched) {
                            setAgentModel(fetched.model || '');
                            setAgentPrompt(fetched.systemPrompt || '');
                            setAgentJsonOutput(Boolean(fetched.jsonOutput));
                            setAgentSchema(fetched.responseSchema ? JSON.stringify(fetched.responseSchema, null, 2) : '');
                            setAgentName(fetched.name || '');
                            setAgentVersion(fetched.version || '1.0.0');
                            setAgentCategory(fetched.category || '');
                            setAgentTemperature(typeof fetched.temperature === 'number' ? fetched.temperature : 0.3);
                            setAgentMaxTokens(typeof fetched.maxTokens === 'number' ? fetched.maxTokens : 4096);
                            setAgentTopP(typeof fetched.topP === 'number' ? fetched.topP : 1.0);
                            setAgentActive(fetched.active !== false);
                            setAgentTools(Array.isArray(fetched.tools) ? fetched.tools : []);
                            setAgentPermissions(Array.isArray(fetched.rolesAllowed) ? fetched.rolesAllowed.join(', ') : (fetched.permissions || ''));
                            updateSelected('model', fetched.model || '');
                            updateSelected('systemPrompt', fetched.systemPrompt || '');
                            updateSelected('jsonOutput', Boolean(fetched.jsonOutput));
                            updateSelected('responseSchema', fetched.responseSchema || null);
                            updateSelected('title', fetched.name || fetched.id || v);
                            updateSelected('label', fetched.name || fetched.id || v);
                          }
                        } catch { /* keep empty */ }
                      })();
                    }
                    if (a) {
                      setAgentModel(a.model || '');
                      setAgentPrompt(a.systemPrompt || '');
                      setAgentJsonOutput(Boolean(a.jsonOutput));
                      setAgentSchema(a.responseSchema ? JSON.stringify(a.responseSchema, null, 2) : '');
                      setAgentName(a.name || '');
                      setAgentVersion(a.version || '1.0.0');
                      setAgentCategory(a.category || '');
                      setAgentTemperature(typeof a.temperature === 'number' ? a.temperature : 0.3);
                      setAgentMaxTokens(typeof a.maxTokens === 'number' ? a.maxTokens : 4096);
                      setAgentTopP(typeof a.topP === 'number' ? a.topP : 1.0);
                      setAgentActive(a.active !== false);
                      setAgentTools(Array.isArray(a.tools) ? a.tools : []);
                      setAgentPermissions(Array.isArray(a.rolesAllowed) ? a.rolesAllowed.join(', ') : (a.permissions || ''));
                      updateSelected('model', a.model || '');
                      updateSelected('systemPrompt', a.systemPrompt || '');
                      updateSelected('jsonOutput', Boolean(a.jsonOutput));
                      updateSelected('responseSchema', a.responseSchema || null);
                      updateSelected('title', a.name || a.id);
                      updateSelected('label', a.name || a.id);
                    }
                  }} style={{ width: '100%', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', opacity: selectedNode.data?.customized ? 0.6 : 1 }}>
                    {selectedNode.data?.customized && <option value={agentRef}>{'custom (' + (agentRef || 'inline') + ')'}</option>}
                    <option value="">-- neu / inline --</option>
                    {agents.map((a) => (
                      <option key={a.id} value={a.id}>{a.id} ({a.name || a.model})</option>
                    ))}
                    {agentRef && !agents.some((a) => a.id === agentRef) && (
                      <option value={agentRef}>{agentRef} (unbekannt)</option>
                    )}
                  </select>
                </label>
                <div style={{ marginTop: 8 }}>
                  <button onClick={openAgentOverlay} style={{ fontSize: 11, background: '#337ab7', color: '#fff', border: 'none', padding: '4px 8px', borderRadius: 4 }}>
                    Edit Agent Config
                  </button>
                </div>
                <div style={{ fontSize: 10, color: '#999', marginTop: 4 }}>
                  {agentRef ? 'Ref: ' + agentRef : 'Inline (kein Ref)'} · {agentModel || 'no model'}
                </div>
              </div>
            )}
            {selectedNode.type === 'decision' && (
              <label style={{ display: 'block', marginTop: 8 }}>Condition<br />
                <input value={selectedNode.data?.condition || ''} onChange={(e) => updateSelected('condition', e.target.value)} style={{ width: '100%', background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
            )}
            {selectedNode.type === 'start' && (
              <label style={{ display: 'block', marginTop: 8 }}>Structure (JSON)<br />
                <textarea
                  value={selectedNode.data?.structure || ''}
                  onChange={(e) => updateSelected('structure', e.target.value)}
                  placeholder={'{\n  "field": "type"\n}'}
                  style={{ width: '100%', height: 100, background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', fontFamily: 'monospace', fontSize: 11 }}
                />
              </label>
            )}
            <div style={{ marginTop: 8 }}>
              <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>Gewählte Inputs (Context-Refs)</div>
              {Array.isArray(selectedNode.data?.contextRefs) && selectedNode.data.contextRefs.length > 0 ? (
                <div style={{ fontSize: 10, color: '#999', marginTop: 2 }}>
                  {selectedNode.data.contextRefs.join(', ')}
                </div>
              ) : (
                <div style={{ fontSize: 10, color: '#555', marginTop: 2 }}>Keine — "Inputs"-Button nutzen</div>
              )}
            </div>
            <label style={{ display: 'block', marginTop: 8 }}>
              <input type="checkbox" checked={Boolean(selectedNode.data?.checkContract)} onChange={(e) => updateSelected('checkContract', e.target.checked)} />
              {' '}Check Contract (JSON response is a contract to validate)
            </label>
            <SchemaSource value={(selectedNode.data?.outputContract) || null} onChange={(schema) => updateSelected('outputContract', schema)} label="Output Contract (Schema)" />
            {selectedNode.data && selectedNode.data.inputs && (
              <div style={{ fontSize: 10, color: '#999', marginTop: 4 }}>
                {selectedNode.data.inputs.map((i) => i.path || i.nodeId).join(', ')}
              </div>
            )}
          </div>
        )}

        {showInputOverlay && selectedNode && (
          <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
            <div style={{ background: '#2d2d2d', padding: 16, borderRadius: 8, width: 480, maxHeight: '80vh', overflow: 'auto', color: '#d4d4d4', fontSize: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <h3 style={{ margin: 0 }}>Select input sources</h3>
                <button onClick={() => setShowInputOverlay(false)} style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer', fontSize: 16 }}>x</button>
              </div>
              <p style={{ fontSize: 11, color: '#999', margin: '0 0 8px' }}>Choose predecessor nodes whose JSON output should be fed as input.</p>
              {predecessors.map((p) => {
                const treeValue = resolveOutputTree(p);
                return (
                <div key={p.id} style={{ marginBottom: 10, padding: 8, border: '1px solid #555', borderRadius: 4 }}>
                  <div style={{ fontWeight: 'bold', marginBottom: 4 }}>{String((p.data && (p.data.title || p.data.label)) || p.id)} <span style={{ color: '#999', fontWeight: 'normal' }}>({p.id} · {p.type})</span></div>
                  <TreeNode value={treeValue} path={[]} nodeId={p.id} selected={selectedInputs} onToggle={toggleInput} />
                </div>
                );
              })}
              <div style={{ marginTop: 8 }}>
                <button onClick={applyInputs} style={{ background: '#337ab7', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4, marginRight: 8 }}>Apply</button>
                <button onClick={() => setShowInputOverlay(false)} style={{ background: '#555', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Cancel</button>
              </div>
            </div>
          </div>
        )}

        {showJsonOverlay && (
          <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
            <div style={{ background: '#2d2d2d', padding: 16, borderRadius: 8, width: 640, maxHeight: '80vh', overflow: 'auto', color: '#d4d4d4', fontSize: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <h3 style={{ margin: 0 }}>Workflow (JSON)</h3>
                <button onClick={() => setShowJsonOverlay(false)} style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer', fontSize: 16 }}>x</button>
              </div>
              <textarea readOnly value={JSON.stringify({ id: workflowName.trim() || 'untitled', name: workflowName.trim() || 'untitled', version: '1.0.0', status: 'draft', nodes, edges, agents: [], ownerId: '', systemPrompt: '', inputSchema: null }, null, 2)} style={{ width: '100%', height: '60vh', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555', fontFamily: 'monospace', fontSize: 11, resize: 'none' }} />
            </div>
          </div>
        )}

        {showAgentOverlay && selectedNode && (selectedNode.type === 'agent' || selectedNode.type === 'context_agent_executor') && (
          <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
            <div style={{ background: '#2d2d2d', padding: 16, borderRadius: 8, width: 560, maxHeight: '85vh', overflow: 'auto', color: '#d4d4d4', fontSize: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <h3 style={{ margin: 0 }}>Agent Config</h3>
                <button onClick={() => setShowAgentOverlay(false)} style={{ background: 'none', border: 'none', color: '#999', cursor: 'pointer', fontSize: 16 }}>x</button>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <label>Name<br /><input value={agentName} onChange={(e) => setAgentName(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
                <label>Version<br /><input value={agentVersion} onChange={(e) => setAgentVersion(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
                <label>Category<br /><input value={agentCategory} onChange={(e) => setAgentCategory(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
                <label>Model<br />
                  <select value={agentModel} onChange={(e) => setAgentModel(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }}>
                    {['deepseek-v4.1-flash', 'gpt-4o', 'gpt-4o-mini', 'claude-3.5-sonnet'].map((m) => <option key={m} value={m}>{m}</option>)}
                  </select>
                </label>
                <label>Temperature<br /><input type="number" step="0.1" value={agentTemperature} onChange={(e) => setAgentTemperature(parseFloat(e.target.value) || 0.3)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
                <label>Max Tokens<br /><input type="number" value={agentMaxTokens} onChange={(e) => setAgentMaxTokens(parseInt(e.target.value) || 4096)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
                <label>Top P<br /><input type="number" step="0.05" value={agentTopP} onChange={(e) => setAgentTopP(parseFloat(e.target.value) || 1.0)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
                <label>Permissions (text)<br /><input value={agentPermissions} onChange={(e) => setAgentPermissions(e.target.value)} placeholder="admin, reviewer" style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} /></label>
              </div>
              <label style={{ display: 'block', marginTop: 8 }}>System Prompt<br />
                <textarea value={agentPrompt} onChange={(e) => setAgentPrompt(e.target.value)} style={{ width: '100%', height: 80, background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>
                <input type="checkbox" checked={agentJsonOutput} onChange={(e) => setAgentJsonOutput(e.target.checked)} />
                {' '}JSON Output
              </label>
              <SchemaSource value={agentSchema.trim() ? (() => { try { return JSON.parse(agentSchema); } catch { return null; } })() : null} onChange={(schema) => setAgentSchema(schema ? JSON.stringify(schema, null, 2) : '')} label="Response Schema (JSON)" />
              <div style={{ marginTop: 8 }}>
                <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>Tools</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {TOOL_OPTIONS.map((tool) => (
                    <label key={tool} style={{ fontSize: 11 }}>
                      <input type="checkbox" checked={agentTools.includes(tool)} onChange={(e) => {
                        if (e.target.checked) setAgentTools((prev) => [...prev, tool]);
                        else setAgentTools((prev) => prev.filter((t) => t !== tool));
                      }} />
                      {' '}{tool}
                    </label>
                  ))}
                </div>
              </div>
              <label style={{ display: 'block', marginTop: 8 }}>Guardrails Input (comma)<br />
                <input value={agentGuardrailsInput} onChange={(e) => setAgentGuardrailsInput(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Guardrails Output (comma)<br />
                <input value={agentGuardrailsOutput} onChange={(e) => setAgentGuardrailsOutput(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Hooks (comma)<br />
                <input value={agentHooks} onChange={(e) => setAgentHooks(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Mode<br />
                <select value={agentMode} onChange={(e) => setAgentMode(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }}>
                  <option value="all">all</option>
                  <option value="read">read</option>
                  <option value="write">write</option>
                </select>
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Skills (comma)<br />
                <input value={agentSkills} onChange={(e) => setAgentSkills(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Owner ID<br />
                <input value={agentOwnerId} onChange={(e) => setAgentOwnerId(e.target.value)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Max Turns<br />
                <input type="number" value={agentMaxTurns} onChange={(e) => setAgentMaxTurns(parseInt(e.target.value) || 10)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>Max Cost (USD)<br />
                <input type="number" step="0.1" value={agentMaxCost} onChange={(e) => setAgentMaxCost(parseFloat(e.target.value) || 1.0)} style={{ width: '100%', background: '#1e1e1e', color: '#d4d4d4', border: '1px solid #555' }} />
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>
                <input type="checkbox" checked={agentActive} onChange={(e) => setAgentActive(e.target.checked)} />
                {' '}Active
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>
                <input type="checkbox" checked={agentRequiresHITL} onChange={(e) => setAgentRequiresHITL(e.target.checked)} />
                {' '}Requires Human Approval
              </label>
              <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <button onClick={() => { saveAgentOverlayToNode(); setShowAgentOverlay(false); }} style={{ background: '#5cb85c', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Save (to workflow)</button>
                <button onClick={() => { saveAgentOverlayRef(); }} style={{ background: '#337ab7', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Save Ref</button>
                <button onClick={handleSaveAgentRefToFile} style={{ background: '#17a2b8', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Save ref to file</button>
                <button onClick={() => { resetAgentOverlayToRef(); }} style={{ background: '#f0ad4e', color: '#000', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Reset to Ref</button>
                <button onClick={() => setShowAgentOverlay(false)} style={{ background: '#555', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Close</button>
              </div>
            </div>
          </div>
        )}

        {showListOverlay && (
          <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
            <div style={{ background: '#2d2d2d', padding: 16, borderRadius: 8, width: 400, maxHeight: '80vh', overflow: 'auto', color: '#d4d4d4', fontSize: 12 }}>
              <h3 style={{ margin: '0 0 8px' }}>Workflows</h3>
              {listWorkflows().length === 0 && remoteWorkflows.length === 0 && <p style={{ color: '#999' }}>No saved workflows.</p>}
              {[...new Set([...listWorkflows().map((w) => w.id), ...remoteWorkflows])].map((id) => (
                <div key={id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 6, border: '1px solid #555', borderRadius: 4, marginBottom: 6 }}>
                  <span>{id}</span>
                  <div>
                    <button onClick={() => handleLoad(id)} style={{ background: '#337ab7', color: '#fff', border: 'none', padding: '2px 8px', borderRadius: 4, marginRight: 4, fontSize: 11 }}>Load</button>
                    <button onClick={async () => {
                      try {
                        const { Api } = await import('./services/api.ts');
                        await Api.workflows.delete(id);
                      } catch { deleteWorkflow(id); }
                      setRemoteWorkflows((prev) => prev.filter((x) => x !== id));
                    }} style={{ background: '#d9534f', color: '#fff', border: 'none', padding: '2px 8px', borderRadius: 4, fontSize: 11 }}>Delete</button>
                  </div>
                </div>
              ))}
              <div style={{ marginTop: 8 }}>
                <label style={{ fontSize: 11, color: '#999' }}>Import from file: <input type="file" accept=".json" onChange={handleLoadFromFile} style={{ fontSize: 11 }} /></label>
              </div>
              <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
                <button onClick={() => {
                  setNodes([
                    { id: 'start-1', type: 'start', position: { x: 50, y: 100 }, data: { title: 'Start' } },
                    { id: 'end-1', type: 'end', position: { x: 300, y: 100 }, data: { title: 'End' } },
                  ]);
                  setEdges([{ id: 'e-start-end', source: 'start-1', target: 'end-1', type: 'labeled' }]);
                  setWorkflowName('');
                  setShowListOverlay(false);
                }} style={{ background: '#5cb85c', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>New (Start+End)</button>
                <button onClick={() => setShowListOverlay(false)} style={{ background: '#555', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4 }}>Close</button>
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}
