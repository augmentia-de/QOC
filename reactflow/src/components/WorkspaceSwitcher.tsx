import { useEffect, useState } from 'react'
import { Workspace } from '../services/qocApi'

interface WorkspaceSwitcherProps {
  /** Called after a successful switch with the new workspace path. */
  onChanged?: (workspace: string) => void
  /** Show the top border used in the Files page footer. Default: true. */
  bordered?: boolean
}

/**
 * Inline workspace switcher for the Files page.
 *
 * <p>The backend validates the target against the immutable workspace base:
 * admins may switch inside the base, regular users only inside their
 * {@code user-ws/<user>} folder.</p>
 */
export default function WorkspaceSwitcher({ onChanged, bordered = true }: WorkspaceSwitcherProps) {
  const [current, setCurrent] = useState('')
  const [path, setPath] = useState('')
  const [create, setCreate] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const load = async () => {
    try {
      const data = await Workspace.get()
      const workspace = String(data?.workspace ?? '')
      setCurrent(workspace)
      setPath(workspace)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const submit = async () => {
    const target = path.trim()
    if (!target) {
      setError('Pfad darf nicht leer sein.')
      return
    }
    setBusy(true)
    setError('')
    setSuccess('')
    try {
      const data = await Workspace.set(target, create)
      const next = String(data?.workspace ?? target)
      setCurrent(next)
      setPath(next)
      setSuccess('Workspace gewechselt.')
      onChanged?.(next)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ padding: 8, borderTop: bordered ? '1px solid #333' : undefined, fontSize: 11, color: '#999' }}>
      <div style={{ marginBottom: 4 }}>
        Workspace: <span style={{ color: '#d4d4d4' }}>{current || '—'}</span>
      </div>
      <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
        <input
          value={path}
          onChange={(event) => setPath(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') void submit()
          }}
          placeholder="/pfad/zum/projekt"
          aria-label="Workspace-Pfad"
          style={{
            flex: 1,
            minWidth: 0,
            padding: '4px 6px',
            background: '#0b0d12',
            color: '#d4d4d4',
            border: '1px solid #333',
            borderRadius: 4,
            fontSize: 11,
          }}
        />
        <button
          onClick={() => void submit()}
          disabled={busy}
          style={{
            padding: '4px 8px',
            background: '#337ab7',
            color: '#fff',
            border: 'none',
            borderRadius: 4,
            cursor: busy ? 'default' : 'pointer',
            fontSize: 11,
            opacity: busy ? 0.5 : 1,
          }}
        >
          {busy ? '…' : 'Setzen'}
        </button>
      </div>
      <label style={{ display: 'flex', gap: 4, alignItems: 'center', marginTop: 4 }}>
        <input
          type="checkbox"
          checked={create}
          onChange={(event) => setCreate(event.target.checked)}
        />
        Verzeichnis erstellen
      </label>
      {error ? <div style={{ color: '#f85149', marginTop: 4 }}>{error}</div> : null}
      {success ? <div style={{ color: '#5cb85c', marginTop: 4 }}>{success}</div> : null}
    </div>
  )
}
