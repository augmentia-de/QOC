import { Handle, Position } from '@xyflow/react';

export function LoopNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-orange-600 border-2 border-orange-400 min-w-[100px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'Loop')}</div>
      <div className="text-xs opacity-70">max: {String(data.maxIterations || 3)}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
