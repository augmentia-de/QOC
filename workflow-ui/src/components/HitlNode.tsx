import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function HitlNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'HITL')}
      subtitle={`approval · ${String(data.assignee || 'human')}`}
      color="bg-purple-600 border-purple-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}
