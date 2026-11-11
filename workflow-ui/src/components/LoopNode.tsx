import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function LoopNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'Loop')}
      subtitle={`max: ${String(data.maxIterations || 3)}`}
      color="bg-orange-600 border-orange-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      {Boolean(data.exitCondition) && (
        <div className="truncate px-3 pb-1 text-[10px] text-orange-100/80" title={String(data.exitCondition)}>
          exit: {String(data.exitCondition)}
        </div>
      )}
    </NodeShell>
  );
}
