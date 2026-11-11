import { Handle, Position } from '@xyflow/react';

export function StartNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-full bg-green-600 border-2 border-green-400 min-w-[80px] text-center">
      <div className="font-bold text-sm">{String(data.title || 'Start')}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
