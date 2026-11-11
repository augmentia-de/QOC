import { useEffect } from 'react';
import {
  useNodeConnections,
  useUpdateNodeInternals,
  type NodeProps,
} from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function ForkNode({ id, data, selected }: NodeProps) {
  const updateNodeInternals = useUpdateNodeInternals();
  const outgoing = useNodeConnections({ handleType: 'source' });

  // Keep at least one free output so the user can add another branch by
  // dragging from the "+" handle.
  const maxIndex = outgoing.reduce((max, connection) => {
    const match = /^out-(\d+)$/.exec(connection.sourceHandle || '');
    return match ? Math.max(max, Number(match[1])) : max;
  }, -1);
  const handleCount = Math.max(1, maxIndex + 2);

  useEffect(() => {
    updateNodeInternals(id);
  }, [id, handleCount, updateNodeInternals]);

  const targetFor = (handleId: string) =>
    outgoing.find((connection) => connection.sourceHandle === handleId)?.target;

  return (
    <NodeShell
      title={String(data.title || 'Fork')}
      subtitle="parallel split"
      color="bg-cyan-600 border-cyan-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} />
      {Array.from({ length: handleCount }, (_, index) => {
        const handleId = `out-${index}`;
        const target = targetFor(handleId);
        return (
          <PortRow
            key={handleId}
            right={{
              id: handleId,
              label: target ? `→ ${target}` : `Out ${index + 1} +`,
              isConnectable: !target,
            }}
          />
        );
      })}
    </NodeShell>
  );
}
