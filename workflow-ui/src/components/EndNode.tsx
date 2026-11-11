import { Handle, Position } from '@xyflow/react';

export function EndNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-gray-600 border-2 border-gray-400 min-w-[80px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'End')}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}