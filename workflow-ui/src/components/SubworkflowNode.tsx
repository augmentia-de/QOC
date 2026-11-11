import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function SubworkflowNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'Sub-Workflow')}
      subtitle={String(data.workflowRef || 'no reference')}
      color="bg-indigo-600 border-indigo-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}
