import type { Edge, Node } from '@xyflow/react';

export interface ConnectionLike {
  source: string | null;
  target: string | null;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

const SINGLE_INCOMING = new Set([
  'agent',
  'end',
  'hitl',
  'subworkflow',
  'loop',
  'llm_eval',
  'llm_summarize',
  'mcp_call',
  'decision',
  'fork',
]);

const SINGLE_OUTGOING = new Set([
  'start',
  'agent',
  'hitl',
  'subworkflow',
  'loop',
  'llm_eval',
  'llm_summarize',
  'mcp_call',
  'join',
]);

const ONE_EDGE_PER_HANDLE = new Set(['decision', 'fork', 'join']);

function createsCycle(edges: Edge[], sourceId: string, targetId: string): boolean {
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) {
    const targets = adjacency.get(edge.source) ?? [];
    targets.push(edge.target);
    adjacency.set(edge.source, targets);
  }

  const stack = [targetId];
  const seen = new Set<string>();
  while (stack.length > 0) {
    const current = stack.pop()!;
    if (current === sourceId) return true;
    if (seen.has(current)) continue;
    seen.add(current);
    stack.push(...(adjacency.get(current) ?? []));
  }
  return false;
}

/**
 * Connection contract for the workflow graph. The runner is a single-walk
 * executor, therefore only decision and fork nodes may fan out. Join is the
 * only node that accepts more than one incoming edge.
 */
export function isValidConnection(
  connection: ConnectionLike,
  nodes: Node[],
  edges: Edge[],
): boolean {
  const sourceId = connection.source;
  const targetId = connection.target;
  if (!sourceId || !targetId || sourceId === targetId) return false;

  const source = nodes.find((node) => node.id === sourceId);
  const target = nodes.find((node) => node.id === targetId);
  if (!source || !target) return false;

  const sourceType = source.type || 'agent';
  const targetType = target.type || 'agent';
  if (sourceType === 'end' || targetType === 'start') return false;

  const sourceHandle = connection.sourceHandle || '';
  const targetHandle = connection.targetHandle || '';

  const duplicate = edges.some(
    (edge) =>
      edge.source === sourceId &&
      edge.target === targetId &&
      (edge.sourceHandle || '') === sourceHandle &&
      (edge.targetHandle || '') === targetHandle,
  );
  if (duplicate) return false;

  if (SINGLE_OUTGOING.has(sourceType) && edges.some((edge) => edge.source === sourceId)) {
    return false;
  }
  if (SINGLE_INCOMING.has(targetType) && edges.some((edge) => edge.target === targetId)) {
    return false;
  }

  if (
    ONE_EDGE_PER_HANDLE.has(sourceType) &&
    sourceHandle &&
    edges.some((edge) => edge.source === sourceId && edge.sourceHandle === sourceHandle)
  ) {
    return false;
  }
  if (
    ONE_EDGE_PER_HANDLE.has(targetType) &&
    targetHandle &&
    edges.some((edge) => edge.target === targetId && edge.targetHandle === targetHandle)
  ) {
    return false;
  }

  if (createsCycle(edges, sourceId, targetId)) return false;

  return true;
}
