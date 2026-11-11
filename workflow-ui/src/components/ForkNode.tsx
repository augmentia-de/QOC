import { Handle, Position } from '@xyflow/react';

export function ForkNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-cyan-600 border-2 border-cyan-400 min-w-[80px] text-center">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'Fork')}</div>
      <div className="text-xs opacity-70">∥ parallel</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
