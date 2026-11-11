import { Handle, Position } from '@xyflow/react';

export function DecisionNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-amber-600 border-2 border-amber-400 min-w-[120px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'Decision')}</div>
      <div className="text-xs opacity-70">if/else</div>
      <Handle type="source" position={Position.Right} id="true" />
      <Handle type="source" position={Position.Bottom} id="false" />
    </div>
  );
}
