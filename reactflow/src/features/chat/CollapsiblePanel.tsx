import { CSSProperties, ReactNode } from 'react';

interface CollapsiblePanelProps {
  id: string;
  title: string;
  collapsed: boolean;
  onToggle: (id: string) => void;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
  height?: number;
}

export function CollapsiblePanel({ id, title, collapsed, onToggle, actions, children, className, style, height }: CollapsiblePanelProps) {
  const merged = { ...style, ...(height !== undefined ? { height, flex: '0 0 auto' } : {}) }
  return (
    <section className={'chat-section' + (collapsed ? ' collapsed' : '') + (className ? ' ' + className : '')} style={merged}>
      <h2 onClick={(e) => {
        if ((e.target as HTMLElement)?.tagName === 'BUTTON') return;
        onToggle(id);
      }}>
        <span>{title}</span>
        <span className="chat-section-actions">{actions}</span>
      </h2>
      <div className="chat-section-body">{children}</div>
    </section>
  );
}