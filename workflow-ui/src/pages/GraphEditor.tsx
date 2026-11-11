import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  addEdge,
  type Node,
  type Edge,
  type Connection,
  type NodeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AgentNode } from '../components/AgentNode';
import { StartNode } from '../components/StartNode';
import { DecisionNode } from '../components/DecisionNode';
import { HitlNode } from '../components/HitlNode';
import { SubworkflowNode } from '../components/SubworkflowNode';
import { ForkNode } from '../components/ForkNode';
import { JoinNode } from '../components/JoinNode';
import { LoopNode } from '../components/LoopNode';
import { EndNode } from '../components/EndNode';
import { LlmEvalNode, LlmSummarizeNode } from '../components/LlmEvalNode';
import { McpCallNode } from '../components/McpCallNode';
import { AgentApi } from '../services/api';
import { PropertyPanel } from '../components/PropertyPanel';
import ELK from 'elkjs/lib/elk.bundled.js';
import { WorkflowApi } from '../services/api';
import {
  clearDecisionTargets,
  decorateEdges,
  normalizeEdgeHandles,
  reconcileDecisionEdges,
  syncDecisionTarget,
  toDefinitionGraph,
  toFlowEdges,
  toFlowNodes,
} from '../lib/workflowGraph';
import { isValidConnection } from '../lib/connectionRules';

const nodeTypes: NodeTypes = {
  agent: AgentNode,
  start: StartNode,
  decision: DecisionNode,
  hitl: HitlNode,
  subworkflow: SubworkflowNode,
  fork: ForkNode,
  join: JoinNode,
  loop: LoopNode,
  end: EndNode,
  llm_eval: LlmEvalNode,
  llm_summarize: LlmSummarizeNode,
  mcp_call: McpCallNode,
};

const elk = new ELK();

interface GraphEditorProps {
  nodesJson?: unknown[];
  workflowId?: string;
  agents?: any[];
  onGraphChange?: (data: { nodes: any[]; edges: any[] }) => void;
}

