import type { ReactNode } from 'react';
import { Handle, Position } from '@xyflow/react';

export interface PortSpec {
  id?: string;
  label: string;
  isConnectable?: boolean;
}

const handleStyle = {
  width: 10,
  height: 10,
  background: '#0f172a',
  borderColor: 'rgba(255,255,255,0.85)',
} as const;

/**
 * A single row with an optional target port on the left and an optional
 * source port on the right. The row is the positioned ancestor of the
 * handles, so React Flow places them exactly on the node border.
 */
export function PortRow({ left, right }: { left?: PortSpec; right?: PortSpec }) {
  return (
    <div className="relative flex h-6 items-center gap-2 px-3">
      <div className="flex min-w-0 flex-1 justify-start">
        <span
          className="truncate text-[10px] font-medium text-white/80"
          title={left?.label}
        >
          {left?.label || ''}
        </span>
      </div>
      <div className="flex min-w-0 flex-1 justify-end">
        <span
          className="truncate text-right text-[10px] font-medium text-white/80"
          title={right?.label}
        >
          {right?.label || ''}
        </span>
      </div>
      {left && (
        <Handle
          id={left.id}
          type="target"
          position={Position.Left}
          isConnectable={left.isConnectable ?? true}
          style={handleStyle}
        />
      )}
      {right && (
        <Handle
          id={right.id}
          type="source"
          position={Position.Right}
          isConnectable={right.isConnectable ?? true}
          style={handleStyle}
        />
      )}
    </div>
  );
}

export function NodeShell({
  title,
  subtitle,
  color,
  selected,
  shape = 'rounded',
  className = '',
  children,
}: {
  title: string;
  subtitle?: string;
  color: string;
  selected?: boolean;
  shape?: 'rounded' | 'full';
  className?: string;
  children: ReactNode;
}) {
  return (
    <div
      className={`min-w-[160px] ${
        shape === 'full' ? 'rounded-full' : 'rounded-lg'
      } border-2 text-white shadow-lg ${color} ${
        selected ? 'ring-2 ring-white/80' : ''
      } ${className}`}
    >
      <div className="border-b border-white/10 px-3 py-1.5">
        <div className="truncate text-sm font-bold">{title}</div>
        {subtitle && <div className="truncate text-[10px] opacity-70">{subtitle}</div>}
      </div>
      <div className="py-1">{children}</div>
    </div>
  );
}
