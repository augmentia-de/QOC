import { useCallback, useEffect, useRef, useState } from 'react';
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
import { PropertyPanel } from '../components/PropertyPanel';
import ELK from 'elkjs/lib/elk.bundled.js';
import type { WorkflowDefinition } from '../types/workflow';
import { WorkflowApi } from '../services/api';

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
};

const elk = new ELK();

interface GraphEditorProps {
  nodesJson?: unknown[];
  workflowId?: string;
  agents?: any[];
  onGraphChange?: (data: { nodes: any[]; edges: any[] }) => void;
}

// Convert backend workflow to ReactFlow nodes/edges
function toReactFlow(def: WorkflowDefinition | null): { nodes: Node[]; edges: Edge[] } {
  if (!def) return { nodes: [], edges: [] };
  const nodes: Node[] = (def.nodes || []).map((n) => ({
    id: n.id,
    type: ['end', 'start', 'decision', 'hitl', 'subworkflow', 'fork', 'join', 'loop', 'llm_eval', 'llm_summarize'].includes(n.type || '') ? n.type! : 'agent',
    position: n.position || { x: 0, y: 0 },
    data: {
      title: n.data?.title || n.id,
      agentRef: n.data?.agentRef || '',
      requiresHumanApproval: n.data?.requiresHumanApproval || false,
    },
  }));
  const edges: Edge[] = (def.edges || []).map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    data: { condition: (e as any).condition || '' },
  }));
  return { nodes, edges };
}

// Convert ReactFlow nodes/edges back to backend format
function toBackend(nodes: Node[], edges: Edge[], agents: { id: string; name?: string }[]): { nodes: any[]; edges: any[] } {
  return {
    nodes: nodes.map((n) => ({
    id: n.id,
    type: ['end', 'start', 'decision', 'hitl', 'subworkflow', 'fork', 'join', 'loop', 'llm_eval', 'llm_summarize'].includes(n.type || '') ? n.type! : 'agent',
    position: n.position,
    data: {
      title: String(n.data?.title || n.id),
      agentRef: String(n.data?.agentRef || ''),
      requiresHumanApproval: Boolean(n.data?.requiresHumanApproval),
      assignee: 'human',
      maxIterations: 3,
      exitCondition: '',
      bodySteps: [],
      joinType: 'all',
      joinN: null,
      branches: [],
      defaultTarget: '',
      workflowRef: '',
    },
  })),
  edges: edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    type: e.type || 'default',
    condition: (e.data as any)?.condition || (e as any).condition || '',
  })),
  };
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

const DEFAULT_AGENTS = [
  { id: 'build', name: 'Build Agent', version: '1.0.0', category: 'core', model: 'deepseek-v4.1-flash', temperature: 0.3, maxTokens: 4096, topP: 1.0, tools: [], guardrailsInput: [], guardrailsOutput: [], hooks: [], jsonOutput: false, active: true, systemPrompt: 'You are a helpful build agent. Reply concisely.' },
];

