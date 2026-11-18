import { useEffect } from 'react';

interface ToastProps {
  message: string | null;
  onClose: () => void;
  duration?: number;
}

export function Toast({ message, onClose, duration = 3500 }: ToastProps) {
  useEffect(() => {
    if (!message) return;
    const t = setTimeout(() => onClose(), duration);
    return () => clearTimeout(t);
  }, [message, duration, onClose]);

  if (!message) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="toast"
      style={{
        position: 'fixed',
        bottom: 24,
        left: '50%',
        transform: 'translateX(-50%)',
        background: '#1e1e1e',
        color: '#d4d4d4',
        border: '1px solid #555',
        borderRadius: 6,
        padding: '8px 16px',
        fontSize: 13,
        zIndex: 2000,
        boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
        maxWidth: '80vw',
        whiteSpace: 'pre-wrap',
      }}
    >
      {message}
    </div>
  );
}

export default Toast;
