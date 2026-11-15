import { useEffect } from 'react';

interface BottomSheetProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
}

export default function BottomSheet({ open, onClose, title, children }: BottomSheetProps) {
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-end md:hidden">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-h-[80vh] overflow-y-auto bg-gray-900 rounded-t-2xl border-t border-gray-700 p-4">
        {title && <h3 className="text-lg font-semibold mb-3">{title}</h3>}
        {children}
      </div>
    </div>
  );
}