import type { Edge, Node } from '@xyflow/react';
import type {
  Branch,
  NodeData,
  WorkflowDefinition,
  WorkflowEdge,
  WorkflowNode,
} from '../types/workflow';

/**
 * Canonical conversion between the persisted workflow definition and the
 * React Flow graph.
 *
 * Both directions deliberately preserve the complete node `data` object and
 * the edge metadata (`sourceHandle`, `targetHandle`, `condition`). Node types
 * are passed through unchanged so new/custom node types do not silently
 * degrade to `agent` when a workflow is saved or reloaded.
 */

const SINGLE_SOURCE_HANDLES: Record<string, string> = {
  start: 'out',
  agent: 'out',
  hitl: 'out',
  subworkflow: 'out',
  loop: 'out',
  llm_eval: 'out',
  llm_summarize: 'out',
  mcp_call: 'out',
  join: 'out',
};

const SINGLE_TARGET_HANDLES: Record<string, string> = {
  agent: 'in',
  end: 'in',
  hitl: 'in',
  subworkflow: 'in',
  loop: 'in',
  llm_eval: 'in',
  llm_summarize: 'in',
  mcp_call: 'in',
  decision: 'in',
  fork: 'in',
};

const EDGE_LABEL_STYLE = { fill: '#fbbf24', fontSize: 10 };
const EDGE_LABEL_BG_STYLE = { fill: '#111827', fillOpacity: 0.85 };

function branchesOf(node: Node): Branch[] {
  return Array.isArray(node.data?.branches) ? (node.data.branches as Branch[]) : [];
}

function conditionOf(edge: Edge): string {
  return String((edge.data as Record<string, unknown> | undefined)?.condition ?? '');
}

function nextFreeHandle(
  nodeId: string,
  prefix: string,
  usedByNode: Map<string, Set<string>>,
  counters: Map<string, number>,
): string {
  let used = usedByNode.get(nodeId);
  if (!used) {
    used = new Set<string>();
    usedByNode.set(nodeId, used);
  }
  let index = counters.get(nodeId) ?? 0;
  while (used.has(`${prefix}${index}`)) index += 1;
  counters.set(nodeId, index + 1);
  const handle = `${prefix}${index}`;
  used.add(handle);
  return handle;
}

/**
 * Assigns the canonical handle IDs to legacy edges (old workflows stored
 * edges without `sourceHandle`/`targetHandle`). This is also a safety net for
 * edges created programmatically.
 */
export function normalizeEdgeHandles(nodes: Node[], edges: Edge[]): Edge[] {
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const usedSourceHandles = new Map<string, Set<string>>();
  const usedTargetHandles = new Map<string, Set<string>>();
  for (const edge of edges) {
    if (edge.sourceHandle) {
      const used = usedSourceHandles.get(edge.source) ?? new Set<string>();
      used.add(edge.sourceHandle);
      usedSourceHandles.set(edge.source, used);
    }
    if (edge.targetHandle) {
      const used = usedTargetHandles.get(edge.target) ?? new Set<string>();
      used.add(edge.targetHandle);
      usedTargetHandles.set(edge.target, used);
    }
  }
  const sourceCounters = new Map<string, number>();
  const targetCounters = new Map<string, number>();

  return edges.map((edge) => {
    const source = nodeById.get(edge.source);
    const target = nodeById.get(edge.target);
    let sourceHandle = edge.sourceHandle || '';
    let targetHandle = edge.targetHandle || '';

    if (source) {
      const type = source.type || 'agent';
      if (!sourceHandle) {
        if (type === 'decision') {
          const branches = branchesOf(source);
          const condition = conditionOf(edge);
          const branch =
            branches.find((candidate) => condition && String(candidate.condition || '') === condition) ||
            branches.find((candidate) => String(candidate.target || '') === edge.target);
          sourceHandle = branch?.branchId ? `branch-${branch.branchId}` : 'default';
        } else if (type === 'fork') {
          sourceHandle = nextFreeHandle(source.id, 'out-', usedSourceHandles, sourceCounters);
        } else if (SINGLE_SOURCE_HANDLES[type]) {
          sourceHandle = SINGLE_SOURCE_HANDLES[type];
        }
      } else if (type === 'decision' && (sourceHandle === 'true' || sourceHandle === 'false')) {
        // Migration from the old DecisionNode with true/false handles.
        sourceHandle = sourceHandle === 'true' && branchesOf(source)[0]?.branchId
          ? `branch-${branchesOf(source)[0].branchId}`
          : 'default';
      } else if (SINGLE_SOURCE_HANDLES[type] && sourceHandle !== SINGLE_SOURCE_HANDLES[type]) {
        sourceHandle = SINGLE_SOURCE_HANDLES[type];
      }
    }

    if (target) {
      const type = target.type || 'agent';
      if (!targetHandle) {
        if (type === 'join') {
          targetHandle = nextFreeHandle(target.id, 'in-', usedTargetHandles, targetCounters);
        } else if (SINGLE_TARGET_HANDLES[type]) {
          targetHandle = SINGLE_TARGET_HANDLES[type];
        }
      } else if (SINGLE_TARGET_HANDLES[type] && targetHandle !== SINGLE_TARGET_HANDLES[type]) {
        targetHandle = SINGLE_TARGET_HANDLES[type];
      }
    }

    return {
      ...edge,
      sourceHandle: sourceHandle || undefined,
      targetHandle: targetHandle || undefined,
    };
  });
}

