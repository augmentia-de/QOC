import { useEffect, useRef, useState } from 'react'
import { Files, Workspace } from '../services/qocApi'

interface FileEntry {
  name: string
  directory: boolean
  relative: string
}

const EDITABLE_EXT = new Set(['txt', 'md', 'json', 'yaml', 'yml', 'xml', 'html', 'css', 'js', 'ts', 'java', 'py', 'sh', 'bash', 'properties', 'conf', 'cfg', 'ini', 'toml', 'csv', 'log', 'sql', 'kt', 'go', 'rs', 'c', 'cpp', 'h', 'hpp'])

function parentPath(path: string): string {
  if (!path || path === '.' || path === '/') return '.'
  const normalized = path.replace(/\/+$/, '')
  const index = normalized.lastIndexOf('/')
  if (index < 0) return '.'
  if (index === 0) return '/'
  return normalized.slice(0, index)
}

function joinPath(dir: string, name: string): string {
  if (!name) return dir || '.'
  if (!dir || dir === '.') return name
  return dir.replace(/\/+$/, '') + '/' + (name.startsWith('/') ? name.slice(1) : name)
}

function fileName(path: string): string {
  if (!path) return ''
  const normalized = path.replace(/\/+$/, '')
  const index = normalized.lastIndexOf('/')
  return index < 0 ? normalized : normalized.slice(index + 1)
}

function isEditable(path: string, content: string): boolean {
  if (!path || content.length > 1000000) return false
  const lower = path.toLowerCase()
  const dot = lower.lastIndexOf('.')
  const ext = dot >= 0 ? lower.slice(dot + 1) : ''
  if (!ext) return true
  return EDITABLE_EXT.has(ext)
}