export default function GraphEditor({ nodesJson, workflowId, agents = [], onGraphChange }: GraphEditorProps) {
  // Ensure build agent is always available
  const allAgents = [...agents];
  if (!allAgents.some((a: any) => a.id === 'build')) {
    allAgents.unshift({ ...DEFAULT_AGENTS[0] });
  }
  if (allAgents.length === 0) {
    allAgents.push({ ...DEFAULT_AGENTS[0] });
  }
  const [nodes, setNodes, onNodesChangeInternalRaw] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChangeInternalRaw] = useEdgesState<Edge>([]);
  const edgesRef = useRef(edges);
  edgesRef.current = edges;

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
          const rfNodes: Node[] = def.nodes.map((n: any) => ({
            id: n.id,
            type: ['end', 'start', 'decision', 'hitl', 'subworkflow', 'fork', 'join', 'loop', 'llm_eval', 'llm_summarize'].includes(n.type || '') ? n.type! : 'agent',
            position: n.position || { x: 0, y: 0 },
            data: {
              title: n.data?.title || n.id,
              agentRef: n.data?.agentRef || '',
              requiresHumanApproval: n.data?.requiresHumanApproval || false,
            },
          }));
          const rfEdges: Edge[] = (def.edges || []).map((e: any) => ({
            id: e.id,
            source: e.source,
            target: e.target,
          }));
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
      const rfNodes: Node[] = n.map((node) => ({
        id: node.id,
        type: ['end', 'start', 'decision', 'hitl', 'subworkflow', 'fork', 'join', 'loop', 'llm_eval', 'llm_summarize'].includes(node.type || '') ? node.type! : 'agent',
        position: node.position || { x: 0, y: 0 },
        data: {
          title: node.data?.title || node.title || node.id,
          agentRef: node.data?.agentRef || node.agent?.ref || '',
          requiresHumanApproval: node.data?.requiresHumanApproval || node.requiresHumanApproval || false,
        },
      }));
      // Build edges from dependsOn if no explicit edges
      const rfEdges: Edge[] = [];
      n.forEach((node) => {
        const deps = node.dependsOn || node.data?.dependsOn || [];
        deps.forEach((dep: string) => {
          rfEdges.push({ id: `${dep}->${node.id}`, source: dep, target: node.id });
        });
      });
      setNodes(rfNodes);
      setEdges(rfEdges);
      setLoading(false);
    } else {
      // Default
      setNodes([
        { id: 'start', type: 'agent', position: { x: 0, y: 0 }, data: { title: 'Start', agentRef: 'build' } },
        { id: 'end', type: 'end', position: { x: 300, y: 0 }, data: { title: 'Done' } },
      ]);
      setEdges([{ id: 'e1', source: 'start', target: 'end' }]);
      setLoading(false);
    }
  }, [nodesJson]);

  const onConnect = useCallback(
    (params: Connection) => {
      setEdges((eds) => {
        const newEdges = addEdge(params, eds);
        if (onGraphChange) {
          isUpdating.current = true;
          onGraphChange({ nodes, edges: newEdges });
        }
        return newEdges;
      });
    },
    [setEdges, nodes, onGraphChange]
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: { id: string; type?: string; data: Record<string, unknown> }) => {
    setSelectedNode({ id: node.id, type: node.type || 'agent', data: node.data });
  }, []);

  const updateNodeData = useCallback((nodeId: string, data: Record<string, unknown>) => {
    setNodes((nds) =>
      nds.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...data } } : n))
    );
    setSelectedNode((prev) =>
      prev && prev.id === nodeId ? { ...prev, data: { ...prev.data, ...data } } : prev
    );
    // Emit changes
    if (onGraphChange) {
      isUpdating.current = true;
      const updated = nodes.map((n) =>
        n.id === nodeId ? { ...n, data: { ...n.data, ...data } } : n
      );
      onGraphChange(toBackend(updated, edges, agents) as any);
    }
  }, [setNodes, nodes, edges, agents, onGraphChange]);

  const handleAutoLayout = useCallback(async () => {
    const result = await autoLayout(nodes, edges);
    setNodes(result.nodes);
    setEdges(result.edges);
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toBackend(result.nodes, result.edges, agents) as any);
    }
  }, [nodes, edges, setNodes, setEdges, agents, onGraphChange]);

  const undo = useCallback(() => {
    if (historyIndex <= 0) return;
    const prev = history[historyIndex - 1];
    setNodes(prev.nodes);
    setEdges(prev.edges);
    setHistoryIndex((i) => i - 1);
  }, [history, historyIndex, setNodes, setEdges]);

  const redo = useCallback(() => {
    if (historyIndex >= history.length - 1) return;
    const next = history[historyIndex + 1];
    setNodes(next.nodes);
    setEdges(next.edges);
    setHistoryIndex((i) => i + 1);
  }, [history, historyIndex, setNodes, setEdges]);

  const addNode = useCallback(() => {
    const id = `node-${nodes.length + 1}`;
    const newNode: Node = { id, type: 'agent', position: { x: 100, y: 100 }, data: { title: id, agentRef: '' } };
    const updatedNodes = [...nodes, newNode];
    setNodes(updatedNodes);
    pushHistory(updatedNodes, edges);
    if (onGraphChange) {
      isUpdating.current = true;
      onGraphChange(toBackend(updatedNodes, edges, agents) as any);
    }
  }, [nodes, edges, agents, setNodes, onGraphChange, pushHistory]);

  if (loading) return <div className="flex items-center justify-center h-full text-gray-400">Lade...</div>;

  return (
    <div className="flex h-[calc(100vh-64px)]">
      <div className="flex-1">
        <div className="bg-gray-800 border-b border-gray-700">
          <div className="p-2 flex gap-2 flex-wrap">
            <span className="text-xs text-gray-400 self-center mr-1">Nodes:</span>
            <button onClick={addNode} className="px-2 py-1 bg-blue-600 rounded text-xs">+ Agent</button>
            <button onClick={() => {
            const id = `start-${Date.now()}`;
            const newNode: Node = { id, type: 'start', position: { x: 100, y: 100 }, data: { title: 'Start' } };
            const updated = [...nodes, newNode];
            setNodes(updated); pushHistory(updated, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(updated, edges, agents) as any); }
          }} className="px-2 py-1 bg-green-600 rounded text-xs">+ Start</button>
            <button onClick={() => {
            const id = `decision-${Date.now()}`;
            const n: Node = { id, type: 'decision', position: { x: 100, y: 100 }, data: { title: 'Decision', branches: [], defaultTarget: '' } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-amber-600 rounded text-xs">+ Decision</button>
            <button onClick={() => {
            const id = `hitl-${Date.now()}`;
            const n: Node = { id, type: 'hitl', position: { x: 100, y: 100 }, data: { title: 'HITL', requiresHumanApproval: true } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-purple-600 rounded text-xs">+ HITL</button>
            <button onClick={() => {
            const id = `subwf-${Date.now()}`;
            const n: Node = { id, type: 'subworkflow', position: { x: 100, y: 100 }, data: { title: 'Sub-Workflow', workflowRef: '' } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-indigo-600 rounded text-xs">+ SubWF</button>
            <button onClick={() => {
            const id = `fork-${Date.now()}`;
            const n: Node = { id, type: 'fork', position: { x: 100, y: 100 }, data: { title: 'Fork' } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-cyan-600 rounded text-xs">+ Fork</button>
            <button onClick={() => {
            const id = `join-${Date.now()}`;
            const n: Node = { id, type: 'join', position: { x: 100, y: 100 }, data: { title: 'Join', joinType: 'all' } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-teal-600 rounded text-xs">+ Join</button>
            <button onClick={() => {
            const id = `loop-${Date.now()}`;
            const n: Node = { id, type: 'loop', position: { x: 100, y: 100 }, data: { title: 'Loop', maxIterations: 3, exitCondition: '' } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-orange-600 rounded text-xs">+ Loop</button>
            <button onClick={() => {
            const id = `end-${Date.now()}`;
            const n: Node = { id, type: 'end', position: { x: 100, y: 100 }, data: { title: 'End' } };
            const u = [...nodes, n]; setNodes(u); pushHistory(u, edges);
            if (onGraphChange) { isUpdating.current = true; onGraphChange(toBackend(u, edges, agents) as any); }
          }} className="px-2 py-1 bg-gray-600 rounded text-xs">+ End</button>
          </div>
          <div className="p-2 flex gap-2 border-t border-gray-700">
            <span className="text-xs text-gray-400 self-center mr-1">Actions:</span>
            <button onClick={async () => {
              const id = workflowId || prompt('Workflow ID eingeben:');
              if (!id) return;
              try {
                let wf;
                try { wf = await WorkflowApi.get(id); } catch { wf = await WorkflowApi.create(id); }
                const converted = toBackend(nodes, edges, agents);
                const updated = { ...wf.data, nodes: converted.nodes, edges: converted.edges };
                await WorkflowApi.save(id, updated as any);
                alert('Gespeichert: ' + id);
              } catch (e) { console.error(e); alert('Speichern fehlgeschlagen'); }
            }} className="px-3 py-1 bg-green-600 rounded text-sm font-bold">💾 Speichern</button>
            <button onClick={async () => {
              const id = workflowId || prompt('Workflow ID eingeben:');
              if (!id) return;
              try {
                let wf;
                try { wf = await WorkflowApi.get(id); } catch { wf = await WorkflowApi.create(id); }
                const converted = toBackend(nodes, edges, agents);
                const updated = { ...wf.data, nodes: converted.nodes, edges: converted.edges };
                await WorkflowApi.save(id, updated as any);
                const res = await WorkflowApi.run(updated as any, 'Hello');
                alert('Ergebnis: ' + res.data.result);
              } catch (e) { console.error(e); alert('Ausführung fehlgeschlagen'); }
            }} className="px-3 py-1 bg-blue-600 rounded text-sm font-bold">▶ Ausführen</button>
            <button onClick={handleAutoLayout} className="px-3 py-1 bg-gray-600 rounded text-sm">Auto-Layout</button>
            <button onClick={undo} className="px-3 py-1 bg-gray-600 rounded text-sm" disabled={historyIndex <= 0}>Undo</button>
            <button onClick={redo} className="px-3 py-1 bg-gray-600 rounded text-sm" disabled={historyIndex >= history.length - 1}>Redo</button>
          </div>
        </div>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChangeInternal}
          onEdgesChange={onEdgesChangeInternal}
          onConnect={onConnect}
          onNodeDragStop={() => {
            if (onGraphChange) {
              isUpdating.current = true;
              onGraphChange({ nodes, edges });
            }
          }}
          onNodeClick={onNodeClick}
          nodeTypes={nodeTypes}
          fitView
          multiSelectionKeyCode="Shift"
          selectionKeyCode="Shift"
          deleteKeyCode="Delete"
          onEdgesDelete={(deleted) => {
            const deletedIds = new Set(deleted.map((e) => e.id));
            const remaining = edges.filter((e) => !deletedIds.has(e.id));
            setEdges(remaining);
            if (onGraphChange) { isUpdating.current = true; onGraphChange({ nodes, edges: remaining }); }
          }}
          onNodesDelete={(deleted) => {
            const deletedIds = new Set(deleted.map((n) => n.id));
            const remaining = nodes.filter((n) => !deletedIds.has(n.id));
            setNodes(remaining);
            setEdges(edges.filter((e) => !deletedIds.has(e.source) && !deletedIds.has(e.target)));
            pushHistory(remaining, edges.filter((e) => !deletedIds.has(e.source) && !deletedIds.has(e.target)));
            if (onGraphChange) {
              onGraphChange(toBackend(remaining, edges.filter((e) => !deletedIds.has(e.source) && !deletedIds.has(e.target)), agents));
            }
          }}
        >
          <Controls />
          <Background />
        </ReactFlow>
      </div>
      <PropertyPanel
        selectedNode={selectedNode}
        onUpdate={updateNodeData}
        agents={allAgents as any}
        onAgentChange={(agent) => {
          // Update workflow agents list
          if (onGraphChange) {
            // Emit agent change via nodes change (simplified)
          }
        }}
        onCopyConfig={(agent) => {
          console.log('Copied agent config:', agent);
        }}
      />
    </div>
  );
}