import { useRef, useCallback } from 'react';

interface SplitterProps {
  orientation: 'vertical' | 'horizontal';
  onDrag: (delta: number) => void;
  onDragEnd?: () => void;
  disabled?: boolean;
}

/**
 * A drag handle for resizing panels. Supports mouse and touch.
 * Calls onDrag with the delta (px) since drag start on each move.
 */
export function Splitter({ orientation, onDrag, onDragEnd, disabled }: SplitterProps) {
  const startRef = useRef<number | null>(null);
  const draggingRef = useRef(false);

  const onPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (disabled) return;
    draggingRef.current = true;
    startRef.current = orientation === 'vertical' ? e.clientX : e.clientY;
    (e.currentTarget as HTMLDivElement).setPointerCapture(e.pointerId);
    document.body.style.cursor = orientation === 'vertical' ? 'col-resize' : 'row-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
  }, [orientation, disabled]);

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current || startRef.current === null) return;
    const pos = orientation === 'vertical' ? e.clientX : e.clientY;
    onDrag(pos - startRef.current);
    startRef.current = pos;
  }, [orientation, onDrag]);

  const onPointerUp = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    startRef.current = null;
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
    onDragEnd?.();
  }, [onDragEnd]);

  return (
    <div
      className={orientation === 'vertical' ? 'chat-side-resizer' : 'chat-panel-resizer'}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      style={{ touchAction: 'none', display: disabled ? 'none' : '' }}
    />
  );
}