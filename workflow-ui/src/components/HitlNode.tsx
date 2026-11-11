import { Handle, Position } from '@xyflow/react';

export function HitlNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-purple-600 border-2 border-purple-400 min-w-[120px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'HITL')}</div>
      <div className="text-xs opacity-70">Human Approval</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
