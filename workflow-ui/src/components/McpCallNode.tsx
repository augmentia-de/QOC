import type { NodeProps } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

export function McpCallNode({ data, selected }: NodeProps) {
  const server = String(data.mcpServer || '');
  const tool = String(data.mcpTool || '');
  return (
    <NodeShell
      title={String(data.title || 'MCP Call')}
      subtitle={server || tool ? `${server || '?'} / ${tool || '?'}` : 'no tool selected'}
      color="bg-fuchsia-700 border-fuchsia-400"
      selected={selected}
    >
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}
