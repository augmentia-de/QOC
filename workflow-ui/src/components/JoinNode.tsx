import { useEffect } from 'react';
import {
  useNodeConnections,
  useUpdateNodeInternals,
  type NodeProps,
} from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function JoinNode({ id, data, selected }: NodeProps) {
  const updateNodeInternals = useUpdateNodeInternals();
  const incoming = useNodeConnections({ handleType: 'target' });

  const maxIndex = incoming.reduce((max, connection) => {
    const match = /^in-(\d+)$/.exec(connection.targetHandle || '');
    return match ? Math.max(max, Number(match[1])) : max;
  }, -1);
  const handleCount = Math.max(1, maxIndex + 2);

  useEffect(() => {
    updateNodeInternals(id);
  }, [id, handleCount, updateNodeInternals]);

  const sourceFor = (handleId: string) =>
    incoming.find((connection) => connection.targetHandle === handleId)?.source;

  return (
    <NodeShell
      title={String(data.title || 'Join')}
      subtitle={`merge · ${String(data.joinType || 'all')}`}
      color="bg-teal-600 border-teal-400"
      selected={selected}
    >
      {Array.from({ length: handleCount }, (_, index) => {
        const handleId = `in-${index}`;
        const source = sourceFor(handleId);
        const left = {
          id: handleId,
          label: source ? `← ${source}` : `In ${index + 1} +`,
          isConnectable: !source,
        };
        return index === 0 ? (
          <PortRow key={handleId} left={left} right={{ id: 'out', label: 'Out' }} />
        ) : (
          <PortRow key={handleId} left={left} />
        );
      })}
    </NodeShell>
  );
}