async function autoLayout(nodes: Node[], edges: Edge[]): Promise<{ nodes: Node[]; edges: Edge[] }> {
  const graph = {
    id: 'root',
    layoutOptions: { 'elk.algorithm': 'layered', 'elk.direction': 'RIGHT' },
    children: nodes.map((n) => ({ id: n.id, width: 180, height: 60 })),
    edges: edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
  const layouted = await elk.layout(graph);
  const posMap = new Map(layouted.children?.map((c: any) => [c.id, { x: c.x, y: c.y }]) || []);
  return {
    nodes: nodes.map((n) => ({ ...n, position: posMap.get(n.id) || n.position })),
    edges,
  };
}

export default function GraphEditor({ nodesJson, workflowId, agents = [], onGraphChange }: GraphEditorProps) {
  // Agents live in the global agent store (Option B). Load them here as well so
  // the standalone editor (/editor) offers the same list as the workflow detail.
  const [storeAgents, setStoreAgents] = useState<any[]>([]);
  useEffect(() => {
    AgentApi.list()
      .then((res) => setStoreAgents(res.data.agents || []))
      .catch((e) => console.error('Agent list failed', e));
  }, []);
  const allAgents = storeAgents.length > 0 ? storeAgents : agents;
  const [nodes, setNodes, onNodesChangeInternalRaw] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChangeInternalRaw] = useEdgesState<Edge>([]);
  const nodesRef = useRef<Node[]>(nodes);
  const edgesRef = useRef<Edge[]>(edges);
  nodesRef.current = nodes;
  edgesRef.current = edges;
  const displayEdges = useMemo(() => decorateEdges(nodes, edges), [nodes, edges]);

  const onNodesChangeInternal = useCallback((changes: any) => {
    onNodesChangeInternalRaw(changes);
  }, [onNodesChangeInternalRaw]);

  const onEdgesChangeInternal = useCallback((changes: any) => {
    onEdgesChangeInternalRaw(changes);
  }, [onEdgesChangeInternalRaw]);
  const [selectedNode, setSelectedNode] = useState<{ id: string; type: string; data: Record<string, unknown> } | null>(null);
  const [history, setHistory] = useState<{ nodes: Node[]; edges: Edge[] }[]>([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const [loading, setLoading] = useState(true);
  const isUpdating = useRef(false);
  const prevNodesJson = useRef<string>('');

  const pushHistory = useCallback((n: Node[], e: Edge[]) => {
    setHistory((prev) => {
      const next = prev.slice(0, historyIndex + 1);
      next.push({ nodes: JSON.parse(JSON.stringify(n)), edges: JSON.parse(JSON.stringify(e)) });
      return next;
    });
    setHistoryIndex((i) => i + 1);
  }, [historyIndex]);

  // Load workflow from backend if workflowId provided
  useEffect(() => {
    if (isUpdating.current) {
      isUpdating.current = false;
      return;
    }
    if (workflowId) {
      WorkflowApi.get(workflowId).then((res) => {
        const def = res.data;
        if (def.nodes && Array.isArray(def.nodes) && def.nodes.length > 0) {
          const rfNodes = toFlowNodes(def.nodes);
          const rfEdges = normalizeEdgeHandles(rfNodes, toFlowEdges(def.edges || []));
          nodesRef.current = rfNodes;
          edgesRef.current = rfEdges;
          setNodes(rfNodes);
          setEdges(rfEdges);
        }
        setLoading(false);
      }).catch(() => setLoading(false));
      return;
    }
    const jsonStr = JSON.stringify(nodesJson);
    if (jsonStr === prevNodesJson.current) return;
    prevNodesJson.current = jsonStr;
    if (nodesJson && Array.isArray(nodesJson) && nodesJson.length > 0) {
      const n = nodesJson as any[];
      // Legacy nodes may keep title/agentRef/requiresHumanApproval outside
      // of `data`. Normalise them without dropping any other field.
      const normalised = n.map((node) => ({
        ...node,
        type: node.type || 'agent',
        data: {
          ...(node.data ?? {}),
          title: node.data?.title || node.title || node.id,
          agentRef: node.data?.agentRef || node.agent?.ref || '',
          requiresHumanApproval:
            node.data?.requiresHumanApproval ?? node.requiresHumanApproval ?? false,
        },
      }));
      const rfNodes = toFlowNodes(normalised as any);
      // Build edges from dependsOn if no explicit edges
      const rfEdges: Edge[] = [];
      n.forEach((node) => {
        const deps = node.dependsOn || node.data?.dependsOn || [];
        deps.forEach((dep: string) => {
          rfEdges.push({ id: `${dep}->${node.id}`, source: dep, target: node.id });
        });
      });
      const normalisedEdges = normalizeEdgeHandles(rfNodes, rfEdges);
      nodesRef.current = rfNodes;
      edgesRef.current = normalisedEdges;
      setNodes(rfNodes);
      setEdges(normalisedEdges);
      setLoading(false);
    } else {
      // Default
      const defaultNodes: Node[] = [
        { id: 'start', type: 'agent', position: { x: 0, y: 0 }, data: { title: 'Start', agentRef: 'build' } },
        { id: 'end', type: 'end', position: { x: 300, y: 0 }, data: { title: 'Done' } },
      ];
      const defaultEdges: Edge[] = normalizeEdgeHandles(defaultNodes, [
        { id: 'e1', source: 'start', target: 'end' },
      ]);
      nodesRef.current = defaultNodes;
      edgesRef.current = defaultEdges;
      setNodes(defaultNodes);
      setEdges(defaultEdges);
      setLoading(false);
    }
  }, [nodesJson, workflowId]);

  const onConnect = useCallback(
    (params: Connection) => {
      const targetId = params.target || '';
      let updatedNodes = nodesRef.current;
      if (params.source && params.sourceHandle) {
        updatedNodes = syncDecisionTarget(
          updatedNodes,
          params.source,
          params.sourceHandle,
          targetId,
        );
      }

      let newEdges = addEdge(params, edgesRef.current);
      const sourceNode = updatedNodes.find((node) => node.id === params.source);
      if (sourceNode?.type === 'decision' && params.sourceHandle?.startsWith('branch-')) {
        const branchId = params.sourceHandle.slice('branch-'.length);
        const branches = Array.isArray(sourceNode.data.branches)
          ? (sourceNode.data.branches as any[])
          : [];
        const branch = branches.find(
          (candidate) => String(candidate.branchId || '') === branchId,
        );
        newEdges = newEdges.map((edge) =>
          edge.source === params.source &&
          edge.target === params.target &&
          (edge.sourceHandle || '') === (params.sourceHandle || '')
            ? {
                ...edge,
                data: { ...(edge.data ?? {}), condition: String(branch?.condition || '') },
              }
            : edge,
        );
      }
      newEdges = normalizeEdgeHandles(updatedNodes, newEdges);

      nodesRef.current = updatedNodes;
      edgesRef.current = newEdges;
      setNodes(updatedNodes);
      setEdges(newEdges);
      if (selectedNode && params.source && selectedNode.id === params.source) {
        const updatedSource = updatedNodes.find((node) => node.id === params.source);
        if (updatedSource) {
          setSelectedNode((prev) => (prev ? { ...prev, data: updatedSource.data } : prev));
        }
      }
      if (onGraphChange) {
        isUpdating.current = true;
        onGraphChange(toDefinitionGraph(updatedNodes, newEdges));
      }
    },
    [setNodes, setEdges, onGraphChange, selectedNode],
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: { id: string; type?: string; data: Record<string, unknown> }) => {
    setSelectedNode({ id: node.id, type: node.type || 'agent', data: node.data });
  }, []);

  const updateNodeData = useCallback((nodeId: string, data: Record<string, unknown>) => {
    const updatedNodes = nodesRef.current.map((n) =>
      n.id === nodeId ? { ...n, data: { ...n.data, ...data } } : n
    );
    const updatedNode = updatedNodes.find((node) => node.id === nodeId);
    let updatedEdges = edgesRef.current;
    if (updatedNode?.type === 'decision') {
      updatedEdges = reconcileDecisionEdges(updatedNode, updatedEdges);
    }
    updatedEdges = normalizeEdgeHandles(updatedNodes, updatedEdges);

    nodesRef.current = updatedNodes;
    edgesRef.current = updatedEdges;
    setNodes(updatedNodes);
    setEdges(updatedEdges);
    setSelectedNode((prev) =>
      prev && prev.id === nodeId
        ? { ...prev, data: updatedNode?.data ?? { ...prev.data, ...data } }
        : prev,
    );
    // Emit the complete graph so node-specific fields (branches, loop
    // settings, MCP config, ...) survive the save round-trip.
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toDefinitionGraph(updatedNodes, updatedEdges));
    }
  }, [setNodes, setEdges, onGraphChange]);

  const handleAutoLayout = useCallback(async () => {
    const result = await autoLayout(nodesRef.current, edgesRef.current);
    nodesRef.current = result.nodes;
    edgesRef.current = result.edges;
    setNodes(result.nodes);
    setEdges(result.edges);
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toDefinitionGraph(result.nodes, result.edges));
    }
  }, [setNodes, setEdges, onGraphChange]);

  const undo = useCallback(() => {
    if (historyIndex <= 0) return;
    const prev = history[historyIndex - 1];
    nodesRef.current = prev.nodes;
    edgesRef.current = prev.edges;
    setNodes(prev.nodes);
    setEdges(prev.edges);
    setHistoryIndex((i) => i - 1);
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toDefinitionGraph(prev.nodes, prev.edges));
    }
  }, [history, historyIndex, setNodes, setEdges, onGraphChange]);

  const redo = useCallback(() => {
    if (historyIndex >= history.length - 1) return;
    const next = history[historyIndex + 1];
    nodesRef.current = next.nodes;
    edgesRef.current = next.edges;
    setNodes(next.nodes);
    setEdges(next.edges);
    setHistoryIndex((i) => i + 1);
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toDefinitionGraph(next.nodes, next.edges));
    }
  }, [history, historyIndex, setNodes, setEdges, onGraphChange]);

  const addNodeOfType = useCallback((type: string, data: Record<string, unknown>) => {
    const id = `${type}-${Date.now()}`;
    const newNode: Node = {
      id,
      type,
      position: { x: 120, y: 120 },
      data: { title: id, ...data },
    };
    const updatedNodes = [...nodesRef.current, newNode];
    nodesRef.current = updatedNodes;
    setNodes(updatedNodes);
    pushHistory(updatedNodes, edgesRef.current);
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toDefinitionGraph(updatedNodes, edgesRef.current));
    }
  }, [setNodes, onGraphChange, pushHistory]);

  if (loading) return <div className="flex items-center justify-center h-full text-gray-400">Lade...</div>;

  const saveAgentConfig = async (agent: any) => {
    try {
      const exists = Boolean(agent.id) && allAgents.some((a: any) => a.id === agent.id);
      if (exists) {
        await AgentApi.save(agent.id, agent);
      } else {
        // New agent: let the backend derive the id from the name.
        const created = await AgentApi.create({ ...agent, id: '' });
        if (selectedNode) {
          updateNodeData(selectedNode.id, { agentRef: created.data.id });
        }
      }
      const res = await AgentApi.list();
      setStoreAgents(res.data.agents || []);
    } catch (e) {
      console.error('Agent config save failed', e);
      alert('Agent-Config konnte nicht gespeichert werden');
    }
  };

  const palette = [
    { label: '+ Agent', type: 'agent', color: 'bg-blue-600', data: { title: 'Agent', agentRef: '' } },
    { label: '+ Start', type: 'start', color: 'bg-green-600', data: { title: 'Start' } },
    { label: '+ Decision', type: 'decision', color: 'bg-amber-600', data: { title: 'Decision', branches: [], defaultTarget: '' } },
    { label: '+ HITL', type: 'hitl', color: 'bg-purple-600', data: { title: 'HITL', requiresHumanApproval: true } },
    { label: '+ SubWF', type: 'subworkflow', color: 'bg-indigo-600', data: { title: 'Sub-Workflow', workflowRef: '' } },
    { label: '+ Fork', type: 'fork', color: 'bg-cyan-600', data: { title: 'Fork' } },
    { label: '+ Join', type: 'join', color: 'bg-teal-600', data: { title: 'Join', joinType: 'all' } },
    { label: '+ Loop', type: 'loop', color: 'bg-orange-600', data: { title: 'Loop', maxIterations: 3, exitCondition: '' } },
    { label: '+ LLM Eval', type: 'llm_eval', color: 'bg-purple-700', data: { title: 'LLM Eval', jsonOutput: true } },
    { label: '+ LLM Sum', type: 'llm_summarize', color: 'bg-indigo-700', data: { title: 'LLM Summarize', defaultTarget: '' } },
    { label: '+ MCP Call', type: 'mcp_call', color: 'bg-fuchsia-700', data: { title: 'MCP Call', mcpServer: '', mcpTool: '', mcpArgs: {} } },
    { label: '+ End', type: 'end', color: 'bg-gray-600', data: { title: 'End' } },
  ];

  const saveWorkflow = async () => {
    const id = workflowId || prompt('Workflow ID eingeben:');
    if (!id) return;
    try {
      let wf;
      try { wf = await WorkflowApi.get(id); } catch { wf = await WorkflowApi.create(id); }
      const converted = toDefinitionGraph(nodesRef.current, edgesRef.current);
      const updated = { ...wf.data, nodes: converted.nodes, edges: converted.edges };
      await WorkflowApi.save(id, updated as any);
      alert('Gespeichert: ' + id);
    } catch (e) { console.error(e); alert('Speichern fehlgeschlagen'); }
  };

  const runWorkflow = async () => {
    const id = workflowId || prompt('Workflow ID eingeben:');
    if (!id) return;
    try {
      let wf;
      try { wf = await WorkflowApi.get(id); } catch { wf = await WorkflowApi.create(id); }
      const converted = toDefinitionGraph(nodesRef.current, edgesRef.current);
      const updated = { ...wf.data, nodes: converted.nodes, edges: converted.edges };
      await WorkflowApi.save(id, updated as any);
      const res = await WorkflowApi.run(updated as any, 'Hello');
      alert('Ergebnis: ' + res.data.result);
    } catch (e) { console.error(e); alert('Ausführung fehlgeschlagen'); }
  };

  return (
    <div className="flex h-full min-h-0">
      <aside className="w-40 shrink-0 bg-gray-800 border-r border-gray-700 overflow-y-auto">
        <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700">
          Nodes
        </div>
        <div className="p-2 flex flex-col gap-1">
          {palette.map((n) => (
            <button
              key={n.label}
              onClick={() => addNodeOfType(n.type, n.data)}
              className={`w-full text-left px-2 py-1 rounded text-xs ${n.color}`}
            >
              {n.label}
            </button>
          ))}
        </div>
      </aside>

      <div className="flex-1 flex flex-col min-w-0">
        <div className="p-2 flex flex-wrap items-center gap-2 bg-gray-800 border-b border-gray-700">
          <span className="text-xs text-gray-400 mr-1">Actions:</span>
          <button onClick={saveWorkflow} className="px-3 py-1 bg-green-600 rounded text-sm font-bold">💾 Speichern</button>
          <button onClick={runWorkflow} className="px-3 py-1 bg-blue-600 rounded text-sm font-bold">▶ Ausführen</button>
          <button onClick={handleAutoLayout} className="px-3 py-1 bg-gray-600 rounded text-sm">Auto-Layout</button>
          <button onClick={undo} className="px-3 py-1 bg-gray-600 rounded text-sm" disabled={historyIndex <= 0}>Undo</button>
          <button onClick={redo} className="px-3 py-1 bg-gray-600 rounded text-sm" disabled={historyIndex >= history.length - 1}>Redo</button>
        </div>

        <div className="flex-1 min-h-0 bg-gray-900">
          <ReactFlow
            nodes={nodes}
            edges={displayEdges}
            onNodesChange={onNodesChangeInternal}
            onEdgesChange={onEdgesChangeInternal}
            onConnect={onConnect}
            isValidConnection={(connection) =>
              isValidConnection(connection, nodesRef.current, edgesRef.current)
            }
            onNodeDragStop={(_event, _node, draggedNodes) => {
              if (onGraphChange) {
                // The third argument contains the final positions. Using it
                // avoids emitting stale positions before the next render.
                const currentNodes = draggedNodes ?? nodesRef.current;
                nodesRef.current = currentNodes;
                isUpdating.current = true;
                onGraphChange(toDefinitionGraph(currentNodes, edgesRef.current));
              }
            }}
            onNodeClick={onNodeClick}
            nodeTypes={nodeTypes}
            fitView
            multiSelectionKeyCode="Shift"
            selectionKeyCode="Shift"
            deleteKeyCode="Delete"
            style={{ background: '#111827' }}
            onEdgesDelete={(deleted) => {
              const deletedEdges = deleted as Edge[];
              const deletedIds = new Set(deletedEdges.map((e) => e.id));
              const remaining = edgesRef.current.filter((e) => !deletedIds.has(e.id));
              const updatedNodes = clearDecisionTargets(nodesRef.current, deletedEdges);
              nodesRef.current = updatedNodes;
              edgesRef.current = remaining;
              setNodes(updatedNodes);
              setEdges(remaining);
              if (selectedNode) {
                const updatedSelected = updatedNodes.find((node) => node.id === selectedNode.id);
                if (updatedSelected) {
                  setSelectedNode((prev) =>
                    prev ? { ...prev, data: updatedSelected.data } : prev,
                  );
                }
              }
              if (onGraphChange) {
                isUpdating.current = true;
                onGraphChange(toDefinitionGraph(updatedNodes, remaining));
              }
            }}
            onNodesDelete={(deleted) => {
              const deletedIds = new Set(deleted.map((n) => n.id));
              const remainingNodes = nodesRef.current.filter((n) => !deletedIds.has(n.id));
              const remainingEdges = edgesRef.current.filter(
                (e) => !deletedIds.has(e.source) && !deletedIds.has(e.target),
              );
              nodesRef.current = remainingNodes;
              edgesRef.current = remainingEdges;
              setNodes(remainingNodes);
              setEdges(remainingEdges);
              pushHistory(remainingNodes, remainingEdges);
              if (onGraphChange) {
                isUpdating.current = true;
                onGraphChange(toDefinitionGraph(remainingNodes, remainingEdges));
              }
            }}
          >
            <Controls />
            <Background color="#4b5563" gap={16} />
          </ReactFlow>
        </div>
      </div>

      <PropertyPanel
        selectedNode={selectedNode}
        onUpdate={updateNodeData}
        nodes={nodes.map((node) => ({
          id: node.id,
          type: node.type || 'agent',
          data: node.data as Record<string, unknown>,
        }))}
        edges={edges}
        agents={allAgents as any}
        onAgentChange={saveAgentConfig}
        onCopyConfig={(agent) => {
          console.log('Copied agent config:', agent);
        }}
      />
    </div>
  );
}