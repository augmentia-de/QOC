import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function ContextAgentExecutorNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'Context Agent')}
      subtitle={String(data.agentRef || 'no agent')}
      color="bg-cyan-700 border-cyan-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <div className="px-3 pb-1 text-[10px] text-cyan-200">full context</div>
    </NodeShell>
  );
}
