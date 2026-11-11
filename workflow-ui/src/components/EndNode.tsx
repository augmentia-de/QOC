import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function EndNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'End')}
      color="bg-gray-600 border-gray-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} />
    </NodeShell>
  );
}
