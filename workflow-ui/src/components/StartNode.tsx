import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function StartNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'Start')}
      color="bg-green-600 border-green-400"
      selected={selected}
      shape="full"
    >
      <PortRow right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}
