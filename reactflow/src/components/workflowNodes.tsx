import { useEffect } from 'react';
import { useNodeConnections, useUpdateNodeInternals } from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

function withError(children, data, selected, title, subtitle, color, shape) {
  return (
    <NodeShell title={title} subtitle={subtitle} color={color} selected={selected} shape={shape}>
      {children}
      {data?.errorOutput !== false && <PortRow right={{ id: 'error', label: 'Error' }} />}
    </NodeShell>
  );
}

export function StartNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'Start')} color="bg-green-600 border-green-400" selected={selected} shape="full">
      <PortRow right={{ id: 'out', label: 'Out' }} />
    </NodeShell>
  );
}

export function EndNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'End')} color="bg-gray-600 border-gray-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} />
    </NodeShell>
  );
}

export function AgentNode({ data, selected }) {
  const subtitle = data?.customized ? ('custom (' + (data?.agentRef || 'inline') + ')') : String(data?.agentRef || 'no agent');
  return (
    <NodeShell title={String(data?.title || 'Agent')} subtitle={subtitle} color="bg-blue-600 border-blue-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      {data?.requiresHumanApproval && <div className="px-3 pb-1 text-[10px] font-semibold text-amber-200">HITL required</div>}
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function ContextAgentExecutorNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'Context Agent')} subtitle={String(data?.agentRef || 'no agent')} color="bg-cyan-700 border-cyan-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <div className="px-3 pb-1 text-[10px] text-cyan-200">full context</div>
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function DecisionNode({ id, data, selected }) {
  const updateNodeInternals = useUpdateNodeInternals();
  const outgoing = useNodeConnections({ handleType: 'source' });
  useEffect(() => { updateNodeInternals(id); }, [id, updateNodeInternals]);
  const isUsed = (handleId) => outgoing.some((c) => c.sourceHandle === handleId);
  return (
    <NodeShell title={String(data?.title || 'Decision')} subtitle="if / else" color="bg-amber-600 border-amber-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'if', label: 'if', isConnectable: !isUsed('if') }} />
      <PortRow right={{ id: 'else', label: 'else', isConnectable: !isUsed('else') }} />
    </NodeShell>
  );
}

export function ForkNode({ id, data, selected }) {
  const updateNodeInternals = useUpdateNodeInternals();
  const outgoing = useNodeConnections({ handleType: 'source' });
  const maxIndex = outgoing.reduce((max, c) => {
    const m = /^out-(\d+)$/.exec(c.sourceHandle || '');
    return m ? Math.max(max, Number(m[1])) : max;
  }, -1);
  const handleCount = Math.max(1, maxIndex + 2);
  useEffect(() => { updateNodeInternals(id); }, [id, handleCount, updateNodeInternals]);
  const targetFor = (handleId) => outgoing.find((c) => c.sourceHandle === handleId)?.target;
  return (
    <NodeShell title={String(data?.title || 'Fork')} subtitle="parallel split" color="bg-cyan-600 border-cyan-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} />
      {Array.from({ length: handleCount }, (_, index) => {
        const handleId = `out-${index}`;
        const target = targetFor(handleId);
        return <PortRow key={handleId} right={{ id: handleId, label: target ? `→ ${target}` : `Out ${index + 1} +`, isConnectable: !target }} />;
      })}
    </NodeShell>
  );
}

export function JoinNode({ id, data, selected }) {
  const updateNodeInternals = useUpdateNodeInternals();
  const incoming = useNodeConnections({ handleType: 'target' });
  const maxIndex = incoming.reduce((max, c) => {
    const m = /^in-(\d+)$/.exec(c.targetHandle || '');
    return m ? Math.max(max, Number(m[1])) : max;
  }, -1);
  const handleCount = Math.max(1, maxIndex + 2);
  useEffect(() => { updateNodeInternals(id); }, [id, handleCount, updateNodeInternals]);
  const sourceFor = (handleId) => incoming.find((c) => c.targetHandle === handleId)?.source;
  return (
    <NodeShell title={String(data?.title || 'Join')} subtitle={`merge · ${String(data?.joinType || 'all')}`} color="bg-teal-600 border-teal-400" selected={selected}>
      {Array.from({ length: handleCount }, (_, index) => {
        const handleId = `in-${index}`;
        const source = sourceFor(handleId);
        const left = { id: handleId, label: source ? `← ${source}` : `In ${index + 1} +`, isConnectable: !source };
        return index === 0
          ? <PortRow key={handleId} left={left} right={{ id: 'out', label: 'Out' }} />
          : <PortRow key={handleId} left={left} />;
      })}
    </NodeShell>
  );
}

export function LoopNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'Loop')} subtitle={`max: ${String(data?.maxIterations || 3)}`} color="bg-orange-600 border-orange-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow left={{ id: 'end', label: 'End' }} right={{ id: 'start', label: 'Start' }} />
      {data?.exitCondition && <div className="truncate px-3 pb-1 text-[10px] text-orange-100/80" title={String(data.exitCondition)}>exit: {String(data.exitCondition)}</div>}
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function HitlNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'HITL')} subtitle={`approval · ${String(data?.assignee || 'human')}`} color="bg-purple-600 border-purple-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function SubworkflowNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'Sub-Workflow')} subtitle={String(data?.workflowRef || 'no reference')} color="bg-indigo-600 border-indigo-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function LlmEvalNode({ data, selected }) {
  const jsonOutput = data?.jsonOutput === false ? false : true;
  return (
    <NodeShell title={String(data?.title || 'LLM Eval')} subtitle={jsonOutput ? 'evaluate → JSON' : 'evaluate → text'} color="bg-purple-600 border-purple-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function LlmSummarizeNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || 'LLM Summarize')} subtitle="summarize" color="bg-indigo-600 border-indigo-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function CompactNode({ data, selected }) {
  const args = (data?.mcpArgs && typeof data.mcpArgs === 'object') ? data.mcpArgs : {};
  const mode = String(args.mode || 'truncate');
  return (
    <NodeShell title={String(data?.title || 'Compact')} subtitle={`compact: ${mode}`} color="bg-emerald-600 border-emerald-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function McpCallNode({ data, selected }) {
  const server = String(data?.mcpServer || '');
  const tool = String(data?.mcpTool || '');
  return (
    <NodeShell title={String(data?.title || 'MCP Call')} subtitle={server || tool ? `${server || '?'} / ${tool || '?'}` : 'no tool selected'} color="bg-fuchsia-700 border-fuchsia-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function CustomNode({ data, selected }) {
  return (
    <NodeShell title={String(data?.title || data?.label || 'Custom')} subtitle={String(data?.component || '')} color="bg-sky-600 border-sky-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}

export function RestCallNode({ data, selected }) {
  const args = (data?.mcpArgs && typeof data.mcpArgs === 'object') ? data.mcpArgs : {};
  return (
    <NodeShell title={String(data?.title || 'REST Call')} subtitle={String(args.method || 'GET') + ' ' + String(args.url || '')} color="bg-cyan-700 border-cyan-400" selected={selected}>
      <PortRow left={{ id: 'in', label: 'In' }} right={{ id: 'out', label: 'Out' }} />
      <PortRow right={{ id: 'error', label: 'Error' }} />
    </NodeShell>
  );
}