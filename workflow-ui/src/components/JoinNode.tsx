import { Handle, Position } from '@xyflow/react';

export function JoinNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-teal-600 border-2 border-teal-400 min-w-[80px] text-center">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'Join')}</div>
      <div className="text-xs opacity-70">merge</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
