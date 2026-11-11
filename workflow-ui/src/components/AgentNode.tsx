import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function AgentNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'Agent')}
      subtitle={String(data.agentRef || 'no agent')}
      color="bg-blue-600 border-blue-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      {Boolean(data.requiresHumanApproval) && (
        <div className="px-3 pb-1 text-[10px] font-semibold text-amber-200">
          HITL required
        </div>
      )}
    </NodeShell>
  );
}
