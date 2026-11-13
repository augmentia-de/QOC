import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function LlmEvalNode({ data, selected }: NodeProps) {
  const jsonOutput = data.jsonOutput === false ? false : true;
  return (
    <NodeShell
      title={String(data.title || 'LLM Eval')}
      subtitle={jsonOutput ? 'evaluate → JSON' : 'evaluate → text'}
      color="bg-purple-600 border-purple-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}

export function LlmSummarizeNode({ data, selected }: NodeProps) {
  return (
    <NodeShell
      title={String(data.title || 'LLM Summarize')}
      subtitle="summarize"
      color="bg-indigo-600 border-indigo-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}

export function CompactNode({ data, selected }: NodeProps) {
  const args = (data.mcpArgs && typeof data.mcpArgs === 'object'
    ? data.mcpArgs as Record<string, unknown> : {}) as Record<string, unknown>;
  const mode = String(args.mode || 'truncate');
  return (
    <NodeShell
      title={String(data.title || 'Compact')}
      subtitle={`compact: ${mode}`}
      color="bg-emerald-600 border-emerald-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}
