interface ConfirmDialogProps {
  message: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({ message, onConfirm, onCancel }: ConfirmDialogProps) {
  if (!message) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Confirm"
      data-testid="confirm-dialog"
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        background: 'rgba(0,0,0,0.6)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 2000,
      }}
    >
      <div style={{ background: '#2d2d2d', color: '#d4d4d4', border: '1px solid #555', borderRadius: 8, padding: 16, minWidth: 280, maxWidth: '80vw' }}>
        <p style={{ margin: '0 0 12px', fontSize: 13, whiteSpace: 'pre-wrap' }}>{message}</p>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button
            type="button"
            onClick={onCancel}
            data-testid="confirm-no"
            aria-label="Nein"
            style={{ background: '#555', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}
          >
            Nein
          </button>
          <button
            type="button"
            onClick={onConfirm}
            data-testid="confirm-yes"
            aria-label="Ja"
            style={{ background: '#d9534f', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}
          >
            Ja
          </button>
        </div>
      </div>
    </div>
  );
}

export default ConfirmDialog;
