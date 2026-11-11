import { useEffect } from 'react';
import {
  useNodeConnections,
  useUpdateNodeInternals,
  type NodeProps,
} from '@xyflow/react';
import { NodeShell, PortRow } from './NodeShell';

interface Branch {
  branchId?: string;
  condition?: string;
  target?: string;
}

export function DecisionNode({ id, data, selected }: NodeProps) {
  const updateNodeInternals = useUpdateNodeInternals();
  const outgoing = useNodeConnections({ handleType: 'source' });
  const branches = Array.isArray(data.branches) ? (data.branches as Branch[]) : [];
  const signature = branches.map((b) => String(b.branchId || '')).join('|');

  // Branch count and IDs change the handle layout at runtime.
  useEffect(() => {
    updateNodeInternals(id);
  }, [id, signature, updateNodeInternals]);

  const isUsed = (handleId: string) =>
    outgoing.some((connection) => connection.sourceHandle === handleId);

  const defaultHandle = 'default';

  return (
    <NodeShell
      title={String(data.title || 'Decision')}
      subtitle="if / else"
      color="bg-amber-600 border-amber-400"
      selected={selected}
    >
      <PortRow
        left={{ id: 'in', label: 'In' }}
        right={{
          id: defaultHandle,
          label: 'Default',
          isConnectable: !isUsed(defaultHandle),
        }}
      />
      {branches.map((branch, index) => {
        const branchId = String(branch.branchId || `b${index + 1}`);
        const handleId = `branch-${branchId}`;
        const label = branch.condition ? String(branch.condition) : `Branch ${index + 1}`;
        return (
          <PortRow
            key={handleId}
            right={{ id: handleId, label, isConnectable: !isUsed(handleId) }}
          />
        );
      })}
      {branches.length === 0 && (
        <div className="px-3 pb-1 text-[10px] text-amber-200">
          Keine Branches — im Panel anlegen
        </div>
      )}
    </NodeShell>
  );
}
