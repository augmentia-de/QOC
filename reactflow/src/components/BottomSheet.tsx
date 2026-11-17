import { useEffect } from 'react';

export function BottomSheet({ open, onClose, title, children }) {
  useEffect(() => {
    if (!open) return;
    const handler = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div style={{ position: 'fixed', left: 0, right: 0, bottom: 0, top: '40%', background: '#1e1e1e', borderTop: '1px solid #444', zIndex: 1000, overflow: 'auto', padding: 8, color: '#d4d4d4' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <strong>{title}</strong>
        <button onClick={onClose} style={{ background: 'none', border: 'none', color: '#999', fontSize: 16 }}>x</button>
      </div>
      {children}
    </div>
  );
}