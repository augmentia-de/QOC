import { Handle, Position } from '@xyflow/react';

export function LlmEvalNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-purple-600 border-2 border-purple-400 min-w-[120px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'LLM Eval')}</div>
      <div className="text-xs opacity-70">evaluate → JSON</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export function LlmSummarizeNode({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="px-4 py-2 rounded-lg bg-indigo-600 border-2 border-indigo-400 min-w-[120px]">
      <Handle type="target" position={Position.Left} />
      <div className="font-bold text-sm">{String(data.title || 'LLM Summarize')}</div>
      <div className="text-xs opacity-70">summarize</div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}