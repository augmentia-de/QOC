import { Handle, Position } from '@xyflow/react';

export function SubworkflowNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-indigo-600 border-2 border-indigo-400 min-w-[120px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'Sub-Workflow')}</div>
      <div className="text-xs opacity-70">{String(data.workflowRef || 'ref')}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