export default function FilesPage() {
  const [currentDir, setCurrentDir] = useState('.')
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [currentFile, setCurrentFile] = useState<string | null>(null)
  const [fileContent, setFileContent] = useState('')
  const [totalChars, setTotalChars] = useState(0)
  const [truncated, setTruncated] = useState(false)
  const [editing, setEditing] = useState(false)
  const [editorContent, setEditorContent] = useState('')
  const [editorStatus, setEditorStatus] = useState('')
  const [workspace, setWorkspace] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const uploadFileRef = useRef<HTMLInputElement>(null)
  const uploadDirRef = useRef<HTMLInputElement>(null)

  const loadFiles = async (path: string) => {
    const target = path || '.'
    setLoading(true)
    setError('')
    try {
      const data = await Files.list(target)
      const list = Array.isArray(data?.data) ? data.data as FileEntry[] : []
      setEntries(list)
      setCurrentDir(target)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setEntries([])
    } finally {
      setLoading(false)
    }
  }

  const viewFile = async (path: string) => {
    try {
      const data = await Files.read(path)
      const info = data?.data ?? {}
      const fullContent = String(info.content ?? '')
      setCurrentFile(path)
      setFileContent(fullContent.length > 20000 ? fullContent.slice(0, 20000) : fullContent)
      setTotalChars(Number(info.totalChars ?? fullContent.length))
      setTruncated(Boolean(info.truncated) || fullContent.length > 20000)
      setEditing(false)
    } catch (err) {
      setCurrentFile(null)
      setFileContent('Error: ' + (err instanceof Error ? err.message : String(err)))
      setTotalChars(0)
      setTruncated(false)
    }
  }

  const saveFile = async () => {
    if (!currentFile) return
    if (editorContent.length > 1000000) {
      setEditorStatus('Too large (max. 1000000 characters)')
      return
    }
    setEditorStatus('Saving…')
    try {
      await Files.create(currentFile, editorContent)
      setEditorStatus('Gespeichert.')
      await viewFile(currentFile)
    } catch (err) {
      setEditorStatus('Error: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const createFile = async () => {
    const name = window.prompt('Dateiname:')
    if (!name) return
    const content = window.prompt('Inhalt (leer = leer):') || ''
    try {
      await Files.create(joinPath(currentDir, name), content)
      await loadFiles(currentDir)
    } catch (err) {
      window.alert('Error: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const deletePath = async (path: string) => {
    if (!window.confirm('Delete?\n\n' + path)) return
    try {
      await Files.delete(path)
      if (currentFile === path) {
        setCurrentFile(null)
        setFileContent('Select a file…')
      }
      await loadFiles(currentDir)
    } catch (err) {
      window.alert('Delete failed: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const downloadFile = async (path: string) => {
    try {
      const response = await Files.downloadFile(path)
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = fileName(path)
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1500)
    } catch (err) {
      window.alert('Download fehlgeschlagen: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const downloadDirectory = async (path: string) => {
    try {
      const response = await Files.downloadDirectory(path)
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = fileName(path) + '.zip'
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1500)
    } catch (err) {
      window.alert('Download fehlgeschlagen: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  const uploadFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return
    let failed = 0
    for (const file of Array.from(files)) {
      const relative = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
      const target = joinPath(currentDir, relative)
      try {
        await Files.upload(target, file)
      } catch {
        failed += 1
      }
    }
    await loadFiles(currentDir)
    if (failed > 0) window.alert(failed + ' file(s) could not be uploaded.')
  }

  const loadWorkspace = async () => {
    try {
      const data = await Workspace.get()
      setWorkspace(String(data?.workspace ?? ''))
    } catch {
      setWorkspace('')
    }
  }

  useEffect(() => {
    void loadFiles('.')
    void loadWorkspace()
  }, [])

  const buttonStyle = (background: string) => ({ padding: '4px 10px', background, color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 })

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr', gap: 12, padding: 16, height: '100vh', boxSizing: 'border-box', background: '#161616', color: '#d4d4d4' }}>
      <section style={{ display: 'flex', flexDirection: 'column', minHeight: 0, border: '1px solid #333', borderRadius: 6, background: '#1e1e1e' }}>
        <div style={{ padding: 8, borderBottom: '1px solid #333', display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'center' }}>
          <strong style={{ color: '#5bc0de', marginRight: 4 }}>Dateien</strong>
          <span style={{ fontSize: 11, color: '#999', flex: 1 }}>{currentDir === '.' ? '.' : './' + currentDir.replace(/^\.\/+/, '')}</span>
          <button onClick={() => void loadFiles(currentDir)} style={buttonStyle('#555')}>↻</button>
          <button onClick={() => void createFile()} style={buttonStyle('#337ab7')}>+</button>
          <button onClick={() => uploadFileRef.current?.click()} style={buttonStyle('#555')}>⤒</button>
          <button onClick={() => uploadDirRef.current?.click()} style={buttonStyle('#555')}>⇪</button>
        </div>
        <input ref={uploadFileRef} type="file" multiple hidden onChange={(event) => void uploadFiles(event.target.files)} />
        <input ref={uploadDirRef} type="file" multiple hidden {...({ webkitdirectory: '' } as unknown as React.InputHTMLAttributes<HTMLInputElement>)} onChange={(event) => void uploadFiles(event.target.files)} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: 8 }}>
          {loading ? <p style={{ color: '#999', fontSize: 12 }}>Lade...</p> : entries.length === 0 ? <p style={{ color: '#999', fontSize: 12 }}>(leer)</p> : (
            <div style={{ display: 'grid', gap: 4 }}>
              {currentDir !== '.' && currentDir !== '/' ? (
                <div onClick={() => void loadFiles(parentPath(currentDir))} style={{ padding: '4px 6px', borderRadius: 4, cursor: 'pointer', background: '#2d2d2d' }}>..</div>
              ) : null}
              {entries.map((entry) => (
                <div key={entry.relative} onClick={() => entry.directory ? void loadFiles(entry.relative) : void viewFile(entry.relative)}
                  style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '4px 6px', borderRadius: 4, cursor: 'pointer', background: currentFile === entry.relative ? '#337ab7' : '#2d2d2d' }}>
                  <span style={{ fontSize: 12 }}>{entry.directory ? '📁 ' : '📄 '}{entry.name}</span>
                  <span style={{ display: 'flex', gap: 4 }}>
                    <button onClick={(event) => { event.stopPropagation(); entry.directory ? void downloadDirectory(entry.relative) : void downloadFile(entry.relative) }} style={buttonStyle('#555')}>⬇</button>
                    <button onClick={(event) => { event.stopPropagation(); void deletePath(entry.relative) }} style={buttonStyle('#c0392b')}>🗑</button>
                  </span>
                </div>
              ))}
            </div>
          )}
          {error ? <p style={{ color: '#f85149', fontSize: 12, marginTop: 8 }}>{error}</p> : null}
        </div>
        {workspace ? <div style={{ padding: 8, borderTop: '1px solid #333', fontSize: 11, color: '#999' }}>Workspace: {workspace}</div> : null}
      </section>

      <section style={{ display: 'flex', flexDirection: 'column', minHeight: 0, border: '1px solid #333', borderRadius: 6, background: '#1e1e1e' }}>
        <div style={{ padding: 8, borderBottom: '1px solid #333', display: 'flex', gap: 8, alignItems: 'center' }}>
          <strong style={{ color: '#5bc0de' }}>Datei</strong>
          <span style={{ fontSize: 11, color: '#999', flex: 1 }}>{currentFile || 'Select a file…'}</span>
          {currentFile && isEditable(currentFile, fileContent) ? (
            <button onClick={() => { setEditing(true); setEditorContent(fileContent); setEditorStatus('') }} style={buttonStyle('#555')}>✎</button>
          ) : null}
        </div>
        {editing ? (
          <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: 8, gap: 8 }}>
            <textarea value={editorContent} onChange={(event) => setEditorContent(event.target.value)} style={{ flex: 1, minHeight: 0, background: '#0b0d12', color: '#d4d4d4', border: '1px solid #333', borderRadius: 6, padding: 8, fontFamily: 'monospace', fontSize: 12, resize: 'none' }} />
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <button onClick={() => void saveFile()} style={buttonStyle('#27ae60')}>Save</button>
              <button onClick={() => setEditing(false)} style={buttonStyle('#555')}>Cancel</button>
              <span style={{ fontSize: 11, color: '#999' }}>{editorStatus}</span>
            </div>
          </div>
        ) : (
          <pre style={{ flex: 1, minHeight: 0, margin: 0, padding: 8, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, background: '#0b0d12', color: '#d4d4d4' }}>
            {truncated ? fileContent + '\n\n… Anzeige auf 20000 characters truncated (file: ' + totalChars.toLocaleString('de-DE') + ' characters).' : fileContent}
          </pre>
        )}
      </section>
    </div>
  )
}