/** Adds condition labels to decision edges for display. */
export function decorateEdges(nodes: Node[], edges: Edge[]): Edge[] {
  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  return edges.map((edge) => {
    const source = nodeById.get(edge.source);
    if (source?.type === 'decision') {
      const handle = edge.sourceHandle || '';
      if (handle.startsWith('branch-')) {
        const branchId = handle.slice('branch-'.length);
        const branch = branchesOf(source).find(
          (candidate) => String(candidate.branchId || '') === branchId,
        );
        return {
          ...edge,
          label: branch?.condition ? String(branch.condition) : 'branch',
          labelStyle: EDGE_LABEL_STYLE,
          labelBgStyle: EDGE_LABEL_BG_STYLE,
        };
      }
      if (handle === 'default') {
        return {
          ...edge,
          label: 'default',
          labelStyle: EDGE_LABEL_STYLE,
          labelBgStyle: EDGE_LABEL_BG_STYLE,
        };
      }
    }

    const condition = conditionOf(edge);
    if (condition) {
      return {
        ...edge,
        label: condition,
        labelStyle: EDGE_LABEL_STYLE,
        labelBgStyle: EDGE_LABEL_BG_STYLE,
      };
    }
    return edge;
  });
}

function newEdgeId(source: string, handle: string): string {
  return `edge-${source}-${handle}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Keeps decision edges in sync with the branch configuration in the property
 * panel. Branches without a target lose their edge; new branch targets get a
 * new edge with the corresponding source handle.
 */
export function reconcileDecisionEdges(node: Node, edges: Edge[]): Edge[] {
  if (node.type !== 'decision') return edges;

  const branches = branchesOf(node);
  const defaultTarget = String(node.data?.defaultTarget || '');
  const allowedHandles = new Set<string>([
    'default',
    ...branches.map((branch, index) => `branch-${branch.branchId || `b${index + 1}`}`),
  ]);

  // Drop stale edges from this decision node (e.g. old true/false handles).
  const result = edges.filter(
    (edge) => edge.source !== node.id || allowedHandles.has(String(edge.sourceHandle || '')),
  );

  for (let index = 0; index < branches.length; index += 1) {
    const branch = branches[index];
    const branchId = String(branch.branchId || `b${index + 1}`);
    const handleId = `branch-${branchId}`;
    const target = String(branch.target || '');
    const existingIndex = result.findIndex(
      (edge) => edge.source === node.id && edge.sourceHandle === handleId,
    );

    if (!target) {
      if (existingIndex >= 0) result.splice(existingIndex, 1);
      continue;
    }

    if (existingIndex >= 0) {
      result[existingIndex] = {
        ...result[existingIndex],
        target,
        data: { ...(result[existingIndex].data ?? {}), condition: String(branch.condition || '') },
      };
    } else {
      result.push({
        id: newEdgeId(node.id, handleId),
        source: node.id,
        sourceHandle: handleId,
        target,
        data: { condition: String(branch.condition || '') },
      });
    }
  }

  const defaultIndex = result.findIndex(
    (edge) => edge.source === node.id && edge.sourceHandle === 'default',
  );
  if (!defaultTarget) {
    if (defaultIndex >= 0) result.splice(defaultIndex, 1);
  } else if (defaultIndex >= 0) {
    result[defaultIndex] = {
      ...result[defaultIndex],
      target: defaultTarget,
      data: { ...(result[defaultIndex].data ?? {}), condition: '' },
    };
  } else {
    result.push({
      id: newEdgeId(node.id, 'default'),
      source: node.id,
      sourceHandle: 'default',
      target: defaultTarget,
      data: { condition: '' },
    });
  }

  return result;
}

/** Updates the branch target when a decision edge is connected. */
export function syncDecisionTarget(
  nodes: Node[],
  sourceId: string,
  sourceHandle: string | null | undefined,
  targetId: string,
): Node[] {
  if (!sourceHandle) return nodes;
  return nodes.map((node) => {
    if (node.id !== sourceId || node.type !== 'decision') return node;
    if (sourceHandle.startsWith('branch-')) {
      const branchId = sourceHandle.slice('branch-'.length);
      const branches = branchesOf(node).map((branch) =>
        String(branch.branchId || '') === branchId ? { ...branch, target: targetId } : branch,
      );
      return { ...node, data: { ...node.data, branches } };
    }
    if (sourceHandle === 'default') {
      return { ...node, data: { ...node.data, defaultTarget: targetId } };
    }
    return node;
  });
}

/** Clears branch/default targets whose decision edge was deleted. */
export function clearDecisionTargets(nodes: Node[], deletedEdges: Edge[]): Node[] {
  return nodes.map((node) => {
    if (node.type !== 'decision') return node;
    let changed = false;
    const branches = branchesOf(node).map((branch, index) => {
      const handleId = `branch-${branch.branchId || `b${index + 1}`}`;
      if (deletedEdges.some((edge) => edge.source === node.id && edge.sourceHandle === handleId)) {
        changed = true;
        return { ...branch, target: '' };
      }
      return branch;
    });
    let defaultTarget = String(node.data?.defaultTarget || '');
    if (deletedEdges.some((edge) => edge.source === node.id && edge.sourceHandle === 'default')) {
      defaultTarget = '';
      changed = true;
    }
    return changed ? { ...node, data: { ...node.data, branches, defaultTarget } } : node;
  });
}

export function toFlowNodes(nodes: WorkflowNode[] = []): Node[] {
  return nodes.map((node) => ({
    id: node.id,
    type: node.type || 'agent',
    position: node.position ?? { x: 0, y: 0 },
    data: { ...(node.data ?? {}) },
  }));
}

export function toFlowEdges(edges: WorkflowEdge[] = []): Edge[] {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle || undefined,
    targetHandle: edge.targetHandle || undefined,
    type: edge.type || undefined,
    data: { condition: edge.condition || '' },
  }));
}

export function toFlowGraph(def: WorkflowDefinition | null): {
  nodes: Node[];
  edges: Edge[];
} {
  if (!def) return { nodes: [], edges: [] };
  const nodes = toFlowNodes(def.nodes || []);
  return {
    nodes,
    edges: normalizeEdgeHandles(nodes, toFlowEdges(def.edges || [])),
  };
}

export function toDefinitionNodes(nodes: Node[] = []): WorkflowNode[] {
  return nodes.map((node) => ({
    id: node.id,
    type: node.type || 'agent',
    position: node.position,
    // React Flow data is the persisted node data. Do not add hard-coded
    // defaults here: the backend model already applies defaults and would
    // otherwise overwrite user configuration on every save.
    data: { ...(node.data ?? {}) } as NodeData,
  }));
}

export function toDefinitionEdges(edges: Edge[] = []): WorkflowEdge[] {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: edge.type || 'default',
    condition: conditionOf(edge),
    sourceHandle: edge.sourceHandle || '',
    targetHandle: edge.targetHandle || '',
  }));
}

export function toDefinitionGraph(
  nodes: Node[],
  edges: Edge[],
): { nodes: WorkflowNode[]; edges: WorkflowEdge[] } {
  return {
    nodes: toDefinitionNodes(nodes),
    edges: toDefinitionEdges(normalizeEdgeHandles(nodes, edges)),
  };
}
